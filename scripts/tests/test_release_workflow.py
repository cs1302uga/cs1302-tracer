"""Exercise release preparation and publication against signed disposable Git history."""

import contextlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import release
from tests.support import SCRIPTS, copy_scripts, executable

MAVEN = r"""
import json, os, re, subprocess, sys
from pathlib import Path
root = Path.cwd()
args = sys.argv[1:]
with open('.git/maven-calls.jsonl', 'a') as stream:
    stream.write(json.dumps(args) + '\n')
state_files = ('pom.xml.tag', 'pom.xml.next', 'pom.xml.releaseBackup', 'release.properties')
if 'release:clean' in args:
    for name in state_files:
        path = root / name
        if path.exists():
            path.unlink()
    sys.exit(0)
for name in state_files:
    root.joinpath(name).write_text('Maven release state')
dry = '-DdryRun=true' in args
if os.environ.get('RELEASE_TEST_MAVEN_FAIL') == ('dry' if dry else 'prepare'):
    sys.exit(23)
if dry:
    assert '-Drelease.preparationGoals=validate' in args
    assert '-Drelease.preparationProfiles=' in args
    sys.exit(0)
assert not any(arg.startswith('-Drelease.preparation') for arg in args)
values = dict(arg[2:].split('=', 1) for arg in args if arg.startswith('-D') and '=' in arg)
assert values['pushChanges'] == 'false' and values['signTag'] == 'true'
pom = root / 'pom.xml'
def git(*args):
    subprocess.run(['git', *args], check=True)
def update(version):
    pom.write_text(re.sub(r'<version>[^<]+</version>', '<version>' + version + '</version>', pom.read_text(), count=1))
    git('add', 'pom.xml')
    git('commit', '-qm', '[maven-release-plugin] ' + version)
update(values['releaseVersion'])
git('tag', '-s', '-m', 'Release ' + values['releaseVersion'], values['tag'])
update(values['developmentVersion'])
"""

GITHUB = r"""
import io, json, os, subprocess, sys, tarfile, zipfile
from pathlib import Path
args = sys.argv[1:]
root = Path.cwd()
with open('.git/gh-calls.jsonl', 'a') as stream:
    stream.write(json.dumps(args) + '\n')
def output(value):
    print(json.dumps(value))
if args[0] == 'api':
    records = '[[{"tag_name":"v1.0.0","draft":false,"prerelease":false}]]'
    output(json.loads(os.environ.get('RELEASE_TEST_RECORDS', records)))
elif args[:2] == ['repo', 'view']:
    output({'nameWithOwner': 'test/release', 'url': 'https://github.com/test/release'})
elif args[:2] == ['run', 'list']:
    commit = args[args.index('--commit') + 1]
    branch = args[args.index('--branch') + 1]
    ready = os.environ.get('RELEASE_TEST_CI', '').split(',')
    record = dict(databaseId=42, attempt=1, event='push', headBranch=branch, headSha=commit,
                  status='completed', conclusion='success')
    root.joinpath('.git/last-ci.json').write_text(json.dumps(record))
    output([record] if 'all' in ready or commit in ready else [])
elif args[:2] == ['run', 'view']:
    record = json.loads(root.joinpath('.git/last-ci.json').read_text())
    names = ['Documentation', 'Scripts quality', 'release']
    names += [f'Java ({platform}, {version})' for platform in ('ubuntu-latest', 'macos-latest') for version in (21, 25)]
    names += [f'Windows packaging and launchers (Java {version})' for version in (21, 25)]
    record['jobs'] = [dict(name=name, status='completed', conclusion='success') for name in names]
    output(record)
elif args[:2] == ['release', 'list']:
    output([{'tagName': 'v2.0.0'}] if os.environ.get('RELEASE_TEST_PUBLICATION') else [])
elif args[:2] == ['release', 'view']:
    names = ['code-tracer.jar', 'code-tracer-bin.tar.gz', 'code-tracer-bin.tar.xz', 'code-tracer-bin.zip',
             'cs1302-tracer-2.0.0.jar']
    output(dict(tagName='v2.0.0', isDraft=False, isPrerelease=False, publishedAt='2026-10-17T00:00:00Z',
                assets=[dict(name=name, size=100) for name in names]))
elif args[:2] == ['release', 'download']:
    destination = Path(args[args.index('--dir') + 1])
    state = json.loads(root.joinpath('.git/code-tracer-release.json').read_text())
    content = io.BytesIO()
    with zipfile.ZipFile(content, 'w') as archive:
        archive.writestr('cs1302/tracer/version.properties',
                         'version=2.0.0\ngit.commit=' + state['release_commit'] + '\ngit.dirty=false\n')
    jar = content.getvalue()
    for name in ('code-tracer.jar', 'cs1302-tracer-2.0.0.jar'):
        destination.joinpath(name).write_bytes(jar)
    with zipfile.ZipFile(destination / 'code-tracer-bin.zip', 'w') as archive:
        archive.writestr('code-tracer/code-tracer.jar', jar)
    for suffix, mode in (('tar.gz', 'w:gz'), ('tar.xz', 'w:xz')):
        with tarfile.open(destination / ('code-tracer-bin.' + suffix), mode) as archive:
            member = tarfile.TarInfo('code-tracer/code-tracer.jar')
            member.size = len(jar)
            archive.addfile(member, io.BytesIO(jar))
else:
    raise AssertionError(args)
"""


class ReleaseWorkflowTests(unittest.TestCase):
    """Use real signatures and refs while substituting Maven and GitHub transport."""

    def setUp(self) -> None:
        """Build a signed history, local origin, matching pending docs, and fake external tools."""
        temporary = tempfile.TemporaryDirectory(prefix="release workflow ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.source = self.root / "source"
        self.source.mkdir()
        self.directory = self.root / "release checkout"
        self.origin = self.root / "origin.git"
        binaries = self.root / "tools"
        binaries.mkdir()
        executable(binaries / "node", 'print("{}")\n')
        executable(binaries / "gh", GITHUB)
        environment = mock.patch.dict(
            os.environ,
            PATH=str(binaries) + os.pathsep + os.environ["PATH"],
            GIT_CONFIG_NOSYSTEM="1",
            GIT_CONFIG_GLOBAL=os.devnull,
        )
        environment.start()
        self.addCleanup(environment.stop)
        self.key = self.root / "release-key"
        subprocess.run(
            ["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(self.key)],
            check=True,
            capture_output=True,
        )
        self.trusted = self.root / "allowed-signers"
        self.trusted.write_text("release@example.invalid " + self.key.with_suffix(".pub").read_text())
        self.git(self.source, "init", "-q", "--initial-branch=main")
        self.git(self.source, "init", "-q", "--bare", "--initial-branch=main", str(self.origin))
        for name, value in {
            "user.name": "Release Test",
            "user.email": "release@example.invalid",
            "user.signingKey": str(self.key),
            "gpg.format": "ssh",
            "gpg.ssh.allowedSignersFile": str(self.trusted),
            "commit.gpgSign": "true",
            "tag.gpgSign": "true",
        }.items():
            self.git(self.source, "config", name, value)
        copy_scripts(self.source)
        executable(self.source / "mvnw", MAVEN)
        hooks = self.source / ".githooks"
        hooks.mkdir()
        (hooks / "pre-commit").write_text("#!/bin/sh\nexit 0\n")
        (hooks / "pre-commit").chmod(0o755)
        (self.source / ".gitignore").write_text(
            "pom.xml.tag\npom.xml.next\npom.xml.releaseBackup\nrelease.properties\n"
        )
        self.pom("1.0.0")
        self.docs("1.0.0", ["1.0.0"])
        self.commit(self.source, "Published release")
        self.git(self.source, "tag", "-s", "-m", "Published release", "v1.0.0")
        self.pom("2.0.0-SNAPSHOT")
        self.docs("2.0.0", ["2.0.0", "1.0.0"])
        self.commit(self.source, "Prepare release documentation")
        self.git(self.source, "remote", "add", "origin", str(self.origin))
        self.git(self.source, "push", "-q", "-u", "origin", "main", "refs/tags/v1.0.0")
        self.base = self.git(self.source, "rev-parse", "HEAD")

    def git(self, root: Path, *arguments: str) -> str:
        """Change only disposable test Git state with diagnostics captured."""
        return subprocess.run(["git", *arguments], cwd=root, check=True, capture_output=True, text=True).stdout.strip()

    def pom(self, version: str, root: Path | None = None) -> None:
        """Write a minimal project with a concrete version."""
        (root or self.source).joinpath("pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
            f"<groupId>test</groupId><artifactId>release</artifactId><version>{version}</version>"
            "<scm><tag>HEAD</tag></scm></project>\n"
        )

    def docs(self, version: str, versions: list[str]) -> None:
        """Maintain real snapshot and sidebar files for release checks."""
        docs = self.source / "docs"
        docs.mkdir(exist_ok=True)
        (docs / "index.md").write_text("# Current documentation\n")
        site = self.source / "website"
        snapshot = site / f"versioned_docs/version-{version}"
        snapshot.mkdir(parents=True)
        shutil.copy2(docs / "index.md", snapshot / "index.md")
        sidebars = site / "versioned_sidebars"
        sidebars.mkdir(exist_ok=True)
        (sidebars / f"version-{version}-sidebars.json").write_text("{}")
        (site / "sidebars.js").write_text("module.exports = {};\n")
        (site / "versions.json").write_text(json.dumps(versions))

    def commit(self, root: Path, message: str) -> str:
        """Commit all intended fixture changes using the configured SSH signer."""
        self.git(root, "add", ".")
        self.git(root, "commit", "-qm", message)
        return self.git(root, "rev-parse", "HEAD")

    def prepare(self) -> release.Release:
        """Prepare using the public orchestration interface and real Git operations."""
        with contextlib.redirect_stdout(io.StringIO()):
            return release.prepare(self.source, self.directory, "2.0.0", "2.0.1-SNAPSHOT", "major")

    def cli(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        """Run the actual command-line entry point with explicit checkout paths."""
        return subprocess.run(
            [sys.executable, str(SCRIPTS / "release.py"), *arguments],
            cwd=self.root,
            capture_output=True,
            text=True,
            timeout=60,
        )

    def test_prepare_cli_preserves_signed_history_and_remote_refs(self) -> None:
        """Preparation rehearses, cleans temporary state, signs both transitions, and does not push."""
        result = self.cli(
            "prepare",
            "--source",
            str(self.source),
            "--directory",
            str(self.directory),
            "--release-version",
            "2.0.0",
            "--development-version",
            "2.0.1-SNAPSHOT",
            "--change-kind",
            "major",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        state = release.Release.load(self.directory)
        self.assertEqual(state.phase, "prepared")
        self.assertEqual(state.previous, "1.0.0")
        state.verify(self.directory)
        self.assertEqual(release.remote_ref(self.directory, "refs/heads/main"), self.base)
        self.assertEqual(release.remote_ref(self.directory, "refs/heads/release/2.0.0"), "")
        self.assertEqual(release.remote_ref(self.directory, "refs/tags/v2.0.0"), "")
        calls = [json.loads(line) for line in (self.directory / ".git/maven-calls.jsonl").read_text().splitlines()]
        self.assertIn("-DdryRun=true", calls[0])
        self.assertIn("release:clean", calls[1])
        self.assertNotIn("-DdryRun=true", calls[2])
        self.assertEqual(len(list((self.directory / ".git/release-logs").iterdir())), 2)
        status = self.cli("status", "--directory", str(self.directory))
        self.assertEqual(status.returncode, 0, status.stderr)
        self.assertEqual(json.loads(status.stdout)["tag_object"], state.tag_object)

    def test_publish_advances_each_verified_revision_and_is_retryable(self) -> None:
        """Require stable and development CI separately, then main ancestry, publication, and docs."""
        state = self.prepare()
        result = self.cli("publish", "--directory", str(self.directory))
        self.assertEqual(result.returncode, 3, result.stderr)
        self.assertIn("stable release commit", result.stdout)
        self.assertEqual(release.remote_ref(self.directory, "refs/heads/release/2.0.0"), state.release_commit)
        with mock.patch.dict(os.environ, RELEASE_TEST_CI=state.release_commit):
            self.assertFalse(release.publish(self.directory))
        self.assertEqual(release.remote_ref(self.directory, "refs/heads/release/2.0.0"), state.development_commit)
        self.assertEqual(release.remote_ref(self.directory, "refs/heads/main"), state.base)
        self.assertEqual(release.remote_ref(self.directory, "refs/tags/v2.0.0"), "")
        with mock.patch.dict(os.environ, RELEASE_TEST_CI="all"):
            self.assertFalse(release.publish(self.directory))
        self.assertEqual(release.remote_ref(self.directory, "refs/heads/main"), state.development_commit)
        self.assertEqual(release.remote_ref(self.directory, "refs/tags/v2.0.0"), state.tag_object)
        manifest = {
            "mode": "published",
            "versions": [{"name": "2.0.0", "sourceCommit": state.release_commit, "banner": "none"}],
        }
        with (
            mock.patch.dict(os.environ, RELEASE_TEST_CI="all", RELEASE_TEST_PUBLICATION="yes"),
            mock.patch("urllib.request.urlopen", side_effect=lambda *args, **kwargs: io.StringIO(json.dumps(manifest))),
        ):
            self.assertTrue(release.publish(self.directory))
            self.git(self.source, "fetch", "-q", "origin", "main")
            self.git(self.source, "reset", "--hard", "origin/main")
            (self.source / "README.md").write_text("post-release changelog reconciliation")
            self.commit(self.source, "Follow-up work")
            self.git(self.source, "push", "-q", "origin", "main")
            self.assertTrue(release.publish(self.directory))
            with mock.patch.object(sys, "argv", ["release.py", "publish", "--directory", str(self.directory)]):
                self.assertEqual(release.main(), 0)
        self.assertEqual(release.Release.load(self.directory).phase, "published")

    def test_resume_only_unpublished_interrupted_real_preparation(self) -> None:
        """Keep failed preparation diagnostics and resume without repeating rehearsal."""
        with mock.patch.dict(os.environ, RELEASE_TEST_MAVEN_FAIL="prepare"):
            with self.assertRaises(subprocess.CalledProcessError):
                self.prepare()
        state = release.Release.load(self.directory)
        self.assertEqual(state.phase, "preparing")
        self.git(self.directory, "checkout", "-qb", "wrong-branch")
        with self.assertRaisesRegex(ValueError, "branch changed"):
            release.resume(self.directory)
        self.git(self.directory, "checkout", "-q", state.branch)
        self.git(self.directory, "push", "-q", "origin", "HEAD:refs/heads/" + state.branch)
        with self.assertRaisesRegex(ValueError, "branch has already been pushed"):
            release.resume(self.directory)
        self.git(self.directory, "push", "-q", "origin", ":refs/heads/" + state.branch)
        self.git(self.directory, "push", "-q", "origin", "refs/tags/v1.0.0:refs/tags/" + state.tag)
        with self.assertRaisesRegex(ValueError, "tag has already been pushed"):
            release.resume(self.directory)
        self.git(self.directory, "push", "-q", "origin", ":refs/tags/" + state.tag)
        result = self.cli("resume", "--directory", str(self.directory))
        self.assertEqual(result.returncode, 0, result.stderr)
        state = release.Release.load(self.directory)
        self.assertEqual(state.phase, "prepared")
        calls = [json.loads(line) for line in (self.directory / ".git/maven-calls.jsonl").read_text().splitlines()]
        self.assertIn("-Dresume=true", calls[-1])
        self.assertEqual(sum("-DdryRun=true" in call for call in calls), 1)
        result = self.cli("resume", "--directory", str(self.directory))
        self.assertEqual(result.returncode, 1)
        self.assertIn("Only interrupted real preparation", result.stderr)

    def test_failed_rehearsal_does_not_create_a_release_or_allow_resume(self) -> None:
        """A failed dry run remains an inspectable rehearsal with the source revision intact."""
        with mock.patch.dict(os.environ, RELEASE_TEST_MAVEN_FAIL="dry"):
            with self.assertRaises(subprocess.CalledProcessError):
                self.prepare()
        self.assertEqual(release.Release.load(self.directory).phase, "rehearsal")
        self.assertEqual(self.git(self.directory, "rev-parse", "HEAD"), self.base)
        self.assertEqual(self.git(self.directory, "tag", "--list", "v2.0.0"), "")
        result = self.cli("publish", "--directory", str(self.directory))
        self.assertEqual(result.returncode, 1)
        self.assertIn("Complete preparation before publishing", result.stderr)

    def test_signing_requires_trusted_ssh_identity_and_resolves_paths(self) -> None:
        """Resolve local key paths without accepting missing trust or another signing format."""
        shutil.copy2(self.key, self.source / ".git/key")
        shutil.copy2(self.trusted, self.source / ".git/signers")
        self.git(self.source, "config", "user.signingKey", ".git/key")
        self.git(self.source, "config", "gpg.ssh.allowedSignersFile", ".git/signers")
        settings = release.signing(self.source)
        self.assertEqual(settings["user.signingKey"], str(self.source / ".git/key"))
        self.assertEqual(settings["gpg.ssh.allowedSignersFile"], str(self.source / ".git/signers"))
        literal = "key::" + self.key.with_suffix(".pub").read_text().strip()
        self.git(self.source, "config", "user.signingKey", literal)
        self.assertEqual(release.signing(self.source)["user.signingKey"], literal)
        self.git(self.source, "config", "gpg.format", "openpgp")
        with self.assertRaisesRegex(ValueError, "SSH signing"):
            release.signing(self.source)
        self.git(self.source, "config", "gpg.format", "ssh")
        self.git(self.source, "config", "gpg.ssh.allowedSignersFile", ".git/missing")
        with self.assertRaisesRegex(ValueError, "trusted release signers"):
            release.signing(self.source)

    def test_version_policy_covers_all_supported_contract_change_kinds(self) -> None:
        """Require matching snapshots, explicit next versions, and sufficient semantic increments."""
        for change, version in (("patch", "1.0.1"), ("minor", "1.1.0"), ("major", "2.0.0")):
            release.check_versions(version + "-SNAPSHOT", "1.0.0", version, "3.0.0-SNAPSHOT", change)
        invalid = (
            ("bad", "1.0.0", "2.0.0", "2.0.1-SNAPSHOT", "major"),
            ("2.0.0-SNAPSHOT", "bad", "2.0.0", "2.0.1-SNAPSHOT", "major"),
            ("2.0.0-SNAPSHOT", "1.0.0", "2.0.0", "2.0.1", "major"),
            ("2.0.0-SNAPSHOT", "1.0.0", "2.0.0", "2.0.0-SNAPSHOT", "major"),
            ("1.0.0-SNAPSHOT", "1.0.0", "1.0.0", "2.0.0-SNAPSHOT", "patch"),
            ("1.0.1-SNAPSHOT", "1.0.0", "1.0.1", "2.0.0-SNAPSHOT", "major"),
        )
        for values in invalid:
            with self.subTest(values=values), self.assertRaises(ValueError):
                release.check_versions(*values)

    def test_preparation_rejects_dirty_sources_and_existing_destinations(self) -> None:
        """Neither a dirty worktree nor an existing destination may be changed by preparation."""
        untracked = self.source / "untracked"
        untracked.write_text("keep me")
        with self.assertRaisesRegex(ValueError, "sources must be clean"):
            self.prepare()
        untracked.unlink()
        self.directory.mkdir()
        with self.assertRaisesRegex(ValueError, "must not already exist"):
            self.prepare()

    def test_preparation_requires_published_history_and_current_main(self) -> None:
        """Do not infer published releases from tags or prepare divergent local sources."""
        records = [
            [
                {"tag_name": "v9.0.0", "draft": True, "prerelease": False},
                {"tag_name": "v9.0.0-rc1", "draft": False, "prerelease": True},
                {"tag_name": "nonsense", "draft": False, "prerelease": False},
            ]
        ]
        with mock.patch.dict(os.environ, RELEASE_TEST_RECORDS=json.dumps(records)):
            with self.assertRaisesRegex(ValueError, "latest published stable release"):
                self.prepare()
        (self.source / "README.md").write_text("unpublished change")
        self.commit(self.source, "Local work")
        with self.assertRaisesRegex(ValueError, "current origin/main"):
            self.prepare()

    def test_preparation_rejects_existing_tags_and_remote_release_branches(self) -> None:
        """Refuse collisions before creating an isolated checkout."""
        self.git(self.source, "tag", "-s", "-m", "Collision", "v2.0.0")
        with self.assertRaisesRegex(ValueError, "tag already exists"):
            self.prepare()
        self.git(self.source, "tag", "-d", "v2.0.0")
        self.git(self.source, "push", "-q", "origin", "HEAD:refs/heads/release/2.0.0")
        with self.assertRaisesRegex(ValueError, "release branch already exists"):
            self.prepare()

    def test_ci_checks_stable_and_next_development_revisions(self) -> None:
        """Resolve next-development documentation from its immutable stable parent."""
        state = self.prepare()
        output = self.root / "github-output"
        result = self.cli(
            "ci", "--source", str(self.directory), "--branch", state.branch, "--github-output", str(output)
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output.read_text(), f"pending_commit={state.release_commit}\n")
        release.ci(self.source, "main", output)
        self.assertTrue(output.read_text().endswith("pending_commit=\n"))
        self.git(self.directory, "tag", "-d", state.tag)
        release.ci(self.directory, "main", output)
        self.assertTrue(output.read_text().endswith(f"pending_commit={state.release_commit}\n"))
        original_tag = self.git(self.directory, "rev-parse", "refs/tags/v1.0.0")
        self.git(self.directory, "tag", "-d", "v1.0.0")
        with self.assertRaisesRegex(ValueError, "multiple untagged"):
            release.ci(self.directory, "main", output)
        self.git(self.directory, "update-ref", "refs/tags/v1.0.0", original_tag)
        self.git(self.directory, "checkout", "-q", "--detach", state.release_commit)
        release.ci(self.directory, state.branch, output)
        self.assertTrue(output.read_text().endswith("pending_commit=\n"))
        with self.assertRaisesRegex(ValueError, "release/x.y.z"):
            release.ci(self.directory, "feature/unexpected", output)
        with self.assertRaisesRegex(ValueError, "stable x.y.z"):
            release.ci(self.directory, "release/invalid", output)
        self.pom("1.9.0", self.directory)
        with self.assertRaisesRegex(ValueError, "unexpected Maven version"):
            release.ci(self.directory, state.branch, output)
        self.pom("2.0.0-SNAPSHOT", self.directory)
        with self.assertRaisesRegex(ValueError, "must follow the release"):
            release.ci(self.directory, state.branch, output)

    def test_modified_local_graph_or_remote_main_blocks_publication(self) -> None:
        """Refuse later local changes and remote divergence before pushing a release branch."""
        state = self.prepare()
        self.git(self.directory, "checkout", "-qb", "unexpected")
        with self.assertRaisesRegex(ValueError, "branch changed"):
            release.publish(self.directory)
        self.git(self.directory, "checkout", "-q", state.branch)
        (self.source / "README.md").write_text("concurrent main change")
        self.commit(self.source, "Concurrent main")
        self.git(self.source, "push", "-q", "origin", "main")
        with self.assertRaisesRegex(ValueError, "Remote main changed"):
            release.publish(self.directory)
        self.assertEqual(release.remote_ref(self.directory, "refs/heads/release/2.0.0"), "")

    def test_origin_requires_a_single_identical_fetch_and_push_destination(self) -> None:
        """Reject URL ambiguity and redirected push destinations before publication."""
        self.assertEqual(release.origin(self.source), str(self.origin))
        self.git(self.source, "config", "remote.origin.pushurl", str(self.root / "another.git"))
        with self.assertRaisesRegex(ValueError, "same single fetch and push URL"):
            release.origin(self.source)
        self.git(self.source, "config", "--unset", "remote.origin.pushurl")
        self.git(self.source, "config", "--add", "remote.origin.url", str(self.root / "another.git"))
        with self.assertRaisesRegex(ValueError, "exactly one fetch URL"):
            release.origin(self.source)

    def test_unsigned_version_transition_fails_signature_verification(self) -> None:
        """A correctly shaped version graph is insufficient without valid commit signatures."""
        state = self.prepare()
        self.git(self.directory, "commit", "--amend", "--no-edit", "--no-gpg-sign", "-q")
        state.development_commit = self.git(self.directory, "rev-parse", "HEAD")
        with self.assertRaises(subprocess.CalledProcessError):
            state.verify(self.directory)

    def test_pom_shape_allows_only_version_and_scm_tag_semantics(self) -> None:
        """Ignore Maven's two owned values while preserving all other POM meaning."""
        original = release.pom_shape(self.source, "HEAD")
        self.pom("2.0.0")
        pom = self.source / "pom.xml"
        pom.write_text(pom.read_text().replace("<tag>HEAD</tag>", "<tag>v2.0.0</tag>"))
        self.commit(self.source, "Change release-owned values")
        self.assertEqual(release.pom_shape(self.source, "HEAD"), original)
        pom.write_text(pom.read_text().replace("</project>", "<name>Different build</name></project>"))
        self.commit(self.source, "Unexpected POM change")
        self.assertNotEqual(release.pom_shape(self.source, "HEAD"), original)
        pom.write_text(pom.read_text().replace("<scm><tag>v2.0.0</tag></scm>", ""))
        self.commit(self.source, "Missing SCM tag")
        with self.assertRaisesRegex(ValueError, "Missing release-owned POM element"):
            release.pom_shape(self.source, "HEAD")

    def test_cli_reports_missing_state_and_invalid_options(self) -> None:
        """Report actionable recovery guidance and ordinary argument errors without mutation."""
        result = self.cli("status", "--directory", str(self.source))
        self.assertEqual(result.returncode, 1)
        self.assertIn("Inspect the report", result.stderr)
        result = self.cli("invalid")
        self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    unittest.main()
