"""Verify isolated guide publications through real Git trees, ZIPs, and process boundaries."""

import io
import json
import os
import stat
import struct
import subprocess
import tempfile
import unittest
import warnings
import zipfile
from copy import deepcopy
from pathlib import Path
from typing import TYPE_CHECKING, Any
from unittest.mock import patch

from scripts import guide_archive as guide

from tests.support import copy_scripts, executable, invoke, tool_environment

if TYPE_CHECKING:
    from docs_release import ReleaseEntry


class GuideArchiveTests(unittest.TestCase):
    """Authenticate exact documented snapshots without permitting unsafe extraction or silent fallback."""

    def setUp(self) -> None:
        """Create a software release followed by a separate reviewed documentation revision."""
        temporary = tempfile.TemporaryDirectory(prefix="guide archive ")
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.root = self.directory / "project"
        self.root.mkdir()
        copy_scripts(self.root)
        self.environment = tool_environment(self.root)
        environment = patch.dict(os.environ, self.environment)
        environment.start()
        self.addCleanup(environment.stop)
        self.git("init", "-q")
        self.git("config", "user.name", "Guide Test")
        self.git("config", "user.email", "guides@example.invalid")
        self.git("config", "core.hooksPath", "/dev/null")
        self.git("config", "tag.gpgSign", "false")
        (self.root / ".gitignore").write_text(
            "/scripts/\n/bin/\n/*.json\n/attestation-fails\n/website/guide-archives.json\n"
        )
        self.version = "1.2.3"
        self.snapshot = self.root / f"website/versioned_docs/version-{self.version}"
        self.snapshot.mkdir(parents=True)
        (self.snapshot / "index.md").write_text("# Released guide\n")
        self.sidebar = self.root / f"website/versioned_sidebars/version-{self.version}-sidebars.json"
        self.sidebar.parent.mkdir()
        self.sidebar.write_text('{"docs":["index"]}')
        (self.root / "pom.xml").write_text(f"<project><version>{self.version}</version></project>")
        self.git("add", ".")
        self.git("commit", "-qm", "software release")
        self.release_commit = self.git("rev-parse", "HEAD")
        self.git("tag", "v" + self.version)
        (self.snapshot / "index.md").write_text("# Corrected release guide\n")
        self.git("add", ".")
        self.git("commit", "-qm", "reviewed historical documentation correction")
        self.builder = self.git("rev-parse", "HEAD")
        executable(
            self.root / "bin/gh",
            """
import json, pathlib, sys
root = pathlib.Path.cwd()
(root / 'gh-arguments.json').write_text(json.dumps(sys.argv[1:]))
sys.exit(1 if (root / 'attestation-fails').exists() else 0)
""",
        )
        self.tree = self.directory / "rendered"
        (self.tree / "guides/install").mkdir(parents=True)
        (self.tree / "assets/js").mkdir(parents=True)
        (self.tree / "index.html").write_text("<html>Version home</html>")
        (self.tree / "guides/install/index.html").write_text("<html>Installation</html>")
        (self.tree / "assets/js/main.js").write_text("console.log('isolated guide');")
        (self.tree / "404.html").write_text("Page not found")
        self.assets = self.directory / "assets"
        self.entry: ReleaseEntry = {
            "name": self.version,
            "label": self.version,
            "docsPath": self.version,
            "docsUrl": guide.BASE + self.version + "/",
            "javadocPath": "api/" + self.version,
            "javadocUrl": guide.BASE + "api/" + self.version + "/index.html",
            "sourceCommit": self.release_commit,
            "banner": "none",
        }
        self.metadata = guide.create(self.root, self.entry, self.tree, self.assets)
        self.path = self.assets / guide.filenames(self.version)[0]
        self.original = self.path.read_bytes()
        self.companions()
        self.record = self.publication()
        self.catalog()

    def git(self, *arguments: str) -> str:
        """Use real Git while disabling inherited signing and repository hooks."""
        return subprocess.check_output(
            ["git", "-c", "commit.gpgsign=false", *arguments], cwd=self.root, text=True, stderr=subprocess.PIPE
        ).strip()

    def companions(self) -> None:
        """Write evidence sidecars for the actual candidate bytes."""
        Path(str(self.path) + ".sha256").write_text(f"{guide.digest(self.path)}  {self.path.name}\n")
        Path(str(self.path) + ".sigstore.json").write_text('{"externalAttestationFixture":true}')

    def publication(self) -> dict[str, Any]:
        """Pin the same publication descriptors a reviewed catalog stores."""
        return {
            "version": self.version,
            "releaseCommit": self.release_commit,
            "documentationCommit": self.builder,
            "builderCommit": self.builder,
            "sourceDigest": guide.source_digest(self.root, self.version),
            "publication": "docs-" + self.builder,
            "assets": [
                {
                    "name": name,
                    "url": f"https://github.com/{guide.REPOSITORY}/releases/download/docs-{self.builder}/{name}",
                    "size": (self.assets / name).stat().st_size,
                    "sha256": guide.digest(self.assets / name),
                }
                for name in guide.filenames(self.version)
            ],
        }

    def catalog(self, value: object | None = None) -> None:
        """Write a real reviewed catalog, or a deliberately malformed representation."""
        (self.root / "website/guide-archives.json").write_text(
            json.dumps({"schemaVersion": 1, "archives": {self.version: self.record}} if value is None else value)
        )

    def archive(
        self,
        metadata: object,
        *,
        files: dict[str, bytes] | None = None,
        extras: tuple[tuple[str | zipfile.ZipInfo, bytes], ...] = (),
    ) -> None:
        """Build genuine ZIPs so rejected metadata and member structures traverse the parser."""
        if files is None:
            files = {name: (self.tree / name).read_bytes() for name in self.metadata["files"]}
        with warnings.catch_warnings(), zipfile.ZipFile(self.path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            warnings.simplefilter("ignore", UserWarning)
            archive.writestr(guide.MANIFEST, json.dumps(metadata))
            for name, content in files.items():
                archive.writestr(name, content)
            for name, content in extras:
                archive.writestr(name, content)

    def inspect(self, *, require_clean: bool = True, destination: Path | None = None) -> dict[str, Any]:
        """Exercise the production inspection interface with the fixture's exact identities."""
        return guide.inspect_archive(
            self.path,
            self.version,
            self.release_commit,
            self.builder,
            require_clean=require_clean,
            destination=destination,
        )

    def download(self, url: str, *, timeout: int) -> io.BytesIO:
        """Substitute only the external HTTP boundary with pinned on-disk bytes."""
        self.assertEqual(timeout, 30)
        return io.BytesIO((self.assets / url.rsplit("/", 1)[-1]).read_bytes())

    def test_clean_round_trip_distinguishes_documentation_and_software_commits(self) -> None:
        """A corrected snapshot retains original release provenance while naming its real builder."""
        self.assertNotEqual(self.release_commit, self.builder)
        self.assertEqual(self.metadata["releaseCommit"], self.release_commit)
        self.assertEqual(self.metadata["documentationCommit"], self.builder)
        self.assertFalse(self.metadata["dirty"])
        self.assertEqual(self.metadata["routes"], ["", "guides/install/"])
        self.assertEqual(self.inspect(), self.metadata)
        destination = self.directory / "composed/1.2.3"
        self.assertEqual(self.inspect(destination=destination), self.metadata)
        self.assertEqual(guide.inventory(destination), guide.inventory(self.tree))
        self.assertFalse((destination / guide.MANIFEST).exists())
        with self.assertRaises(OSError):
            self.inspect(destination=destination)
        self.assertEqual(guide.inventory(destination), guide.inventory(self.tree))

    def test_dirty_candidates_are_inspectable_but_cannot_be_published(self) -> None:
        """Local changes are recorded and strict verification fails before extraction."""
        (self.snapshot / "index.md").write_text("Uncommitted correction")
        metadata = guide.create(self.root, self.entry, self.tree, self.assets)
        self.assertTrue(metadata["dirty"])
        self.assertEqual(self.inspect(require_clean=False), metadata)
        with self.assertRaisesRegex(ValueError, "expected clean"):
            self.inspect()

    def test_source_digest_tracks_reviewed_docs_without_global_asset_churn(self) -> None:
        """Shared assets stay frozen in archives while reviewed source corrections invalidate them."""
        first = guide.source_digest(self.root, self.version)
        static = self.root / "website/static"
        static.mkdir()
        (static / "image.svg").write_text("<svg/>")
        second = guide.source_digest(self.root, self.version)
        self.assertEqual(first, second)
        self.sidebar.write_text('{"docs":[]}')
        self.assertNotEqual(second, guide.source_digest(self.root, self.version))
        self.sidebar.unlink()
        self.sidebar.symlink_to(self.snapshot / "index.md")
        with self.assertRaisesRegex(ValueError, "sidebar.*regular"):
            guide.source_digest(self.root, self.version)

    def test_source_and_output_trees_reject_redirects_and_special_files(self) -> None:
        """Symlinks, FIFOs, and non-directory roots cannot enter an application inventory."""
        with self.assertRaisesRegex(ValueError, "real directory"):
            guide.inventory(self.tree / "index.html")
        link = self.directory / "tree-link"
        link.symlink_to(self.tree, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "real directory"):
            guide.inventory(link)
        link = self.tree / "linked"
        link.symlink_to(self.root / "pom.xml")
        with self.assertRaisesRegex(ValueError, "symbolic"):
            guide.inventory(self.tree)
        link.unlink()
        os.mkfifo(link)
        with self.assertRaisesRegex(ValueError, "regular files"):
            guide.inventory(self.tree)

    def test_create_requires_release_identity_index_and_owned_manifest(self) -> None:
        """Unreleased entries and renderer-supplied provenance cannot become archive candidates."""
        pending = self.entry.copy()
        pending["sourceCommit"] = None
        with self.assertRaises(ValueError):
            guide.create(self.root, pending, self.tree, self.assets)
        (self.tree / guide.MANIFEST).write_text("injected metadata")
        with self.assertRaisesRegex(ValueError, "cannot supply"):
            guide.create(self.root, self.entry, self.tree, self.assets)
        (self.tree / guide.MANIFEST).unlink()
        (self.tree / "index.html").unlink()
        with self.assertRaisesRegex(ValueError, "requires an index"):
            guide.create(self.root, self.entry, self.tree, self.assets)

    def test_archive_paths_are_portable_non_conflicting_and_regular(self) -> None:
        """Reject traversal, duplicate names, symlinks, folders, and file/parent collisions."""
        for name in ("", "/escape", "../escape", "a/./b", "a//b", "a\\b", "C:escape", "control\x01"):
            with self.subTest(name=name):
                self.assertFalse(guide.safe_name(name))
        for name in ("assets/main.js", "index.html"):
            self.assertTrue(guide.safe_name(name))
        for name in ("../escape", "INDEX.HTML", "directory/"):
            self.archive(self.metadata, extras=((name, b"bad"),))
            with self.assertRaisesRegex(ValueError, "Unsafe or duplicate"):
                self.inspect()
        linked = zipfile.ZipInfo("linked")
        linked.external_attr = (stat.S_IFLNK | 0o777) << 16
        self.archive(self.metadata, extras=((linked, b"index.html"),))
        with self.assertRaisesRegex(ValueError, "Unsafe or duplicate"):
            self.inspect()
        self.archive(self.metadata, extras=(("assets", b"conflicting file"),))
        with self.assertRaisesRegex(ValueError, "Conflicting"):
            self.inspect()
        self.archive(self.metadata, extras=(("nulXname", b"bad"),))
        self.path.write_bytes(self.path.read_bytes().replace(b"nulXname", b"nul\x00name"))
        with self.assertRaisesRegex(ValueError, "Unsafe or duplicate"):
            self.inspect()

    def test_zip_limits_are_checked_before_decompression(self) -> None:
        """Untrusted ZIP headers cannot force excessive extraction or encrypted reads."""
        for field, format_, value, message in (
            (8, "H", 1, "Unsafe or duplicate"),
            (24, "I", guide.MAX_MEMBER + 1, "Unsafe or duplicate"),
            (24, "I", guide.MAX_EXPANDED + 1, "expansion limits"),
        ):
            with self.subTest(field=field, value=value):
                content = bytearray(self.original)
                position = content.index(b"PK\x01\x02")
                struct.pack_into("<" + format_, content, position + field, value)
                self.path.write_bytes(content)
                with self.assertRaisesRegex(ValueError, message):
                    self.inspect()
        with zipfile.ZipFile(self.path, "w") as archive:
            for index in range(guide.MAX_ENTRIES + 1):
                archive.writestr(f"file-{index}", b"")
        with self.assertRaisesRegex(ValueError, "expansion limits"):
            self.inspect()
        self.path.write_bytes(b"")
        with self.assertRaisesRegex(ValueError, "bounded regular"):
            self.inspect()
        with self.path.open("wb") as output:
            output.truncate(guide.MAX_ARCHIVE + 1)
        with self.assertRaisesRegex(ValueError, "bounded regular"):
            self.inspect()
        self.path.unlink()
        self.path.symlink_to(self.tree / "index.html")
        with self.assertRaisesRegex(ValueError, "bounded regular"):
            self.inspect()

    def test_manifest_and_inventory_are_complete_and_exact(self) -> None:
        """Reject malformed or mismatched provenance, omitted files, and inconsistent routes."""
        for metadata in (
            None,
            [],
            {},
            {**self.metadata, "schemaVersion": True},
            {**self.metadata, "dirty": "false"},
            {**self.metadata, "sourceDigest": None},
            {**self.metadata, "sourceDigest": "not-a-hash"},
            {**self.metadata, "releaseCommit": "a" * 40},
        ):
            with self.subTest(metadata=metadata):
                self.archive(metadata)
                with self.assertRaisesRegex(ValueError, "expected clean"):
                    self.inspect()
        for changes in (
            {"files": []},
            {"files": {}},
            {"files": {**self.metadata["files"], "absent": "0" * 64}},
            {"files": {**self.metadata["files"], "index.html": None}},
            {"files": {**self.metadata["files"], "index.html": "bad"}},
            {"routes": ["wrong/"]},
        ):
            self.archive({**self.metadata, **changes})
            with self.assertRaisesRegex(ValueError, "complete-file inventory"):
                self.inspect()
        files = {name: (self.tree / name).read_bytes() for name in self.metadata["files"] if name != "index.html"}
        metadata = {**self.metadata, "files": {name: guide.digest(self.tree / name) for name in files}}
        self.archive(metadata, files=files)
        with self.assertRaisesRegex(ValueError, "complete-file inventory"):
            self.inspect()
        with zipfile.ZipFile(self.path, "w") as archive:
            archive.writestr("index.html", b"no manifest")
        with self.assertRaisesRegex(ValueError, "Missing or oversized"):
            self.inspect()
        with zipfile.ZipFile(self.path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(guide.MANIFEST, b" " * (2 * 1024 * 1024 + 1))
        with self.assertRaisesRegex(ValueError, "Missing or oversized"):
            self.inspect()

    def test_changed_contents_never_install_partial_output(self) -> None:
        """Checksum failure leaves no destination and preserves prior already published output."""
        metadata = deepcopy(self.metadata)
        metadata["files"]["index.html"] = "0" * 64
        self.archive(metadata)
        destination = self.directory / "output/1.2.3"
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            self.inspect(destination=destination)
        self.assertFalse(destination.exists())
        self.assertEqual(list(destination.parent.iterdir()), [])

    def test_verification_requires_exact_publisher_identity(self) -> None:
        """The external verifier is pinned to the docs workflow and reviewed main revision."""
        metadata = guide.verify_files(
            self.root, self.path, self.version, self.release_commit, self.builder, guide.REPOSITORY
        )
        self.assertEqual(metadata, self.metadata)
        arguments = json.loads((self.root / "gh-arguments.json").read_text())
        for flag, expected in (
            ("--repo", guide.REPOSITORY),
            ("--hostname", "github.com"),
            ("--signer-workflow", guide.REPOSITORY + "/.github/workflows/docs-archive.yml"),
            ("--source-ref", "refs/heads/main"),
            ("--source-digest", self.builder),
            ("--signer-digest", self.builder),
            ("--predicate-type", "https://slsa.dev/provenance/v1"),
        ):
            self.assertEqual(arguments[arguments.index(flag) + 1], expected)
        self.assertIn("--deny-self-hosted-runners", arguments)
        (self.root / "attestation-fails").touch()
        with self.assertRaises(subprocess.CalledProcessError):
            guide.verify_files(self.root, self.path, self.version, self.release_commit, self.builder, guide.REPOSITORY)

    def test_verification_rejects_missing_invalid_or_redirected_companions(self) -> None:
        """A manifest alone cannot establish a trusted publication."""
        checksum = Path(str(self.path) + ".sha256")
        bundle = Path(str(self.path) + ".sigstore.json")
        for companion, contents in (
            (checksum, b"bad"),
            (checksum, b"x" * 257),
            (bundle, b""),
            (bundle, b"x" * (guide.MAX_BUNDLE + 1)),
        ):
            self.companions()
            companion.write_bytes(contents)
            with self.assertRaises(ValueError):
                guide.verify_files(
                    self.root, self.path, self.version, self.release_commit, self.builder, guide.REPOSITORY
                )
        for companion in (checksum, bundle):
            self.companions()
            companion.unlink()
            with self.assertRaises(FileNotFoundError):
                guide.verify_files(
                    self.root, self.path, self.version, self.release_commit, self.builder, guide.REPOSITORY
                )
            companion.symlink_to(self.tree / "index.html")
            with self.assertRaises(ValueError):
                guide.verify_files(
                    self.root, self.path, self.version, self.release_commit, self.builder, guide.REPOSITORY
                )
            companion.unlink()
        self.companions()
        with self.assertRaisesRegex(ValueError, "explicit GitHub repository"):
            guide.verify_files(self.root, self.path, self.version, self.release_commit, self.builder, "invalid")

    def test_catalog_requires_reviewed_snapshot_and_complete_pinned_descriptors(self) -> None:
        """No missing catalog, wrong source, or invalid descriptor can imply a fallback build."""
        self.assertEqual(guide.catalog_entry(self.root, self.entry), self.record)
        for value in ([], {}, {"schemaVersion": True}, {"schemaVersion": 2}):
            self.catalog(value)
            with self.assertRaisesRegex(ValueError, "catalog schema"):
                guide.catalog_entry(self.root, self.entry)
        for archives in (None, [], {}, {self.version: None}):
            self.catalog({"schemaVersion": 1, "archives": archives})
            with self.assertRaisesRegex(ValueError, "before retiring"):
                guide.catalog_entry(self.root, self.entry)
        original = deepcopy(self.record)
        for change in (
            {"builderCommit": None},
            {"builderCommit": "invalid"},
            {"version": "9.9.9"},
            {"releaseCommit": "a" * 40},
            {"documentationCommit": "a" * 40},
            {"publication": "v1.2.3"},
            {"sourceDigest": "0" * 64},
            {"assets": None},
            {"assets": []},
        ):
            self.record = {**original, **change}
            self.catalog()
            with self.assertRaises(ValueError):
                guide.catalog_entry(self.root, self.entry)
        for change in (
            None,
            {"name": "other"},
            {"url": "https://example.com/asset"},
            {"size": True},
            {"size": 0},
            {"size": guide.MAX_ARCHIVE + 1},
            {"sha256": None},
            {"sha256": "bad"},
        ):
            self.record = deepcopy(original)
            self.record["assets"][0] = None if change is None else {**self.record["assets"][0], **change}
            self.catalog()
            with self.assertRaisesRegex(ValueError, "asset descriptor"):
                guide.catalog_entry(self.root, self.entry)
        self.record = original
        self.catalog()
        pending = self.entry.copy()
        pending["sourceCommit"] = None
        with self.assertRaises(ValueError):
            guide.catalog_entry(self.root, pending)

    def test_restore_verifies_downloads_and_installs_exact_output(self) -> None:
        """The consumer authenticates all bytes before installing a complete guide tree."""
        destination = self.directory / "composed/1.2.3"
        with patch("urllib.request.urlopen", side_effect=self.download):
            self.assertEqual(guide.restore(self.root, self.entry, destination), self.metadata)
        self.assertEqual(guide.inventory(destination), guide.inventory(self.tree))

    def test_download_failure_or_mismatched_snapshot_never_installs(self) -> None:
        """Wrong size, wrong digest, and a different attested snapshot fail closed."""
        destination = self.directory / "composed/1.2.3"
        with patch("urllib.request.urlopen", side_effect=OSError("network unavailable")):
            with self.assertRaisesRegex(OSError, "network unavailable"):
                guide.restore(self.root, self.entry, destination)
        self.assertFalse(destination.exists())
        for content in (b"truncated", b"x" * self.path.stat().st_size):
            with patch("urllib.request.urlopen", return_value=io.BytesIO(content)):
                with self.assertRaisesRegex(ValueError, "pinned size or checksum"):
                    guide.restore(self.root, self.entry, destination)
            self.assertFalse(destination.exists())
        metadata = {**self.metadata, "sourceDigest": "0" * 64}
        self.archive(metadata)
        self.companions()
        self.record = self.publication()
        self.catalog()
        with patch("urllib.request.urlopen", side_effect=self.download):
            with self.assertRaisesRegex(ValueError, "differs from the reviewed catalog"):
                guide.restore(self.root, self.entry, destination)
        self.assertFalse(destination.exists())

    def test_cli_checks_and_verifies_the_working_snapshot(self) -> None:
        """CLI success includes source agreement, while changed source and invalid arguments fail."""
        arguments = (
            str(self.path),
            "--version",
            self.version,
            "--release-commit",
            self.release_commit,
            "--builder-commit",
            self.builder,
        )
        for action in ("check", "verify"):
            result = invoke(self.root, "guide_archive.py", action, *arguments, env=self.environment)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn(f"Guide archive {action} passed", result.stdout)
        (self.snapshot / "index.md").write_text("different snapshot")
        result = invoke(self.root, "guide_archive.py", "check", *arguments, env=self.environment)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("does not match", result.stderr)
        result = invoke(self.root, "guide_archive.py", "check", env=self.environment)
        self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    unittest.main()
