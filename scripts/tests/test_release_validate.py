"""Exercise release gates with real project files and external process substitutes."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from release_validate import initialize
from tests.support import SCRIPTS


class ReleaseValidationTests(unittest.TestCase):
    """Keep gate ordering, failure propagation, and clean coverage observable."""

    def setUp(self) -> None:
        """Create a stable project with a matching snapshot and controlled tools."""
        temporary = tempfile.TemporaryDirectory(prefix="release gates ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        scripts = self.root / "scripts"
        scripts.mkdir()
        for name in ("release_validate.py", "docs_release.py"):
            shutil.copy2(SCRIPTS / name, scripts / name)
        (self.root / "pom.xml").write_text("<project><version>1.2.3</version></project>")
        for directory in ("docs", "website/versioned_docs/version-1.2.3"):
            folder = self.root / directory
            folder.mkdir(parents=True)
            (folder / "index.md").write_text("# Release\n")
        (self.root / "website/versions.json").write_text('["1.2.3"]')
        sidebars = self.root / "website/versioned_sidebars"
        sidebars.mkdir()
        (sidebars / "version-1.2.3-sidebars.json").write_text("{}")
        binaries = self.root / "target/frontend/node"
        binaries.mkdir(parents=True)
        self.executable(binaries / "node", 'print("{}")\n')
        body = """
import json, os, sys
from pathlib import Path
record = {
    'tool': Path(sys.argv[0]).name, 'args': sys.argv[1:], 'cwd': str(Path.cwd()),
    'coverage_file': os.environ.get('COVERAGE_FILE'), 'coverage_rcfile': os.environ.get('COVERAGE_RCFILE'),
    'wrapper_commit': os.environ.get('UVW_GIT_COMMIT'),
}
with open(os.environ['RELEASE_TEST_LOG'], 'a') as stream:
    stream.write(json.dumps(record) + '\\n')
if os.environ.get('RELEASE_TEST_FAIL') == ' '.join(sys.argv[1:]):
    sys.exit(7)
"""
        for path in (scripts / "uv", binaries / "javadoc", self.root / "target/code-tracer"):
            self.executable(path, body)
        self.log = self.root / "commands.jsonl"
        self.environment = dict(os.environ, RELEASE_TEST_LOG=str(self.log))

    def executable(self, path: Path, body: str) -> None:
        """Write an executable stand-in at an external-tool boundary."""
        path.write_text(f"#!{sys.executable}\n{body}")
        path.chmod(0o755)

    def run_gate(self, phase: str) -> subprocess.CompletedProcess[str]:
        """Invoke the production CLI from another working directory."""
        return subprocess.run(
            [sys.executable, str(self.root / "scripts/release_validate.py"), phase],
            cwd=self.root.parent,
            env=self.environment,
            text=True,
            capture_output=True,
            timeout=30,
        )

    def test_initialization_preserves_stale_coverage_and_is_repeatable(self) -> None:
        """Archive old execution data before tests without cleaning other audit evidence."""
        previous = self.root / "target/jacoco.exec"
        unrelated = self.root / "target/previous-audit.txt"
        unrelated.write_text("keep me")
        initialize(self.root)
        self.assertFalse((self.root / "target/release-validation").exists())
        for contents in (b"old coverage", b"retry coverage"):
            previous.write_bytes(contents)
            result = self.run_gate("initialize")
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertFalse(previous.exists())
            self.assertIn("Preserved previous Java coverage", result.stdout)
        saved = self.root.glob("target/release-validation/previous-coverage-*/jacoco.exec")
        self.assertEqual({path.read_bytes() for path in saved}, {b"old coverage", b"retry coverage"})
        self.assertEqual(unrelated.read_text(), "keep me")
        self.assertFalse(self.log.exists())

    def test_snapshot_sources_are_rejected_before_any_gate(self) -> None:
        """The release plugin must transform the POM before entering the full gate."""
        (self.root / "pom.xml").write_text("<project><version>1.2.3-SNAPSHOT</version></project>")
        for phase in ("initialize", "verify"):
            result = self.run_gate(phase)
            self.assertEqual(result.returncode, 2)
            self.assertIn("requires a stable x.y.z POM", result.stderr)
        self.assertFalse(self.log.exists())

    def test_verify_runs_full_quality_and_compatibility_gates_serially(self) -> None:
        """Run quality checks, clean combined coverage, fixtures, launchers, and Javadoc in order."""
        result = self.run_gate("verify")
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertEqual(len(calls), 12)
        self.assertEqual(
            [call["args"][2:] for call in calls[:7]],
            [
                ["ruff", "check", "scripts"],
                ["ruff", "format", "--check", "scripts"],
                ["ty", "check", "scripts"],
                ["coverage", "erase"],
                ["coverage", "run", "-m", "unittest", "discover", "-s", "scripts/tests", "-t", "scripts"],
                ["coverage", "combine"],
                ["coverage", "report"],
            ],
        )
        self.assertIn("test_verify.py", calls[7]["args"])
        self.assertIn("examples/verify.py", calls[8]["args"])
        self.assertEqual(calls[9]["args"], ["--version"])
        self.assertEqual(calls[10]["args"], ["--help"])
        self.assertEqual(calls[11]["tool"], "javadoc")
        self.assertIn("-private", calls[11]["args"])
        for call in calls:
            self.assertEqual(Path(call["cwd"]), self.root.resolve())
            self.assertEqual(call["coverage_file"], str(self.root / ".coverage"))
            self.assertEqual(call["coverage_rcfile"], str(self.root / "scripts/pyproject.toml"))
            self.assertEqual(call["wrapper_commit"], "never")
        self.assertIn("Maven's docsite gate must also finish", result.stdout)

    def test_failed_check_prevents_later_gates(self) -> None:
        """A failed coverage threshold cannot continue to fixtures or report success."""
        self.environment["RELEASE_TEST_FAIL"] = "run --locked coverage report"
        result = self.run_gate("verify")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("exit status 7", result.stderr)
        self.assertNotIn("Local release gates passed", result.stdout)
        self.assertEqual(len(self.log.read_text().splitlines()), 7)

    def test_stale_documentation_fails_before_external_quality_checks(self) -> None:
        """A release snapshot mismatch blocks preparation before expensive gates."""
        (self.root / "docs/index.md").write_text("# Changed after preparation\n")
        result = self.run_gate("verify")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Release snapshot differs", result.stderr)
        self.assertFalse(self.log.exists())


if __name__ == "__main__":
    unittest.main()
