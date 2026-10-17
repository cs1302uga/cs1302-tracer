"""Exercise guide publication phases with genuine Git histories, archives, and process boundaries."""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path
from typing import TYPE_CHECKING, Any, cast
from unittest.mock import patch

from scripts import guide_archive
from scripts import guide_publish as publish

from tests.support import copy_scripts, executable, invoke, tool_environment

if TYPE_CHECKING:
    from docs_release import ReleaseEntry

VERSIONS = "1.0.0,2.0.0"
REPOSITORY = "example/guides"


class GuidePublishTests(unittest.TestCase):
    """Freeze complete publications and produce catalog evidence only from authenticated remote files."""

    def setUp(self) -> None:
        """Create two tagged snapshots and a separate clean current-main documentation builder."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        copy_scripts(self.root)
        (self.root / ".gitignore").write_text("scripts/\nbin/\nassets/\ntarget/\ngithub.json\ncommands.jsonl\n")
        for version in VERSIONS.split(","):
            snapshot = self.root / f"website/versioned_docs/version-{version}"
            snapshot.mkdir(parents=True)
            (snapshot / "index.md").write_text("# Reviewed documentation\n")
            sidebar = self.root / f"website/versioned_sidebars/version-{version}-sidebars.json"
            sidebar.parent.mkdir(exist_ok=True)
            sidebar.write_text('{"docs": ["index"]}')
        self.git("init", "-q")
        self.git("config", "user.name", "Guide fixture")
        self.git("config", "user.email", "guide@example.invalid")
        self.git("config", "commit.gpgSign", "false")
        self.git("config", "tag.gpgSign", "false")
        self.git("config", "core.hooksPath", "/dev/null")
        self.git("add", ".")
        self.git("commit", "-qm", "Published snapshots")
        release = self.git("rev-parse", "HEAD")
        self.releases: dict[str, str] = dict.fromkeys(VERSIONS.split(","), release)
        for version in self.releases:
            self.git("tag", "v" + version)
        (self.root / "builder.txt").write_text("Reviewed builder")
        self.git("add", "builder.txt")
        self.git("commit", "-qm", "Documentation builder")
        self.builder = self.git("rev-parse", "HEAD")
        self.git("update-ref", "refs/remotes/origin/main", self.builder)
        self.directory = self.root / "target/archives"
        self.assets = self.root / "assets"
        self.assets.mkdir()
        self.directory.mkdir(parents=True)
        for version in self.releases:
            tree = self.root / "target/trees" / version
            tree.mkdir(parents=True)
            (tree / "index.html").write_text(f"<h1>{version}</h1>")
            guide_archive.create(
                self.root, cast("ReleaseEntry", {"name": version, "sourceCommit": release}), tree, self.assets
            )
            (self.assets / guide_archive.filenames(version)[2]).write_text('{"externalSigstoreFixture": true}')
        for path in self.assets.iterdir():
            shutil.copy2(path, self.directory / path.name)
        self.environment = tool_environment(self.root)
        self.environment.update(GH_REPO="wrong/repository", GH_HOST="wrong.invalid")
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
    print('authentication or network failure', file=sys.stderr)
    sys.exit(1)
if args[0] == 'api':
    if args[3].startswith('repos/example/guides/git/matching-refs/tags/'):
        assert args == ['api', '--hostname', 'github.com',
            'repos/example/guides/git/matching-refs/tags/docs-' + state['builder']], args
        print(json.dumps(state['refs']))
        sys.exit(0)
    assert args == ['api', '--hostname', 'github.com', '--paginate',
                    'repos/example/guides/releases?per_page=100', '--jq', '.[].tag_name'], args
    print('\\n'.join(state['tags']))
elif args[:2] == ['attestation', 'verify']:
    assert args[args.index('--repo') + 1] == 'example/guides', args
    assert args[args.index('--hostname') + 1] == 'github.com', args
    assert args[args.index('--source-ref') + 1] == 'refs/heads/main', args
    assert args[args.index('--source-digest') + 1] == state['builder'], args
    assert args[args.index('--signer-digest') + 1] == state['builder'], args
    assert args[args.index('--signer-workflow') + 1] == 'example/guides/.github/workflows/docs-archive.yml', args
    assert '--deny-self-hosted-runners' in args, args
    assert pathlib.Path(args[2]).is_file()
    assert pathlib.Path(args[args.index('--bundle') + 1]).is_file()
    sys.exit(state.get('attestation_exit', 0))
else:
    assert args[args.index('--repo') + 1] == 'github.com/example/guides', args
    assert args[2] == 'docs-' + state['builder'], args
    if args[:2] == ['release', 'view']:
        print(json.dumps(state['release']))
    elif args[:2] == ['release', 'download']:
        target = pathlib.Path(args[args.index('--dir') + 1])
        for index, arg in enumerate(args):
            if arg == '--pattern':
                name = args[index + 1]
                shutil.copy2(root / 'assets' / name, target / name)
    else:
        raise AssertionError(args)
""",
        )
        self.state: dict[str, Any] = {
            "builder": self.builder,
            "refs": [{"ref": "refs/tags/docs-" + self.builder, "object": {"type": "commit", "sha": self.builder}}],
            "tags": ["docs-" + self.builder],
            "release": {
                "tagName": "docs-" + self.builder,
                "isDraft": False,
                "isPrerelease": True,
                "assets": [{"name": path.name, "size": path.stat().st_size} for path in sorted(self.assets.iterdir())],
            },
        }
        self.save()

    def git(self, *args: str) -> str:
        """Run actual Git in the disposable repository."""
        return subprocess.check_output(["git", *args], cwd=self.root, text=True).strip()

    def save(self) -> None:
        """Write external responses independently of archive verification."""
        (self.root / "github.json").write_text(json.dumps(self.state))

    def process(self, action: str, output: Path | None = None) -> bool:
        """Run a publication phase against its real candidate and remote file sets."""
        self.save()
        return publish.process(self.root, action, self.directory, self.releases, self.builder, REPOSITORY, output)[0]

    def cli(self, action: str, *args: str) -> subprocess.CompletedProcess[str]:
        """Exercise copied CLI code and the clean-builder checks through a real subprocess."""
        self.save()
        return invoke(
            self.root,
            "guide_publish.py",
            action,
            "--versions",
            VERSIONS,
            "--builder-commit",
            self.builder,
            "--repository",
            REPOSITORY,
            "--directory",
            str(self.directory),
            *args,
            env=self.environment,
        )

    def test_plan_and_clean_builder_identity(self) -> None:
        """Only validated stable selections from the expected clean current-main builder reach tools."""
        result = self.cli("plan")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), VERSIONS.split(","))
        for versions, builder, repository in (
            ("1.0.0,1.0.0", self.builder, REPOSITORY),
            ("1.0.0, 2.0.0", self.builder, REPOSITORY),
            ("", self.builder, REPOSITORY),
            (VERSIONS, "invalid", REPOSITORY),
            (VERSIONS, self.builder, "owner/repo/escape"),
            (VERSIONS, "a" * 40, REPOSITORY),
        ):
            with self.subTest(versions=versions, builder=builder, repository=repository):
                with self.assertRaises(ValueError):
                    publish.context(self.root, versions, builder, repository)
        self.git("update-ref", "refs/remotes/origin/main", self.releases["1.0.0"])
        with self.assertRaisesRegex(ValueError, "current-main"):
            publish.context(self.root, VERSIONS, self.builder, REPOSITORY)
        self.git("update-ref", "refs/remotes/origin/main", self.builder)
        (self.root / "builder.txt").write_text("Unreviewed edit")
        with self.assertRaisesRegex(ValueError, "clean builder"):
            publish.context(self.root, VERSIONS, self.builder, REPOSITORY)

    def test_fresh_and_remote_error_are_distinct(self) -> None:
        """Only a successful listing without the publication permits fresh attestation."""
        self.state["tags"] = []
        output = self.root / "target/github-output"
        result = self.cli("preserve", "--github-output", str(output))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output.read_text(), "preserved=false\npublished=false\n")
        with self.assertRaisesRegex(ValueError, "publication is missing"):
            self.process("verify-remote")
        self.state["fail"] = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.process("preserve")

    def test_preserve_reuses_remote_trios_and_validates_every_subject(self) -> None:
        """Frozen archives replace newly generated candidates only after complete remote verification."""
        version = "1.0.0"
        tree = self.root / "target/trees" / version
        (tree / "index.html").write_text("A newly generated valid candidate")
        guide_archive.create(
            self.root,
            cast("ReleaseEntry", {"name": version, "sourceCommit": self.releases[version]}),
            tree,
            self.directory,
        )
        self.assertNotEqual(
            (self.directory / guide_archive.filenames(version)[0]).read_bytes(),
            (self.assets / guide_archive.filenames(version)[0]).read_bytes(),
        )
        output = self.root / "target/github-output"
        result = self.cli("preserve", "--github-output", str(output))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output.read_text(), "preserved=true\npublished=true\n")
        for remote in self.assets.iterdir():
            self.assertEqual((self.directory / remote.name).read_bytes(), remote.read_bytes())
        self.assertFalse(self.process("verify-local"))
        self.assertTrue(self.process("verify-remote"))
        commands = [json.loads(line) for line in (self.root / "commands.jsonl").read_text().splitlines()]
        downloads = [args for args in commands if args[:2] == ["release", "download"]]
        self.assertEqual(len(downloads), 2)
        for args in downloads:
            self.assertFalse(Path(args[args.index("--dir") + 1]).exists())

    def test_catalog_contains_only_verified_published_descriptors(self) -> None:
        """The candidate records software/source/build identity and actual remote bytes in stable order."""
        output = self.root / "target/review/catalog.json"
        result = self.cli("catalog", "--catalog-output", str(output))
        self.assertEqual(result.returncode, 0, result.stderr)
        candidate = json.loads(output.read_text())
        self.assertEqual(candidate["schemaVersion"], 1)
        self.assertEqual(set(candidate["archives"]), set(self.releases))
        for version, record in candidate["archives"].items():
            self.assertEqual(record["releaseCommit"], self.releases[version])
            self.assertEqual(record["builderCommit"], self.builder)
            self.assertEqual(record["documentationCommit"], self.builder)
            self.assertEqual(record["publication"], "docs-" + self.builder)
            self.assertEqual(record["sourceDigest"], guide_archive.source_digest(self.root, version))
            self.assertEqual([asset["name"] for asset in record["assets"]], list(guide_archive.filenames(version)))
            for asset in record["assets"]:
                path = self.assets / asset["name"]
                self.assertEqual(asset["size"], path.stat().st_size)
                self.assertEqual(asset["sha256"], guide_archive.digest(path))
                self.assertEqual(
                    asset["url"], f"https://github.com/{REPOSITORY}/releases/download/docs-{self.builder}/{path.name}"
                )
        self.state["release"]["isDraft"] = True
        self.assertTrue(self.process("verify-remote"))
        with self.assertRaisesRegex(ValueError, "published prerelease"):
            self.process("catalog", output)
        self.state["release"]["isDraft"] = False
        with self.assertRaisesRegex(ValueError, "output path"):
            self.process("catalog")

    def test_partial_or_different_selection_is_never_silently_repaired(self) -> None:
        """Any existing incomplete set, including an empty draft, requires explicit recovery."""
        original = self.state["release"]["assets"]
        for assets in ([], original[:3], original[:-1]):
            with self.subTest(assets=assets):
                self.state["release"]["assets"] = assets
                with self.assertRaisesRegex(ValueError, "Partial documentation publication"):
                    self.process("preserve")
        self.state["release"]["assets"] = original + [{"name": "unselected.zip", "size": 1}]
        with self.assertRaisesRegex(ValueError, "unexpected"):
            self.process("preserve")

    def test_existing_tags_and_publication_state_are_verified(self) -> None:
        """A docs tag must point directly at the expected builder; complete drafts still need publication."""
        original = deepcopy(self.state["refs"])
        for refs in ({}, [None]):
            with self.subTest(refs=refs):
                self.state["refs"] = refs
                with self.assertRaisesRegex(ValueError, "ref metadata"):
                    self.process("preserve")
        for refs in (
            original * 2,
            [{"ref": original[0]["ref"]}],
            [{"ref": original[0]["ref"], "object": {"type": "tag", "sha": self.builder}}],
            [{"ref": original[0]["ref"], "object": {"type": "commit", "sha": "f" * 40}}],
        ):
            with self.subTest(refs=refs):
                self.state["refs"] = refs
                with self.assertRaisesRegex(ValueError, "exact builder"):
                    self.process("preserve")
        self.state["refs"] = []
        with self.assertRaisesRegex(ValueError, "tag is missing"):
            self.process("preserve")
        self.state["tags"] = []
        self.assertFalse(self.process("preserve"))
        self.state["refs"] = original
        self.state["tags"] = ["docs-" + self.builder]
        self.state["release"]["isDraft"] = True
        output = self.root / "target/github-output"
        result = self.cli("preserve", "--github-output", str(output))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(output.read_text(), "preserved=true\npublished=false\n")

    def test_malformed_release_and_asset_metadata(self) -> None:
        """Validate metadata before choosing any remote filenames or trusting size declarations."""
        original = deepcopy(self.state["release"])
        self.state["tags"].append("docs-" + self.builder)
        with self.assertRaisesRegex(ValueError, "Ambiguous"):
            self.process("preserve")
        self.state["tags"].pop()
        for release in (
            [],
            {},
            {**original, "tagName": "wrong"},
            {**original, "isDraft": 1},
            {**original, "isPrerelease": False},
            {**original, "assets": {}},
        ):
            with self.subTest(release=release):
                self.state["release"] = release
                with self.assertRaisesRegex(ValueError, "prerelease metadata"):
                    self.process("preserve")
        self.state["release"] = original
        name = guide_archive.filenames("1.0.0")[0]
        fixtures = [
            [None],
            [{}],
            [{"name": name}],
            [{"name": name, "size": True}],
            [{"name": name, "size": 0}],
            [{"name": name, "size": publish.MAX_ARCHIVE + 1}],
            [{"name": name, "size": 1}] * 2,
        ]
        for assets in fixtures:
            with self.subTest(assets=assets):
                self.state["release"]["assets"] = assets
                with self.assertRaisesRegex(ValueError, "release asset"):
                    self.process("preserve")

    def test_snapshot_mismatches_fail_in_candidates_and_remote_evidence(self) -> None:
        """Archive identity must match the reviewed snapshot even when its attestation is accepted."""
        snapshot = self.root / "website/versioned_docs/version-1.0.0/index.md"
        snapshot.write_text("Changed guides")
        with self.assertRaisesRegex(ValueError, "Guide candidate.*snapshot"):
            self.process("preserve")
        with self.assertRaisesRegex(ValueError, "Guide publication.*snapshot"):
            self.process("verify-local")

    def test_download_and_attestation_failures_do_not_emit_catalogs(self) -> None:
        """Changed remote sizes, bad checksums, and bad attestations stop before promotion."""
        output = self.root / "target/catalog.json"
        self.state["release"]["assets"][0]["size"] += 1
        with self.assertRaisesRegex(ValueError, "published sizes"):
            self.process("catalog", output)
        self.state["release"]["assets"][0]["size"] -= 1
        checksum = self.assets / guide_archive.filenames("1.0.0")[1]
        original = checksum.read_text()
        checksum.write_text("0" * 64 + original[64:])
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.process("catalog", output)
        checksum.write_text(original)
        self.state["attestation_exit"] = 1
        with self.assertRaises(subprocess.CalledProcessError):
            self.process("catalog", output)
        self.assertFalse(output.exists())
