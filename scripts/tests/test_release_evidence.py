"""Exercise release evidence with real archives, Git paths, and external-tool stand-ins."""

import hashlib
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
        (self.root / "pom.xml").write_text(f"<project><version>{VERSION}</version></project>")
        self.commit = self.commit_pom()
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
if args[:2] == ['attestation', 'verify']:
    assert args[args.index('--repo') + 1] == 'example/release-test', args
    assert pathlib.Path(args[2]).is_file()
    assert pathlib.Path(args[args.index('--bundle') + 1]).is_file()
    sys.exit(int(state.get('attestation_exit', 0)))
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
                    "headSha": self.commit,
                    "status": "completed",
                    "conclusion": "success",
                }
            ],
            "run": {
                "event": "push",
                "headBranch": BRANCH,
                "headSha": self.commit,
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
            "versions": [{"name": VERSION, "sourceCommit": self.commit, "banner": "none"}],
        }
        self.state["tag_runs"] = [dict(self.state["runs"][0], headBranch="v" + VERSION, databaseId=9)]
        tag_run: dict[str, Any] = deepcopy(self.state["run"])
        tag_run["headBranch"] = "v" + VERSION
        tag_run["jobs"][-1]["conclusion"] = "success"
        self.state["tag_run"] = tag_run
        self.assets = self.root / "assets"
        self.assets.mkdir()
        self.metadata = f"version={VERSION}\ngit.commit={self.commit}\ngit.dirty=false\n".encode()
        for name in (self.names[0], self.names[-1]):
            self.jar(self.assets / name, self.metadata)
        self.archives((self.assets / self.names[0]).read_bytes())
        self.save()

    def commit_pom(self) -> str:
        """Commit real capability metadata for the release-evidence Git lookup."""
        subprocess.run(["git", "add", "pom.xml"], cwd=self.root, check=True)
        subprocess.run(
            [
                "git",
                "-c",
                "user.name=Evidence Test",
                "-c",
                "user.email=evidence@example.invalid",
                "-c",
                "commit.gpgSign=false",
                "-c",
                "core.hooksPath=/dev/null",
                "commit",
                "-qm",
                "Release metadata",
            ],
            cwd=self.root,
            check=True,
        )
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=self.root, text=True).strip()

    def javadoc(self, *, required: bool = False) -> list[str]:
        """Create a real classified JAR and companions, optionally declaring the capability."""
        if required:
            (self.root / "pom.xml").write_text(
                f"<project><version>{VERSION}</version><properties>"
                "<release.javadoc.schema>1</release.javadoc.schema></properties></project>"
            )
            self.commit = self.commit_pom()
            for name in ("runs", "tag_runs"):
                self.state[name][0]["headSha"] = self.commit
            for name in ("run", "tag_run"):
                self.state[name]["headSha"] = self.commit
            self.manifest["versions"][0]["sourceCommit"] = self.commit
            self.metadata = f"version={VERSION}\ngit.commit={self.commit}\ngit.dirty=false\n".encode()
            for name in (self.names[0], self.names[-1]):
                self.jar(self.assets / name, self.metadata)
            self.archives((self.assets / self.names[0]).read_bytes())
        name = f"cs1302-tracer-{VERSION}-javadoc.jar"
        path = self.assets / name
        values = {
            "Manifest-Version": "1.0",
            "Code-Tracer-Javadoc-Schema": "1",
            "Code-Tracer-Version": VERSION,
            "Code-Tracer-Commit": self.commit,
            "Code-Tracer-Dirty": "false",
            "Code-Tracer-Javadoc-Plugin": "3.12.0",
            "Code-Tracer-Build-Jdk": "21.0.9",
            "Code-Tracer-Build-Jdk-Vendor": "Eclipse Adoptium",
            "Code-Tracer-Build-Maven": "4.0.0-rc-5",
        }
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(
                "META-INF/MANIFEST.MF", "".join(f"{key}: {value}\r\n" for key, value in values.items()) + "\r\n"
            )
            for member in ("index.html", "search.html", "element-list"):
                archive.writestr(member, "Generated API reference")
        names = [name, name + ".sha256", name + ".sigstore.json"]
        (self.assets / names[1]).write_text(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {name}\n")
        (self.assets / names[2]).write_text('{"externalSigstoreFixture": true}')
        self.state["release"]["assets"].extend(
            {"name": item, "size": (self.assets / item).stat().st_size} for item in names
        )
        return names

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
            return evidence.publication_verified(self.root, VERSION, self.commit)

    def ci(self) -> bool:
        """Verify the current observable run and jobs."""
        self.save()
        return evidence.ci_passed(self.root, BRANCH, self.commit)

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
        self.assertIn(self.commit, commands[1])

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
                "headSha": self.commit,
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

    def test_aggregate_does_not_replace_individual_job_evidence(self) -> None:
        """Require the aggregate and all eight original validation jobs independently."""
        original = deepcopy(self.state["run"]["jobs"])
        names = {
            "Documentation",
            "Regression gates",
            "Scripts quality",
            "Java (ubuntu-latest, 21)",
            "Java (ubuntu-latest, 25)",
            "Java (macos-latest, 21)",
            "Java (macos-latest, 25)",
            "Windows packaging and launchers (Java 21)",
            "Windows packaging and launchers (Java 25)",
        }
        for name in sorted(names):
            with self.subTest(missing=name):
                self.state["run"]["jobs"] = [job for job in original if job["name"] != name]
                with self.assertRaisesRegex(ValueError, "missing or duplicates"):
                    self.ci()

    def test_regression_gate_must_pass_for_branch_and_tag_evidence(self) -> None:
        """Reject missing, duplicated, pending, failed, cancelled, or skipped aggregates."""
        for key, verify in (("run", self.ci), ("tag_run", self.published)):
            original = deepcopy(self.state[key]["jobs"])
            gate = next(job for job in original if job["name"] == "Regression gates")
            for jobs in (
                [job for job in original if job["name"] != "Regression gates"],
                [*original, gate],
            ):
                with self.subTest(run=key, jobs=jobs):
                    self.state[key]["jobs"] = jobs
                    with self.assertRaisesRegex(ValueError, "missing or duplicates"):
                        verify()
            self.state[key]["jobs"] = deepcopy(original)
            gate = next(job for job in self.state[key]["jobs"] if job["name"] == "Regression gates")
            for status, conclusion in (
                ("in_progress", ""),
                ("completed", "failure"),
                ("completed", "cancelled"),
                ("completed", "skipped"),
            ):
                with self.subTest(run=key, status=status, conclusion=conclusion):
                    gate.update(status=status, conclusion=conclusion)
                    with self.assertRaisesRegex(ValueError, "Every required CI job"):
                        verify()
            self.state[key]["jobs"] = original

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
        for version, commit in (("../../evil", self.commit), ("01.0.0", self.commit), (VERSION, "short")):
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

    def test_release_asset_records_must_be_unambiguous(self) -> None:
        """Reject malformed and duplicate asset metadata before downloading any files."""
        original = deepcopy(self.state["release"]["assets"])
        invalid = [
            None,
            [None],
            [{"name": None, "size": 1}],
            [{"name": "", "size": 1}],
            [{"name": "file", "size": "1"}],
            [{"name": "file", "size": True}],
            [{"name": "file", "size": -1}],
            [*original, original[0]],
        ]
        for records in invalid:
            with self.subTest(records=records), self.assertRaisesRegex(ValueError, "Release assets must"):
                self.state["release"]["assets"] = records
                self.published()
        commands = [json.loads(line) for line in (self.root / "commands.jsonl").read_text().splitlines()]
        self.assertFalse(any(command[:2] == ["release", "download"] for command in commands))

    def test_required_javadoc_checks_exact_publisher_before_completing(self) -> None:
        """Download all new assets and enforce exact GitHub certificate policy on the actual JAR."""
        names = self.javadoc(required=True)
        self.assertTrue(self.published())
        commands = [json.loads(line) for line in (self.root / "commands.jsonl").read_text().splitlines()]
        downloaded = next(command for command in commands if command[:2] == ["release", "download"])
        for name in names:
            self.assertIn(name, downloaded)
        verification = next(command for command in commands if command[:2] == ["attestation", "verify"])
        destination = self.root / ".git/release-assets" / VERSION
        self.assertEqual(
            verification,
            [
                "attestation",
                "verify",
                str(destination / names[0]),
                "--bundle",
                str(destination / names[2]),
                "--hostname",
                "github.com",
                "--repo",
                "example/release-test",
                "--signer-workflow",
                "example/release-test/.github/workflows/build.yml",
                "--signer-digest",
                self.commit,
                "--source-digest",
                self.commit,
                "--source-ref",
                "refs/tags/v" + VERSION,
                "--deny-self-hosted-runners",
                "--predicate-type",
                "https://slsa.dev/provenance/v1",
            ],
        )
        self.state["release"]["assets"] = [
            asset for asset in self.state["release"]["assets"] if asset["name"] not in names
        ]
        self.assertFalse(self.published(), "Declared artifact support must not silently become a legacy release")

    def test_advertised_legacy_javadoc_requires_every_companion(self) -> None:
        """An optional legacy artifact becomes required evidence when any companion is advertised."""
        names = self.javadoc()
        complete = deepcopy(self.state["release"]["assets"])
        for only in names:
            self.state["release"]["assets"] = [
                asset for asset in complete if asset["name"] not in names or asset["name"] == only
            ]
            self.assertFalse(self.published())
        self.state["release"]["assets"] = complete
        self.assertTrue(self.published())

    def test_invalid_javadoc_checksum_or_attestation_cannot_complete(self) -> None:
        """Keep integrity and external trust failures visible instead of falling back to old assets."""
        names = self.javadoc()
        checksum = self.assets / names[1]
        original = checksum.read_text()
        checksum.write_text("wrong checksum\n")
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.published()
        checksum.write_text(original)
        self.state["attestation_exit"] = 1
        with self.assertRaises(subprocess.CalledProcessError):
            self.published()

    def test_inconsistent_and_dirty_jar_metadata_is_error(self) -> None:
        """Wrong versions, commits, and dirty flags fail after actual asset download."""
        self.jar(self.assets / self.names[-1], b"different")
        with self.assertRaisesRegex(ValueError, "different build metadata"):
            self.published()
        for old, new in ((VERSION, "1.0.0"), (self.commit, "2" * 40), ("false", "true")):
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
        entry = {"name": VERSION, "sourceCommit": self.commit, "banner": "none"}
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
                    self.assertFalse(evidence.publication_verified(self.root, VERSION, self.commit))
                else:
                    with self.assertRaises(urllib.error.HTTPError):
                        evidence.publication_verified(self.root, VERSION, self.commit)
        with patch("urllib.request.urlopen", side_effect=urllib.error.URLError("offline")):
            with self.assertRaises(urllib.error.URLError):
                evidence.publication_verified(self.root, VERSION, self.commit)
