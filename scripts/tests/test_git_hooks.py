"""Exercise staged Git hook checks through real commits and temporary repositories."""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from typing import Any

from tests.support import SCRIPTS, executable

MAVEN = r"""
import json, os, sys
from pathlib import Path
root = Path.cwd()
sources = {str(path.relative_to(root)): path.read_text() for path in root.rglob('*.java')}
record = dict(arguments=sys.argv[1:], directory=str(root), sources=sources,
              pom=(root / 'pom.xml').read_text(),
              checkstyle=(root / 'src/main/resources/cs1302_checks_extended.xml').read_text())
with Path(os.environ['HOOK_TEST_MAVEN_LOG']).open('a') as stream:
    stream.write(json.dumps(record) + '\n')
if os.environ.get('HOOK_TEST_MAVEN_EXIT'):
    sys.exit(int(os.environ['HOOK_TEST_MAVEN_EXIT']))
sys.exit(23 if any('BAD_STYLE' in source for source in sources.values()) else 0)
"""

type CheckoutState = tuple[str, dict[str, tuple[int, bytes]]]


class GitHooksTests(unittest.TestCase):
    """Keep fast commits faithful to staged content without mutating the checkout."""

    def setUp(self) -> None:
        """Create an actual Git checkout with real hooks and a Maven process stand-in."""
        temporary = tempfile.TemporaryDirectory(prefix="staged hooks ")
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.root = self.directory / "checkout with spaces"
        self.root.mkdir()
        self.log = self.directory / "Maven process calls.jsonl"
        self.environment = {
            **os.environ,
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": os.devnull,
            "HOOK_TEST_MAVEN_LOG": str(self.log),
        }
        self.environment.pop("HOOK_TEST_MAVEN_EXIT", None)
        self.git("init", "-q")
        self.git("config", "user.name", "Hook Test")
        self.git("config", "user.email", "hooks@example.invalid")
        self.git("config", "commit.gpgSign", "false")
        self.git("config", "core.hooksPath", ".githooks")
        hooks = self.root / ".githooks"
        hooks.mkdir()
        for name in ("pre-commit", "pre-commit-checkstyle", "pre-commit-coverage"):
            shutil.copy2(SCRIPTS.parent / ".githooks" / name, hooks / name)
        executable(self.root / "mvnw", MAVEN)
        self.pom = self.root / "pom.xml"
        self.pom.write_text("<project><profile><id>pre-commit-coverage</id></profile></project>\n")
        self.checkstyle = self.root / "src/main/resources/cs1302_checks_extended.xml"
        self.checkstyle.parent.mkdir(parents=True)
        self.checkstyle.write_text('<module name="Checker"/>\n')
        self.source = self.root / "Main.java"
        self.source.write_text("class Main {}\n")
        self.git("add", "--", ".")

    def git(self, *arguments: str, check: bool = True) -> subprocess.CompletedProcess[str]:
        """Run real Git in the disposable checkout with isolated user configuration."""
        return subprocess.run(
            ["git", *arguments],
            cwd=self.root,
            env=self.environment,
            check=check,
            capture_output=True,
            text=True,
            timeout=10,
        )

    def commit(self) -> subprocess.CompletedProcess[str]:
        """Let Git invoke the installed pre-commit hook for an actual local commit."""
        return self.git("commit", "-q", "--no-gpg-sign", "-m", "Exercise staged checks", check=False)

    def calls(self) -> list[dict[str, Any]]:
        """Read the observable Maven calls made by the hook's isolated snapshots."""
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def checkout_state(self) -> CheckoutState:
        """Capture the staged tree and all non-Git file contents and permissions."""
        files = {
            str(path.relative_to(self.root)): (path.stat().st_mode & 0o777, path.read_bytes())
            for path in self.root.rglob("*")
            if path.is_file() and ".git" not in path.relative_to(self.root).parts
        }
        return self.git("write-tree").stdout.strip(), files

    def assert_checkstyle_only(self) -> dict[str, Any]:
        """Require exactly one staged Checkstyle call and cleaned temporary output."""
        calls = self.calls()
        self.assertEqual(len(calls), 1, calls)
        call = calls[0]
        self.assertEqual(call["arguments"], ["-B", "-ntp", "checkstyle:check"])
        self.assertNotEqual(Path(call["directory"]), self.root)
        self.assertFalse(Path(call["directory"]).exists())
        return call

    def test_staged_bad_source_blocks_working_good_source(self) -> None:
        """Reject staged violations even after the working copy has been corrected."""
        staged = "class Main { /* BAD_STYLE */ }\n"
        self.source.write_text(staged)
        self.git("add", "--", "Main.java")
        self.source.write_text("class Main {}\n")
        before = self.checkout_state()
        result = self.commit()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("staged Checkstyle check failed", result.stderr)
        self.assertEqual(self.assert_checkstyle_only()["sources"]["Main.java"], staged)
        self.assertEqual(self.checkout_state(), before)
        self.assertNotEqual(self.git("rev-parse", "--verify", "HEAD", check=False).returncode, 0)

    def test_staged_good_source_ignores_working_bad_source(self) -> None:
        """Commit the valid index while preserving unstaged violations and untracked files."""
        self.source.write_text("class Main { /* BAD_STYLE */ }\n")
        (self.root / "Untracked.java").write_text("class Untracked { /* BAD_STYLE */ }\n")
        before = self.checkout_state()
        result = self.commit()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.assert_checkstyle_only()["sources"], {"Main.java": "class Main {}\n"})
        self.assertEqual(self.checkout_state(), before)
        self.assertEqual(self.git("show", "HEAD:Main.java").stdout, "class Main {}\n")

    def test_staged_whitespace_stops_before_maven(self) -> None:
        """Block trailing whitespace in the index before invoking Maven."""
        self.source.write_text("class Main {} \n")
        self.git("add", "--", "Main.java")
        self.source.write_text("class Main {}\n")
        before = self.checkout_state()
        result = self.commit()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("trailing whitespace", result.stdout + result.stderr)
        self.assertEqual(self.calls(), [])
        self.assertEqual(self.checkout_state(), before)

    def test_unstaged_whitespace_does_not_block_commit(self) -> None:
        """Apply whitespace checks to the index when the working copy has trailing spaces."""
        self.source.write_text("class Main {} \n")
        before = self.checkout_state()
        result = self.commit()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_checkstyle_only()
        self.assertEqual(self.checkout_state(), before)

    def test_unusual_staged_filenames(self) -> None:
        """Preserve spaces, newlines, Unicode, and leading hyphens in exported filenames."""
        names = ("file with spaces.java", "line\nbreak.java", "café.java", "--leading.java")
        for name in names:
            (self.root / name).write_text("class Example {}\n")
        self.git("add", "--", *names)
        before = self.checkout_state()
        result = self.commit()
        self.assertEqual(result.returncode, 0, result.stderr)
        sources = self.assert_checkstyle_only()["sources"]
        self.assertEqual(set(sources), {"Main.java", *names})
        for name in names:
            self.assertEqual(sources[name], "class Example {}\n")
        self.assertEqual(self.checkout_state(), before)

    def test_staged_build_and_style_configuration(self) -> None:
        """Use indexed Maven and Checkstyle configuration despite unstaged replacements."""
        staged_pom = self.pom.read_text()
        staged_style = self.checkstyle.read_text()
        self.pom.write_text("unstaged Maven configuration\n")
        self.checkstyle.write_text("unstaged Checkstyle configuration\n")
        before = self.checkout_state()
        result = self.commit()
        self.assertEqual(result.returncode, 0, result.stderr)
        call = self.assert_checkstyle_only()
        self.assertEqual(call["pom"], staged_pom)
        self.assertEqual(call["checkstyle"], staged_style)
        self.assertEqual(self.checkout_state(), before)

    def test_maven_failure_blocks_commit(self) -> None:
        """Propagate an external Maven failure as a rejected commit without staging edits."""
        self.environment["HOOK_TEST_MAVEN_EXIT"] = "19"
        before = self.checkout_state()
        result = self.commit()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("staged Checkstyle check failed", result.stderr)
        self.assert_checkstyle_only()
        self.assertEqual(self.checkout_state(), before)

    def test_strict_coverage_helper_remains_explicit(self) -> None:
        """Keep the existing explicit staged full-suite and strict coverage command available."""
        self.source.write_text("class Main { /* BAD_STYLE */ }\n")
        before = self.checkout_state()
        result = subprocess.run(
            [str(self.root / ".githooks/pre-commit-coverage")],
            cwd=self.root,
            env=self.environment,
            capture_output=True,
            text=True,
            timeout=10,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.calls()
        self.assertEqual(len(calls), 1, calls)
        self.assertEqual(calls[0]["arguments"], ["-B", "-ntp", "-Ppre-commit-coverage", "clean", "test"])
        self.assertEqual(calls[0]["sources"], {"Main.java": "class Main {}\n"})
        self.assertFalse(Path(calls[0]["directory"]).exists())
        self.assertEqual(self.checkout_state(), before)
