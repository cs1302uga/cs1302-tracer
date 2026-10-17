"""Verify retry preservation against real Javadoc files and an external GitHub CLI fixture."""

import hashlib
import json
import os
import subprocess
import tempfile
import unittest
import zipfile
from copy import deepcopy
from pathlib import Path
from typing import Any
from unittest.mock import patch

from scripts import javadoc_publish as publish

from tests.support import copy_scripts, executable, invoke, tool_environment

VERSION = "4.2.0"
COMMIT = "a" * 40
REPOSITORY = "example/release-test"


class JavadocPublishTests(unittest.TestCase):
    """Treat already published artifacts as a verified immutable set on every retry."""

    def setUp(self) -> None:
        """Create genuine release files, private local candidates, and a logged process boundary."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        copy_scripts(self.root)
        self.environment = tool_environment(self.root)
        self.environment.update(GH_HOST="wrong.invalid", GH_REPO="wrong/ambient")
        environment_patch = patch.dict(os.environ, self.environment)
        environment_patch.start()
        self.addCleanup(environment_patch.stop)
        executable(
            self.root / "bin/gh",
            """
import json, pathlib, shutil, sys
root = pathlib.Path.cwd()
state = json.loads((root / 'github.json').read_text())
args = sys.argv[1:]
with (root / 'commands.jsonl').open('a') as log:
    log.write(json.dumps(args) + '\\n')
if state.get('fail'):
    print('synthetic authentication or transport failure', file=sys.stderr)
    sys.exit(1)
if args[0] == 'api':
    assert args == ['api', '--hostname', 'github.com', '--paginate',
                    'repos/example/release-test/releases?per_page=100', '--jq', '.[].tag_name'], args
    print('\\n'.join(state['tags']))
elif args[:2] == ['attestation', 'verify']:
    assert args[args.index('--repo') + 1] == 'example/release-test', args
    assert args[args.index('--hostname') + 1] == 'github.com', args
    assert args[args.index('--source-ref') + 1] == 'refs/tags/v4.2.0', args
    assert args[args.index('--source-digest') + 1] == 'a' * 40, args
    assert args[args.index('--signer-digest') + 1] == 'a' * 40, args
    assert args[args.index('--signer-workflow') + 1] == 'example/release-test/.github/workflows/build.yml', args
    assert '--deny-self-hosted-runners' in args, args
    assert pathlib.Path(args[2]).is_file()
    assert pathlib.Path(args[args.index('--bundle') + 1]).is_file()
    sys.exit(state.get('attestation_exit', 0))
else:
    assert args[args.index('--repo') + 1] == 'github.com/example/release-test', args
    assert args[2] == 'v4.2.0', args
    if args[:2] == ['release', 'view']:
        print(json.dumps(state['release']))
    elif args[:2] == ['release', 'download']:
        destination = pathlib.Path(args[args.index('--dir') + 1])
        for index, argument in enumerate(args):
            if argument == '--pattern':
                name = args[index + 1]
                shutil.copy2(root / 'assets' / name, destination / name)
    else:
        raise AssertionError(args)
""",
        )
        self.names = publish.filenames(VERSION)
        self.assets = self.root / "assets"
        self.assets.mkdir()
        jar = self.assets / self.names[0]
        values = {
            "Manifest-Version": "1.0",
            "Code-Tracer-Javadoc-Schema": "1",
            "Code-Tracer-Version": VERSION,
            "Code-Tracer-Commit": COMMIT,
            "Code-Tracer-Dirty": "false",
            "Code-Tracer-Javadoc-Plugin": "3.12.0",
            "Code-Tracer-Build-Jdk": "21.0.9",
            "Code-Tracer-Build-Jdk-Vendor": "Eclipse Adoptium",
            "Code-Tracer-Build-Maven": "3.10.0",
        }
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr(
                "META-INF/MANIFEST.MF", "".join(f"{key}: {value}\r\n" for key, value in values.items()) + "\r\n"
            )
            for name in ("index.html", "search.html", "element-list"):
                archive.writestr(name, "Published API reference")
        (self.assets / self.names[1]).write_text(f"{hashlib.sha256(jar.read_bytes()).hexdigest()}  {jar.name}\n")
        (self.assets / self.names[2]).write_text('{"externalSigstoreFixture": true}')
        self.candidate = self.root / "target" / jar.name
        self.candidate.parent.mkdir()
        for name in self.names:
            (self.candidate.parent / name).write_text("new build candidate " + name)
        self.state: dict[str, Any] = {
            "tags": ["v1.0.0", "v" + VERSION],
            "release": {
                "tagName": "v" + VERSION,
                "isPrerelease": False,
                "assets": [{"name": name, "size": (self.assets / name).stat().st_size} for name in self.names],
            },
        }
        self.save()

    def save(self) -> None:
        """Write the next external GitHub response without mocking internal verification."""
        (self.root / "github.json").write_text(json.dumps(self.state))

    def process(self, *, preserve: bool = True) -> bool:
        """Invoke the same function used by the CLI with its exact release identity."""
        self.save()
        return publish.process(self.root, self.candidate, VERSION, COMMIT, REPOSITORY, preserve=preserve)

    def contents(self) -> dict[str, bytes]:
        """Capture the candidate trio to detect unexpected modifications after failures."""
        return {name: (self.candidate.parent / name).read_bytes() for name in self.names}

    def cli(self, action: str, *args: str) -> subprocess.CompletedProcess[str]:
        """Exercise a copied CLI through the real Python interpreter."""
        self.save()
        return invoke(
            self.root,
            "javadoc_publish.py",
            action,
            str(self.candidate),
            "--version",
            VERSION,
            "--commit",
            COMMIT,
            "--repository",
            REPOSITORY,
            *args,
            env=self.environment,
        )

    def test_absent_release_is_fresh_but_not_verified(self) -> None:
        """Missing releases leave new candidates intact and report false only on preflight."""
        before = self.contents()
        self.state["tags"] = ["v1.0.0"]
        output = self.root / "github-output"
        result = self.cli("preserve", "--github-output", str(output))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output.read_text(), "preserved=false\n")
        self.assertEqual(self.contents(), before)
        with self.assertRaisesRegex(ValueError, "artifacts are missing"):
            self.process(preserve=False)

    def test_existing_release_without_javadoc_is_fresh(self) -> None:
        """Unrelated release assets do not indicate a partial Javadoc publication."""
        for assets in ([], [{"name": "code-tracer.jar", "size": 123}]):
            with self.subTest(assets=assets):
                self.state["release"]["assets"] = assets
                self.assertFalse(self.process())

    def test_complete_retry_preserves_all_original_bytes(self) -> None:
        """A verified retry copies the entire frozen trio, and remains idempotent."""
        expected = {name: (self.assets / name).read_bytes() for name in self.names}
        output = self.root / "github-output"
        output.write_text("previous=value\n")
        result = self.cli("preserve", "--github-output", str(output))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output.read_text(), "previous=value\npreserved=true\n")
        self.assertEqual(self.contents(), expected)
        self.assertTrue(self.process())
        self.assertEqual(self.contents(), expected)
        commands = [json.loads(line) for line in (self.root / "commands.jsonl").read_text().splitlines()]
        downloads = [command for command in commands if command[:2] == ["release", "download"]]
        self.assertEqual(len(downloads), 2)
        for command in downloads:
            self.assertEqual([command[i + 1] for i, arg in enumerate(command) if arg == "--pattern"], list(self.names))
            self.assertFalse(Path(command[command.index("--dir") + 1]).exists())

    def test_postflight_verifies_remote_files_without_replacing_candidates(self) -> None:
        """Postflight verifies GitHub's bytes even when the local candidate is deliberately invalid."""
        before = self.contents()
        result = self.cli("verify")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("existing artifacts verified", result.stdout)
        self.assertEqual(self.contents(), before)

    def test_partial_sets_require_recovery_before_download(self) -> None:
        """No subset of published companions can be silently repaired with a new build."""
        original = deepcopy(self.state["release"]["assets"])
        before = self.contents()
        for missing in self.names:
            with self.subTest(missing=missing):
                self.state["release"]["assets"] = [asset for asset in original if asset["name"] != missing]
                with self.assertRaisesRegex(ValueError, "Partial.*reviewed recovery"):
                    self.process()
                self.assertEqual(self.contents(), before)
        self.assertNotIn('"download"', (self.root / "commands.jsonl").read_text())

    def test_remote_errors_do_not_masquerade_as_absence(self) -> None:
        """Authentication and transport errors fail before producing a false output."""
        self.state["fail"] = True
        output = self.root / "github-output"
        result = self.cli("preserve", "--github-output", str(output))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(output.exists())
        with self.assertRaises(subprocess.CalledProcessError):
            self.process()

    def test_ambiguous_tags_and_invalid_release_metadata_fail(self) -> None:
        """Stable identity and release-level structure are required before trusting asset descriptors."""
        self.state["tags"].append("v" + VERSION)
        with self.assertRaisesRegex(ValueError, "Ambiguous"):
            self.process()
        self.state["tags"].pop()
        for release in ([], {}, {"tagName": "v0.0.0"}, {"tagName": "v" + VERSION, "isPrerelease": True}):
            with self.subTest(release=release):
                self.state["release"] = release
                with self.assertRaisesRegex(ValueError, "expected stable tag"):
                    self.process()
        self.state["release"] = {"tagName": "v" + VERSION, "isPrerelease": False, "assets": {}}
        with self.assertRaisesRegex(ValueError, "asset metadata"):
            self.process()

    def test_invalid_asset_descriptors_fail(self) -> None:
        """Reject malformed sizes and duplicate names instead of accepting ambiguous downloads."""
        fixtures = [
            [None],
            [{}],
            [{"name": 123}],
            [{"name": "", "size": 1}],
            [{"name": "other", "size": True}],
            [{"name": "other", "size": "1"}],
            [{"name": "other", "size": -1}],
            [{"name": "other", "size": 1}] * 2,
        ]
        for assets in fixtures:
            with self.subTest(assets=assets):
                self.state["release"]["assets"] = assets
                with self.assertRaisesRegex(ValueError, "unique names"):
                    self.process()

    def test_zero_and_oversized_assets_fail_before_download(self) -> None:
        """Release metadata must bound every member of the trio before downloading it."""
        original = deepcopy(self.state["release"]["assets"])
        for index, limit in enumerate((publish.MAX_ARCHIVE, 256, publish.MAX_BUNDLE)):
            for size in (0, limit + 1):
                with self.subTest(index=index, size=size):
                    self.state["release"]["assets"] = deepcopy(original)
                    self.state["release"]["assets"][index]["size"] = size
                    with self.assertRaisesRegex(ValueError, "sizes exceed"):
                        self.process()

    def test_changed_download_size_and_invalid_provenance_preserve_candidates(self) -> None:
        """Descriptor changes, invalid checksums, and failed attestations never replace candidates."""
        before = self.contents()
        self.state["release"]["assets"][0]["size"] += 1
        with self.assertRaisesRegex(ValueError, "differ from published asset sizes"):
            self.process()
        self.state["release"]["assets"][0]["size"] -= 1
        checksum = self.assets / self.names[1]
        original = checksum.read_text()
        checksum.write_text("0" * 64 + original[64:])
        with self.assertRaisesRegex(ValueError, "checksum"):
            self.process()
        checksum.write_text(original)
        self.state["attestation_exit"] = 1
        with self.assertRaises(subprocess.CalledProcessError):
            self.process()
        self.assertEqual(self.contents(), before)

    def test_invalid_identity_is_rejected_before_remote_calls(self) -> None:
        """Untrusted input cannot change the remote endpoint, release, or destination filename."""
        for path, version, commit, repository in (
            (self.candidate, "4.2.0-SNAPSHOT", COMMIT, REPOSITORY),
            (self.candidate, VERSION, "short", REPOSITORY),
            (self.candidate, VERSION, COMMIT, "example/repo/extra"),
            (self.candidate.with_name("arbitrary.jar"), VERSION, COMMIT, REPOSITORY),
        ):
            with self.subTest(path=path, version=version, commit=commit, repository=repository):
                with self.assertRaises(ValueError):
                    publish.process(self.root, path, version, commit, repository, preserve=True)
        self.assertFalse((self.root / "commands.jsonl").exists())
