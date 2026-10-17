"""Exercise release evidence with real archives, Git paths, and external-tool stand-ins."""

import io
import json
import os
import subprocess
import tarfile
import tempfile
import unittest
import urllib.error
import warnings
import zipfile
from copy import deepcopy
from email.message import Message
from pathlib import Path
from typing import Any
from unittest.mock import patch

from scripts import release_evidence as evidence

from tests.support import executable, tool_environment

VERSION = "2.0.0"
COMMIT = "1" * 40
BRANCH = "release/2.0.0"


class ReleaseEvidenceTests(unittest.TestCase):
    """Prevent pending, stale, skipped, or mismatched evidence from qualifying a release."""

    def setUp(self) -> None:
        """Create a synthetic repository and an observable GitHub CLI boundary."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(
            ["git", "remote", "add", "origin", "git@github.com:example/release-test.git"], cwd=self.root, check=True
        )
        environment = tool_environment(self.root)
        environment_patch = patch.dict(os.environ, environment)
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
if args[:2] == ['repo', 'view']:
    assert args[2] == 'git@github.com:example/release-test.git', args
    print(json.dumps(state['repository']))
    sys.exit(0)
assert args[args.index('--repo') + 1] == 'github.com/example/release-test', args
if args[:2] == ['run', 'list']:
    print(json.dumps(state['tag_runs'] if args[args.index('--branch') + 1].startswith('v') else state['runs']))
elif args[:2] == ['run', 'view']:
    print(json.dumps(state['tag_run'] if args[2] == '9' else state['run']))
elif args[:2] == ['release', 'list']:
    print(json.dumps(state['releases']))
elif args[:2] == ['release', 'view']:
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
        self.names = [
            "code-tracer.jar",
            "code-tracer-bin.tar.gz",
            "code-tracer-bin.tar.xz",
            "code-tracer-bin.zip",
            f"cs1302-tracer-{VERSION}.jar",
        ]
        self.state: dict[str, Any] = {
            "repository": {"nameWithOwner": "example/release-test", "url": "https://github.com/example/release-test"},
            "runs": [
                {
                    "databaseId": 8,
                    "attempt": 1,
                    "event": "push",
                    "headBranch": BRANCH,
                    "headSha": COMMIT,
                    "status": "completed",
                    "conclusion": "success",
                }
            ],
            "run": {
                "event": "push",
                "headBranch": BRANCH,
                "headSha": COMMIT,
                "status": "completed",
                "conclusion": "success",
                "jobs": [
                    {"name": name, "status": "completed", "conclusion": "success"}
                    for name in sorted(evidence.EXPECTED_JOBS)
                ]
                + [{"name": "release", "status": "completed", "conclusion": "skipped"}],
            },
            "releases": [{"tagName": "v" + VERSION}],
            "release": {
                "tagName": "v" + VERSION,
                "isDraft": False,
                "isPrerelease": False,
                "publishedAt": "2026-01-01T00:00:00Z",
                "assets": [{"name": name, "size": 100} for name in self.names],
            },
        }
        self.manifest: dict[str, Any] = {
            "mode": "published",
            "versions": [{"name": VERSION, "sourceCommit": COMMIT, "banner": "none"}],
        }
        self.state["tag_runs"] = [dict(self.state["runs"][0], headBranch="v" + VERSION, databaseId=9)]
        tag_run: dict[str, Any] = deepcopy(self.state["run"])
        tag_run["headBranch"] = "v" + VERSION
        tag_run["jobs"][-1]["conclusion"] = "success"
        self.state["tag_run"] = tag_run
        self.assets = self.root / "assets"
        self.assets.mkdir()
        self.metadata = f"version={VERSION}\ngit.commit={COMMIT}\ngit.dirty=false\n".encode()
        for name in (self.names[0], self.names[-1]):
            self.jar(self.assets / name, self.metadata)
        self.archives((self.assets / self.names[0]).read_bytes())
        self.save()

    def save(self) -> None:
        """Publish the next GitHub response to the external-process fixture."""
        (self.root / "github.json").write_text(json.dumps(self.state))

    def jar(self, path: Path, metadata: bytes) -> None:
        """Create a real JAR carrying synthetic build identity."""
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(evidence.PROPERTIES_MEMBER, metadata)

    def archives(self, content: bytes) -> None:
        """Package executable bytes in every release distribution format."""
        with zipfile.ZipFile(self.assets / self.names[3], "w") as archive:
            archive.writestr(evidence.JAR_MEMBER, content)
        for name, mode in ((self.names[1], "w:gz"), (self.names[2], "w:xz")):
            with tarfile.open(self.assets / name, mode) as archive:
                member = tarfile.TarInfo(evidence.JAR_MEMBER)
                member.size = len(content)
                archive.addfile(member, io.BytesIO(content))

    def published(self) -> bool:
        """Verify actual downloaded files against a controlled HTTP response."""
        self.save()
        with patch("urllib.request.urlopen", return_value=io.BytesIO(json.dumps(self.manifest).encode())):
            return evidence.publication_verified(self.root, VERSION, COMMIT)

    def ci(self) -> bool:
        """Verify the current observable run and jobs."""
        self.save()
        return evidence.ci_passed(self.root, BRANCH, COMMIT)

    def test_exact_ci_jobs_and_latest_run(self) -> None:
        """Accept all required jobs while ignoring the intentionally skipped publisher."""
        self.state["runs"].extend(
            [
                dict(self.state["runs"][0], databaseId=3),
                dict(self.state["runs"][0], databaseId=8, attempt=2),
                dict(self.state["runs"][0], databaseId=99, headSha="2" * 40),
                dict(self.state["runs"][0], databaseId=99, headBranch="main"),
                dict(self.state["runs"][0], databaseId=99, event="pull_request"),
            ]
        )
        self.assertTrue(self.ci())
        commands = [json.loads(line) for line in (self.root / "commands.jsonl").read_text().splitlines()]
        self.assertEqual(commands[-1][:3], ["run", "view", "8"])
        self.assertIn("build.yml", commands[1])
        self.assertIn(COMMIT, commands[1])

    def test_repository_identity_ignores_ambient_defaults(self) -> None:
        """Bind every API request to origin even when a different repository is selected globally."""
        with patch.dict(os.environ, {"GH_REPO": "other/project", "GH_HOST": "other.example"}):
            self.assertTrue(self.ci())
            self.assertTrue(self.published())
        commands = [json.loads(line) for line in (self.root / "commands.jsonl").read_text().splitlines()]
        for command in commands:
            if command[:2] == ["repo", "view"]:
                self.assertEqual(command[2], "git@github.com:example/release-test.git")
            else:
                self.assertEqual(command[command.index("--repo") + 1], "github.com/example/release-test")
        for identity in (
            {"nameWithOwner": "invalid", "url": "https://github.com/invalid"},
            {"nameWithOwner": "example/release-test", "url": "https://other.example/example/release-test"},
        ):
            self.state["repository"] = identity
            self.save()
            with self.assertRaisesRegex(ValueError, "explicit github.com"):
                evidence.repository(self.root)

    def test_pending_runs_and_reruns(self) -> None:
        """Missing push evidence and an in-progress latest rerun remain pending."""
        self.state["runs"] = []
        self.assertFalse(self.ci())
        self.state["runs"] = [
            {
                "databaseId": 1,
                "attempt": 1,
                "event": "push",
                "headBranch": BRANCH,
                "headSha": COMMIT,
            }
        ]
        self.state["run"]["status"] = "in_progress"
        self.assertFalse(self.ci())

    def test_ci_identity_and_failure_rejected(self) -> None:
        """Refuse wrong revisions and failed, missing, duplicate, or skipped required jobs."""
        for key, value in (("event", "pull_request"), ("headBranch", "main"), ("headSha", "2" * 40)):
            with self.subTest(key=key):
                original = self.state["run"][key]
                self.state["run"][key] = value
                with self.assertRaisesRegex(ValueError, "exact release"):
                    self.ci()
                self.state["run"][key] = original
        self.state["run"]["conclusion"] = "failure"
        with self.assertRaisesRegex(ValueError, "did not pass"):
            self.ci()
        self.state["run"]["conclusion"] = "success"
        original_jobs = list(self.state["run"]["jobs"])
        for jobs in (original_jobs[1:], original_jobs[1:] + [original_jobs[1]], original_jobs + [original_jobs[0]]):
            self.state["run"]["jobs"] = jobs
            with self.assertRaisesRegex(ValueError, "missing or duplicates"):
                self.ci()
        self.state["run"]["jobs"] = original_jobs
        for status, conclusion in (("in_progress", ""), ("completed", "skipped")):
            self.state["run"]["jobs"][0].update(status=status, conclusion=conclusion)
            with self.assertRaisesRegex(ValueError, "Every required CI job"):
                self.ci()

    def test_github_failure_is_not_pending_or_success(self) -> None:
        """Authentication and transport errors propagate from the actual tool process."""
        self.state["fail"] = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.ci()

    def test_publication_downloads_and_verifies_all_five_assets(self) -> None:
        """Require consistent embedded identity and byte-identical packaged executables."""
        self.assertTrue(self.published())
        destination = self.root / ".git/release-assets" / VERSION
        self.assertEqual({path.name for path in destination.iterdir()}, set(self.names))
        commands = [json.loads(line) for line in (self.root / "commands.jsonl").read_text().splitlines()]
        self.assertIn("--clobber", commands[-1])
        for name in self.names:
            self.assertIn(name, commands[-1])

    def test_publication_requires_exact_successful_tag_workflow_and_publisher(self) -> None:
        """Existing assets cannot replace successful tag-triggered CI and publication."""
        original = self.state["tag_runs"]
        self.state["tag_runs"] = []
        self.assertFalse(self.published())
        self.state["tag_runs"] = [dict(original[0], headSha="2" * 40)]
        self.assertFalse(self.published())
        self.state["tag_runs"] = original
        self.state["tag_run"]["status"] = "in_progress"
        self.assertFalse(self.published())
        self.state["tag_run"]["status"] = "completed"
        self.state["tag_run"]["conclusion"] = "failure"
        with self.assertRaisesRegex(ValueError, "did not pass"):
            self.published()
        self.state["tag_run"]["conclusion"] = "success"
        self.state["tag_run"]["jobs"][-1]["conclusion"] = "skipped"
        with self.assertRaisesRegex(ValueError, "Every required CI job"):
            self.published()
        self.state["tag_run"]["jobs"].pop()
        with self.assertRaisesRegex(ValueError, "missing or duplicates"):
            self.published()
        commands = [json.loads(line) for line in (self.root / "commands.jsonl").read_text().splitlines()]
        self.assertFalse(any(command[0] == "release" for command in commands))

    def test_invalid_publication_identity_rejected_before_io(self) -> None:
        """Do not use unvalidated version paths or abbreviated revisions."""
        for version, commit in (("../../evil", COMMIT), ("01.0.0", COMMIT), (VERSION, "short")):
            with self.subTest(version=version, commit=commit), self.assertRaises(ValueError):
                evidence.publication_verified(self.root, version, commit)
        self.assertFalse((self.root / "commands.jsonl").exists())

    def test_publication_incomplete_metadata_is_pending(self) -> None:
        """Absent releases, drafts, dates, or nonzero required assets cannot complete publication."""
        self.state["releases"] = [{"tagName": "v1.0.0"}]
        self.assertFalse(self.published())
        self.state["releases"] = [{"tagName": "v" + VERSION}]
        for field, value in (("isDraft", True), ("publishedAt", "")):
            original = self.state["release"][field]
            self.state["release"][field] = value
            self.assertFalse(self.published())
            self.state["release"][field] = original
        self.state["release"]["assets"].pop()
        self.assertFalse(self.published())
        self.state["release"]["assets"].append({"name": self.names[-1], "size": 0})
        self.assertFalse(self.published())

    def test_mismatched_release_classification_is_error(self) -> None:
        """A wrong tag or prerelease must not be mistaken for an unfinished stable release."""
        for field, value in (("tagName", "v1.0.0"), ("isPrerelease", True)):
            original = self.state["release"][field]
            self.state["release"][field] = value
            with self.assertRaisesRegex(ValueError, "classification"):
                self.published()
            self.state["release"][field] = original

    def test_inconsistent_and_dirty_jar_metadata_is_error(self) -> None:
        """Wrong versions, commits, and dirty flags fail after actual asset download."""
        self.jar(self.assets / self.names[-1], b"different")
        with self.assertRaisesRegex(ValueError, "different build metadata"):
            self.published()
        for old, new in ((VERSION, "1.0.0"), (COMMIT, "2" * 40), ("false", "true")):
            metadata = self.metadata.replace(old.encode(), new.encode())
            for name in (self.names[0], self.names[-1]):
                self.jar(self.assets / name, metadata)
            with self.assertRaisesRegex(ValueError, "expected clean tagged revision"):
                self.published()

    def test_archive_jar_mismatch_is_error(self) -> None:
        """A distribution cannot silently ship bytes from a different build."""
        self.archives(b"different executable")
        with self.assertRaisesRegex(ValueError, "different executable JAR"):
            self.published()

    def test_missing_and_duplicate_metadata_or_members(self) -> None:
        """Reject ambiguous metadata, missing members, and links in place of a JAR."""
        jar_path = self.assets / self.names[0]
        zip_path = self.assets / self.names[3]
        for count in (0, 2):
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(jar_path, "w") as archive:
                    for _ in range(count):
                        archive.writestr(evidence.PROPERTIES_MEMBER, self.metadata)
                with self.assertRaisesRegex(ValueError, "build metadata"):
                    evidence.jar_metadata(jar_path)
                with zipfile.ZipFile(zip_path, "w") as archive:
                    for _ in range(count):
                        archive.writestr(evidence.JAR_MEMBER, b"jar")
                with self.assertRaisesRegex(ValueError, "executable JAR"):
                    evidence.archived_jar(zip_path)
        tar_path = self.assets / self.names[1]
        for count, kind in ((0, tarfile.REGTYPE), (2, tarfile.REGTYPE), (1, tarfile.SYMTYPE)):
            with tarfile.open(tar_path, "w:gz") as archive:
                for _ in range(count):
                    member = tarfile.TarInfo(evidence.JAR_MEMBER)
                    member.type = kind
                    member.linkname = "elsewhere"
                    archive.addfile(member)
            with self.assertRaisesRegex(ValueError, "executable JAR"):
                evidence.archived_jar(tar_path)

    def test_corrupt_jar_and_archives_report_validation_errors(self) -> None:
        """Malformed downloaded files report clear failures rather than qualifying publication."""
        path = self.assets / self.names[0]
        path.write_bytes(b"broken JAR")
        with self.assertRaisesRegex(ValueError, "Invalid release JAR"):
            evidence.jar_metadata(path)
        for name in self.names[1:4]:
            path = self.assets / name
            path.write_bytes(b"broken archive")
            with self.assertRaisesRegex(ValueError, "Invalid release archive"):
                evidence.archived_jar(path)

    def test_pending_and_invalid_live_documentation(self) -> None:
        """Require published source provenance instead of trusting an earlier deployment."""
        self.manifest["versions"] = [{"name": "1.0.0"}]
        self.assertFalse(self.published())
        self.manifest["mode"] = "preview"
        with self.assertRaisesRegex(ValueError, "not a published build"):
            self.published()
        self.manifest["mode"] = "published"
        entry = {"name": VERSION, "sourceCommit": COMMIT, "banner": "none"}
        for entries in ([entry, entry], [dict(entry, sourceCommit="2" * 40)], [dict(entry, banner="unreleased")]):
            self.manifest["versions"] = entries
            with self.assertRaisesRegex(ValueError, "expected tagged revision"):
                self.published()

    def test_http_absence_is_pending_but_other_failures_propagate(self) -> None:
        """Only a missing manifest is pending; network and authentication errors remain errors."""
        for status in (404, 403, 500):
            error = urllib.error.HTTPError(evidence.MANIFEST_URL, status, "test error", Message(), None)
            with patch("urllib.request.urlopen", side_effect=error):
                if status == 404:
                    self.assertFalse(evidence.publication_verified(self.root, VERSION, COMMIT))
                else:
                    with self.assertRaises(urllib.error.HTTPError):
                        evidence.publication_verified(self.root, VERSION, COMMIT)
        with patch("urllib.request.urlopen", side_effect=urllib.error.URLError("offline")):
            with self.assertRaises(urllib.error.URLError):
                evidence.publication_verified(self.root, VERSION, COMMIT)
