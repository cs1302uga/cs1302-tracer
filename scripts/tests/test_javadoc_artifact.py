"""Exercise real release archives with substituted network and attestation boundaries."""

import io
import json
import os
import shutil
import stat
import struct
import subprocess
import tempfile
import unittest
import warnings
import zipfile
from contextlib import redirect_stdout
from pathlib import Path
from typing import TYPE_CHECKING, cast
from unittest.mock import patch

from scripts import javadoc_artifact as artifact

from tests.support import copy_scripts, executable, invoke, tool_environment

if TYPE_CHECKING:
    from docs_release import ReleaseEntry, ReleaseRecord


class JavadocArtifactTests(unittest.TestCase):
    """Keep malformed or unauthenticated release output out of the documentation site."""

    def setUp(self) -> None:
        """Create real tagged-source history, a JAR, and observable external tools."""
        temporary = tempfile.TemporaryDirectory(prefix="release javadoc ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        copy_scripts(self.root)
        self.environment = tool_environment(self.root)
        environment = patch.dict(os.environ, self.environment)
        environment.start()
        self.addCleanup(environment.stop)
        self.git("init", "-q")
        self.git("config", "user.name", "Artifact Test")
        self.git("config", "user.email", "artifact@example.invalid")
        self.git("config", "core.hooksPath", "/dev/null")
        self.version = "1.2.3"
        self.commit = self.source("1")
        self.assets = self.root / "assets"
        self.assets.mkdir()
        self.jar = self.assets / artifact.filenames(self.version)[0]
        executable(
            self.root / "bin/gh",
            """
import json, pathlib, sys
root = pathlib.Path.cwd()
(root / 'gh-arguments.json').write_text(json.dumps(sys.argv[1:]))
mutation = root / 'mutate-cache-during-attestation'
if mutation.exists():
    pathlib.Path(mutation.read_text()).write_bytes(b'changed after copy')
sys.exit(1 if (root / 'attestation-fails').exists() else 0)
""",
        )
        self.archive()
        self.companions()

    def git(self, *arguments: str) -> str:
        """Run real Git without inherited signing or hooks."""
        return subprocess.check_output(
            ["git", "-c", "commit.gpgsign=false", *arguments], cwd=self.root, text=True, stderr=subprocess.PIPE
        ).strip()

    def source(self, schema: str | None) -> str:
        """Create a historical POM declaring or predating the release artifact contract."""
        marker = "" if schema is None else f"<release.javadoc.schema>{schema}</release.javadoc.schema>"
        (self.root / "pom.xml").write_text(
            f"<project><version>{self.version}</version><properties>{marker}</properties></project>"
        )
        self.git("add", "pom.xml")
        self.git("commit", "-qm", "historical POM", "--allow-empty")
        return self.git("rev-parse", "HEAD")

    def metadata(self, **overrides: str) -> bytes:
        """Emit the producer's manifest attributes, with optional malformed values."""
        values = {
            "Manifest-Version": "1.0",
            "Code-Tracer-Javadoc-Schema": "1",
            "Code-Tracer-Version": self.version,
            "Code-Tracer-Commit": self.commit,
            "Code-Tracer-Dirty": "false",
            "Code-Tracer-Javadoc-Plugin": "3.12.0",
            "Code-Tracer-Build-Jdk": "21.0.9",
            "Code-Tracer-Build-Jdk-Vendor": "Eclipse Adoptium",
            "Code-Tracer-Build-Maven": "3.10.0",
        }
        values.update(overrides)
        return ("\r\n".join(f"{key}: {value}" for key, value in values.items()) + "\r\n\r\n").encode()

    def archive(
        self, metadata: bytes | None = None, extras: tuple[tuple[str | zipfile.ZipInfo, bytes], ...] = ()
    ) -> None:
        """Write genuine ZIP structures rather than substituting archive parsing."""
        with warnings.catch_warnings(), zipfile.ZipFile(self.jar, "w") as archive:
            warnings.simplefilter("ignore", UserWarning)
            archive.writestr(artifact.MANIFEST, metadata if metadata is not None else self.metadata())
            for name in artifact.REQUIRED:
                archive.writestr(name, b"api")
            archive.writestr("resources/", b"")
            archive.writestr("resources/style.css", b"body {}")
            for name, content in extras:
                archive.writestr(name, content)

    def companions(self) -> None:
        """Generate the same adjacent evidence filenames used by the publisher."""
        Path(str(self.jar) + ".sha256").write_text(f"{artifact.digest(self.jar)}  {self.jar.name}\n")
        Path(str(self.jar) + ".sigstore.json").write_text('{"external": "attestation fixture"}')

    def records(self) -> list[ReleaseRecord]:
        """Describe actual fixture bytes as the release REST API does."""
        return [
            {
                "tag_name": "v" + self.version,
                "assets": [
                    {
                        "name": name,
                        "size": (self.assets / name).stat().st_size,
                        "digest": "sha256:" + artifact.digest(self.assets / name),
                        "browser_download_url": f"https://github.com/{artifact.REPOSITORY}/releases/download/v{self.version}/{name}",
                    }
                    for name in artifact.filenames(self.version)
                ],
            }
        ]

    def entry(self) -> ReleaseEntry:
        """Provide the already-selected source fields consumed by the artifact boundary."""
        return {
            "name": self.version,
            "sourceCommit": self.commit,
            "label": self.version,
            "docsPath": self.version,
            "docsUrl": "/cs1302-tracer/" + self.version,
            "javadocPath": "api/" + self.version,
            "javadocUrl": "/cs1302-tracer/api/" + self.version,
            "banner": "none",
        }

    def download(self, url: str, *, timeout: int) -> io.BytesIO:
        """Supply real fixture contents through the external HTTP boundary."""
        self.assertEqual(timeout, 30)
        return io.BytesIO((self.assets / url.rsplit("/", 1)[-1]).read_bytes())

    def test_cli_checks_and_verifies_exact_publisher_identity(self) -> None:
        """Keep producer checks and consumer attestation policy on the same implementation."""
        args = [str(self.jar), "--version", self.version, "--commit", self.commit]
        result = invoke(self.root, "javadoc_artifact.py", "check", *args, env=self.environment)
        self.assertEqual(result.returncode, 0, result.stderr)
        result = invoke(self.root, "javadoc_artifact.py", "verify", *args, env=self.environment)
        self.assertEqual(result.returncode, 0, result.stderr)
        expected = [
            "attestation",
            "verify",
            str(self.jar),
            "--bundle",
            str(self.jar) + ".sigstore.json",
            "--hostname",
            "github.com",
            "--repo",
            artifact.REPOSITORY,
            "--signer-workflow",
            artifact.REPOSITORY + "/.github/workflows/build.yml",
            "--signer-digest",
            self.commit,
            "--source-digest",
            self.commit,
            "--source-ref",
            "refs/tags/v" + self.version,
            "--deny-self-hosted-runners",
            "--predicate-type",
            "https://slsa.dev/provenance/v1",
        ]
        self.assertEqual(json.loads((self.root / "gh-arguments.json").read_text()), expected)
        artifact.verify_attestation(self.root, self.jar, self.version, self.commit, artifact.REPOSITORY)
        (self.root / "attestation-fails").touch()
        with self.assertRaises(subprocess.CalledProcessError):
            artifact.verify_release_files(self.root, self.jar, self.version, self.commit, artifact.REPOSITORY)
        with self.assertRaisesRegex(ValueError, "explicit GitHub"):
            artifact.verify_attestation(self.root, self.jar, self.version, self.commit, "evil/../repo")

    def test_manifest_folding_identity_and_toolchain_validation(self) -> None:
        """Require exact clean release identity while handling standard manifest folding."""
        values = artifact.manifest_values(b"Long-Value: ab\r\n cd\r\n\r\nName: other-section\r\n")
        self.assertEqual(values, {"long-value": "abcd"})
        self.assertEqual(artifact.manifest_values(b"Value: complete"), {"value": "complete"})
        for content in (b" orphan\n", b"No colon\n", b"Bad.Name: value\n", b"Empty: \n", b"X: a\nx: b\n"):
            with self.subTest(content=content), self.assertRaises(ValueError):
                artifact.manifest_values(content)
        for key, value in (
            ("Code-Tracer-Version", "2.0.0"),
            ("Code-Tracer-Commit", "0" * 40),
            ("Code-Tracer-Dirty", "true"),
            ("Code-Tracer-Javadoc-Schema", "2"),
            ("Code-Tracer-Build-Maven", "${maven.version}"),
        ):
            self.archive(self.metadata(**{key: value}))
            with self.subTest(key=key), self.assertRaises(ValueError):
                artifact.validate_archive(self.jar, self.version, self.commit)
        self.archive(self.metadata().replace(b"Code-Tracer-Build-Jdk: 21.0.9\r\n", b""))
        with self.assertRaisesRegex(ValueError, "toolchain"):
            artifact.validate_archive(self.jar, self.version, self.commit)
        self.archive(b"Invalid: \xff\n")
        with self.assertRaisesRegex(ValueError, "Invalid release"):
            artifact.validate_archive(self.jar, self.version, self.commit)
        for version, commit in (("1.2.3-SNAPSHOT", self.commit), (self.version, "abc")):
            with self.assertRaises(ValueError):
                artifact.identity(version, commit)

    def test_unsafe_members_are_rejected_before_destination_changes(self) -> None:
        """Reject aliases, traversal, special files, duplicate names, and path conflicts."""
        destination = self.root / "out/api"
        destination.mkdir(parents=True)
        (destination / "previous").write_text("keep")
        special = zipfile.ZipInfo("link")
        special.external_attr = (stat.S_IFLNK | 0o777) << 16
        fifo = zipfile.ZipInfo("fifo")
        fifo.external_attr = (stat.S_IFIFO | 0o600) << 16
        cases = [
            (("/", b""),),
            (("../escape", b"x"),),
            (("/absolute", b"x"),),
            (("x//y", b"x"),),
            (("x/./y", b"x"),),
            (("x\\y", b"x"),),
            (("C:drive", b"x"),),
            (("control\x01", b"x"),),
            (("INDEX.HTML", b"x"),),
            (("index.html", b"x"),),
            ((special, b"target"),),
            ((fifo, b""),),
            (("file", b"x"), ("file/child", b"x")),
            (("FILE", b"x"), ("file/child", b"x")),
        ]
        for extras in cases:
            self.archive(extras=extras)
            with self.subTest(extras=extras), self.assertRaises(ValueError):
                artifact.validate_archive(self.jar, self.version, self.commit, destination)
            self.assertEqual(list(destination.iterdir()), [destination / "previous"])
        self.archive()
        shutil.rmtree(destination)
        artifact.validate_archive(self.jar, self.version, self.commit, destination)
        self.assertEqual((destination / "resources/style.css").read_bytes(), b"body {}")

    def test_archive_size_count_crc_and_required_asset_checks(self) -> None:
        """Use real oversized or malformed structures without decompressing unbounded data."""
        self.jar.write_bytes(b"not a zip")
        with self.assertRaises(ValueError):
            artifact.validate_archive(self.jar, self.version, self.commit)
        self.archive()
        self.jar.write_bytes(self.jar.read_bytes().replace(b"body {}", b"body []", 1))
        with self.assertRaisesRegex(ValueError, "Invalid release"):
            artifact.validate_archive(self.jar, self.version, self.commit)
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("index.html", b"only index")
        with self.assertRaisesRegex(ValueError, "required"):
            artifact.validate_archive(self.jar, self.version, self.commit)
        self.archive(b"X: " + b"a" * 65536)
        with self.assertRaisesRegex(ValueError, "manifest exceeds"):
            artifact.validate_archive(self.jar, self.version, self.commit)
        self.archive(extras=tuple((f"file-{number}", b"") for number in range(10000)))
        with self.assertRaisesRegex(ValueError, "expansion"):
            artifact.validate_archive(self.jar, self.version, self.commit)
        self.archive(extras=(("large", b"x" * (artifact.MAX_MEMBER + 1)),))
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            artifact.validate_archive(self.jar, self.version, self.commit)
        with self.jar.open("wb") as stream:
            stream.truncate(artifact.MAX_ARCHIVE + 1)
        with self.assertRaisesRegex(ValueError, "bounded regular"):
            artifact.validate_archive(self.jar, self.version, self.commit)
        self.jar.unlink()
        self.jar.symlink_to(self.root / "pom.xml")
        with self.assertRaises(ValueError):
            artifact.validate_archive(self.jar, self.version, self.commit)
        with self.assertRaises(ValueError):
            artifact.digest(self.jar)

    def test_malformed_zip_headers_are_rejected_without_extraction(self) -> None:
        """Inspect genuine headers for oversized expansion, encryption, and hidden names."""
        for field, value, message in (
            (24, artifact.MAX_EXPANDED + 1, "expansion"),
            (8, 1, "Unsafe"),
            (10, 99, "Invalid release"),
        ):
            self.archive()
            content = bytearray(self.jar.read_bytes())
            central = content.index(b"PK\x01\x02")
            struct.pack_into("<I" if field == 24 else "<H", content, central + field, value)
            self.jar.write_bytes(content)
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, message):
                artifact.validate_archive(self.jar, self.version, self.commit)
        self.archive()
        content = self.jar.read_bytes().replace(b"resources/style.css", b"resources/styl\x00.css")
        self.jar.write_bytes(content)
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            artifact.validate_archive(self.jar, self.version, self.commit)

    def test_companion_sizes_types_and_checksums_are_not_optional(self) -> None:
        """Never run the attestation command for absent or mismatched local evidence."""
        checksum = Path(str(self.jar) + ".sha256")
        bundle = Path(str(self.jar) + ".sigstore.json")
        for value in ("wrong", "x" * 257):
            checksum.write_text(value)
            with self.assertRaises(ValueError):
                artifact.verify_release_files(self.root, self.jar, self.version, self.commit, artifact.REPOSITORY)
        self.companions()
        for size in (0, artifact.MAX_BUNDLE + 1):
            with bundle.open("wb") as stream:
                stream.truncate(size)
            with self.assertRaisesRegex(ValueError, "bundle"):
                artifact.verify_release_files(self.root, self.jar, self.version, self.commit, artifact.REPOSITORY)
        self.companions()
        checksum.unlink()
        checksum.symlink_to(self.root / "pom.xml")
        with self.assertRaisesRegex(ValueError, "checksum file"):
            artifact.verify_release_files(self.root, self.jar, self.version, self.commit, artifact.REPOSITORY)
        checksum.unlink()
        self.companions()
        bundle.unlink()
        bundle.symlink_to(self.root / "pom.xml")
        with self.assertRaisesRegex(ValueError, "bundle"):
            artifact.verify_release_files(self.root, self.jar, self.version, self.commit, artifact.REPOSITORY)

    def test_release_capability_and_descriptor_failures_do_not_fall_back(self) -> None:
        """Only a genuine legacy release with no advertised artifact permits generation."""
        destination = self.root / "out"
        records = self.records()
        for field, value in (
            ("size", 0),
            ("size", True),
            ("size", artifact.MAX_ARCHIVE + 1),
            ("digest", "bad"),
            ("browser_download_url", "https://evil.invalid/file"),
        ):
            changed = self.records()
            cast("dict[str, object]", changed[0]["assets"][0])[field] = value
            with self.subTest(field=field, value=value), self.assertRaisesRegex(ValueError, "descriptor"):
                artifact.restore(self.root, self.entry(), changed, destination)
        for changed in (
            [],
            records + records,
            [{"tag_name": "v" + self.version}],
            [{**records[0], "draft": True}],
            [{**records[0], "prerelease": True}],
            [{**records[0], "assets": records[0]["assets"][:2]}],
        ):
            with self.subTest(records=changed), self.assertRaises(ValueError):
                artifact.restore(self.root, self.entry(), cast("list[ReleaseRecord]", changed), destination)
        with self.assertRaises(ValueError):
            artifact.restore(self.root, {**self.entry(), "sourceCommit": None}, records, destination)
        legacy = self.source(None)
        self.assertFalse(artifact.requires_artifact(self.root, legacy))
        self.assertIsNone(
            artifact.restore(
                self.root, {**self.entry(), "sourceCommit": legacy}, [{"tag_name": "v" + self.version}], destination
            )
        )
        unsupported = self.source("2")
        with self.assertRaisesRegex(ValueError, "Unsupported"):
            artifact.requires_artifact(self.root, unsupported)

    def test_download_cache_repair_limits_and_external_failures(self) -> None:
        """Reuse only verified bundles, repair corrupt copies, and preserve previous output."""
        records = self.records()
        destination = self.root / "out/api"
        with patch("urllib.request.urlopen", side_effect=self.download) as network:
            self.assertEqual(artifact.restore(self.root, self.entry(), records, destination), "release-asset")
            self.assertEqual(network.call_count, 3)
            shutil.rmtree(destination)
            self.assertEqual(artifact.restore(self.root, self.entry(), records, destination), "release-asset-cache")
            self.assertEqual(network.call_count, 3)
            shutil.rmtree(destination)
            cache = self.root / "website/.cache/release-javadoc" / artifact.digest(self.jar)
            (cache / self.jar.name).write_bytes(b"corrupt")
            self.assertEqual(artifact.restore(self.root, self.entry(), records, destination), "release-asset")
            self.assertEqual(network.call_count, 6)
        shutil.rmtree(destination)
        shutil.rmtree(cache)

        def oversized(url: str, *, timeout: int) -> io.BytesIO:
            content = self.download(url, timeout=timeout).getvalue()
            return io.BytesIO(content + b"xx" if url.endswith(".jar") else content)

        with patch("urllib.request.urlopen", side_effect=oversized):
            with self.assertRaisesRegex(ValueError, "Downloaded"):
                artifact.restore(self.root, self.entry(), records, destination)
        with patch("urllib.request.urlopen", side_effect=OSError("offline")):
            with self.assertRaisesRegex(OSError, "offline"):
                artifact.restore(self.root, self.entry(), records, destination)
        self.assertFalse(destination.exists())
        with patch("urllib.request.urlopen", side_effect=self.download):
            (self.root / "attestation-fails").touch()
            with self.assertRaises(subprocess.CalledProcessError):
                artifact.restore(self.root, self.entry(), records, destination)
        self.assertFalse(destination.exists())

    def test_local_bundle_corruption_and_redirects_trigger_verified_downloads(self) -> None:
        """Repair equal-size corruption, redirected files, and non-directory bundle entries."""
        records = self.records()
        destination = self.root / "out/api"
        cache = self.root / "website/.cache/release-javadoc" / artifact.digest(self.jar)
        with patch("urllib.request.urlopen", side_effect=self.download) as network:
            self.assertEqual(artifact.restore(self.root, self.entry(), records, destination), "release-asset")
            for corruption in ("same-size", "file-symlink", "directory-file", "directory-symlink"):
                shutil.rmtree(destination)
                if corruption == "same-size":
                    cached = cache / self.jar.name
                    content = bytearray(cached.read_bytes())
                    content[-1] ^= 1
                    cached.write_bytes(content)
                elif corruption == "file-symlink":
                    cached = cache / self.jar.name
                    cached.unlink()
                    cached.symlink_to(self.jar)
                else:
                    shutil.rmtree(cache)
                    if corruption == "directory-file":
                        cache.write_text("corrupt entry")
                    else:
                        cache.symlink_to(self.assets, target_is_directory=True)
                count = network.call_count
                with self.subTest(corruption=corruption):
                    self.assertEqual(artifact.restore(self.root, self.entry(), records, destination), "release-asset")
                    self.assertEqual(network.call_count, count + 3)
                    self.assertFalse(cache.is_symlink())
                    self.assertEqual((cache / self.jar.name).read_bytes(), self.jar.read_bytes())
                    self.assertEqual((destination / "resources/style.css").read_bytes(), b"body {}")

    def test_unavailable_cache_uses_verified_download_without_redirected_writes(self) -> None:
        """An obstructed or redirected cache must not block verified documentation output."""
        records = self.records()
        website = self.root / "website"
        outside = self.root / "outside"
        outside.mkdir()
        for obstruction in ("file", "symlink"):
            if obstruction == "file":
                website.write_text("not a cache directory")
            else:
                website.symlink_to(outside, target_is_directory=True)
            destination = self.root / ("output-" + obstruction)
            messages = io.StringIO()
            with (
                self.subTest(obstruction=obstruction),
                redirect_stdout(messages),
                patch("urllib.request.urlopen", side_effect=self.download) as network,
            ):
                self.assertEqual(artifact.restore(self.root, self.entry(), records, destination), "release-asset")
                self.assertEqual(network.call_count, 3)
            self.assertIn("cache unavailable", messages.getvalue())
            self.assertEqual(list(outside.iterdir()), [])
            self.assertEqual((destination / "index.html").read_bytes(), b"api")
            website.unlink()

    def test_cache_copy_failures_and_races_retry_verified_downloads(self) -> None:
        """Recheck private copies and keep shared-cache changes away from extracted content."""
        records = self.records()
        destination = self.root / "out/api"
        cache = self.root / "website/.cache/release-javadoc" / artifact.digest(self.jar)
        copy_tree = shutil.copytree
        with patch("urllib.request.urlopen", side_effect=self.download) as network:
            artifact.restore(self.root, self.entry(), records, destination)
            for failure in ("unreadable", "changed"):
                shutil.rmtree(destination)

                def changed_copy(source: Path, target: Path, *args, **kwargs) -> str:
                    if source == cache and failure == "unreadable":
                        raise OSError("shared cache disappeared")
                    copied = copy_tree(source, target, *args, **kwargs)
                    if source == cache:
                        (Path(target) / self.jar.name).write_bytes(b"changed during copy")
                    return str(copied)

                count = network.call_count
                with self.subTest(failure=failure), patch("shutil.copytree", side_effect=changed_copy):
                    self.assertEqual(artifact.restore(self.root, self.entry(), records, destination), "release-asset")
                self.assertEqual(network.call_count, count + 3)
                self.assertEqual((destination / "index.html").read_bytes(), b"api")
            shutil.rmtree(destination)
            (self.root / "mutate-cache-during-attestation").write_text(str(cache / self.jar.name))
            count = network.call_count
            self.assertEqual(artifact.restore(self.root, self.entry(), records, destination), "release-asset-cache")
            self.assertEqual(network.call_count, count)
            self.assertEqual((destination / "index.html").read_bytes(), b"api")
            self.assertEqual((cache / self.jar.name).read_bytes(), b"changed after copy")
            arguments = json.loads((self.root / "gh-arguments.json").read_text())
            self.assertTrue(Path(arguments[2]).parent.parent.name.startswith("tracer-verified-javadoc-"))

    def test_cache_install_failure_preserves_fresh_output_and_restores_previous_entry(self) -> None:
        """Exercise failed atomic renames both with and without an existing cached bundle."""
        original_rename = Path.rename

        def fail_install(path: Path, target: Path) -> Path:
            if path.name == "bundle" and path.parent.name.startswith(".release-store-"):
                raise OSError("cache storage unavailable")
            return original_rename(path, target)

        records = self.records()
        cache = self.root / "website/.cache/release-javadoc" / artifact.digest(self.jar)
        destination = self.root / "out/api"
        for previous in (False, True):
            if previous:
                with patch("urllib.request.urlopen", side_effect=self.download):
                    artifact.restore(self.root, self.entry(), records, destination)
                shutil.rmtree(destination)
                (cache / self.jar.name).write_bytes(b"previous corrupt bytes")
            messages = io.StringIO()
            with (
                self.subTest(previous=previous),
                redirect_stdout(messages),
                patch("urllib.request.urlopen", side_effect=self.download),
                patch.object(Path, "rename", new=fail_install),
            ):
                self.assertEqual(artifact.restore(self.root, self.entry(), records, destination), "release-asset")
            self.assertIn("cache write failed", messages.getvalue())
            self.assertEqual((destination / "index.html").read_bytes(), b"api")
            self.assertEqual(list(cache.parent.glob(".release-store-*")), [])
            if previous:
                self.assertEqual((cache / self.jar.name).read_bytes(), b"previous corrupt bytes")
            else:
                self.assertFalse(cache.exists())
            shutil.rmtree(destination)


if __name__ == "__main__":
    unittest.main()
