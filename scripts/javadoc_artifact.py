#!/usr/bin/env python3
"""Validate and consume release Javadoc with exact source and publisher provenance."""

import argparse
import hashlib
import re
import shutil
import stat
import subprocess
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path, PurePosixPath
from typing import TYPE_CHECKING

from javadoc_cache import REQUIRED

if TYPE_CHECKING:
    from docs_release import ReleaseAsset, ReleaseEntry, ReleaseRecord

REPOSITORY = "cs1302uga/cs1302-tracer"
MANIFEST = "META-INF/MANIFEST.MF"
MAX_ARCHIVE = 64 * 1024 * 1024
MAX_EXPANDED = 256 * 1024 * 1024
MAX_MEMBER = 32 * 1024 * 1024
MAX_BUNDLE = 4 * 1024 * 1024
MAX_ENTRIES = 10000
VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\Z")


def identity(version: str, commit: str) -> None:
    """Require a stable version and full source revision for published documentation."""
    if not VERSION.fullmatch(version) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Release Javadoc requires a stable x.y.z version and full commit SHA")


def filenames(version: str) -> tuple[str, str, str]:
    """Name the Javadoc artifact and its two explicitly published companions."""
    jar = f"cs1302-tracer-{version}-javadoc.jar"
    return jar, jar + ".sha256", jar + ".sigstore.json"


def requires_artifact(root: Path, commit: str) -> bool:
    """Read the capability declared by the exact historical source, never today's POM."""
    pom = subprocess.check_output(["git", "show", commit + ":pom.xml"], cwd=root, stderr=subprocess.PIPE)
    marker = ET.fromstring(pom).find("./{*}properties/{*}release.javadoc.schema")
    if marker is None:
        return False
    if marker.text != "1":
        raise ValueError("Unsupported release Javadoc schema in tagged POM")
    return True


def digest(path: Path) -> str:
    """Hash a regular local file without trusting a cached verification result."""
    if not stat.S_ISREG(path.lstat().st_mode):
        raise ValueError("Release Javadoc inputs must be regular files")
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def manifest_values(content: bytes) -> dict[str, str]:
    """Parse the main JAR manifest section, including folded UTF-8 attributes."""
    physical = content.replace(b"\r\n", b"\n").split(b"\n")
    logical: list[bytes] = []
    for line in physical:
        if not line:
            break
        if line.startswith(b" "):
            if not logical:
                raise ValueError("Orphan continuation in Javadoc manifest")
            logical[-1] += line[1:]
        else:
            logical.append(line)
    values: dict[str, str] = {}
    for line in logical:
        name, separator, value = line.decode("utf-8").partition(": ")
        key = name.lower()
        if not separator or not re.fullmatch(r"[a-z0-9_-]+", key) or key in values or not value.strip():
            raise ValueError("Invalid or duplicate Javadoc manifest attribute")
        values[key] = value
    return values


def validate_archive(path: Path, version: str, commit: str, destination: Path | None = None) -> dict[str, str]:
    """Verify clean source identity and safe complete contents before optional extraction."""
    identity(version, commit)
    if not stat.S_ISREG(path.lstat().st_mode) or path.stat().st_size > MAX_ARCHIVE:
        raise ValueError("Javadoc JAR must be a bounded regular file")
    try:
        with zipfile.ZipFile(path) as archive:
            members = archive.infolist()
            if len(members) > MAX_ENTRIES or sum(item.file_size for item in members) > MAX_EXPANDED:
                raise ValueError("Javadoc archive exceeds expansion limits")
            seen: set[str] = set()
            files: set[str] = set()
            for item in members:
                name = item.filename.rstrip("/")
                relative = PurePosixPath(name)
                mode = stat.S_IFMT(item.external_attr >> 16)
                expected = stat.S_IFDIR if item.is_dir() else stat.S_IFREG
                if (
                    not name
                    or relative.is_absolute()
                    or any(part in ("", ".", "..") for part in name.split("/"))
                    or "\\" in name
                    or ":" in name
                    or any(ord(char) < 32 for char in name)
                    or item.orig_filename != item.filename
                    or name.casefold() in seen
                    or mode not in (0, expected)
                    or item.flag_bits & 1
                    or item.file_size > MAX_MEMBER
                ):
                    raise ValueError("Unsafe or duplicate Javadoc archive entry")
                seen.add(name.casefold())
                if not item.is_dir():
                    files.add(name)
            if not {*REQUIRED, MANIFEST}.issubset(files):
                raise ValueError("Javadoc archive lacks required API assets or manifest")
            folded_files = {name.casefold() for name in files}
            if any(parent.as_posix() in folded_files for name in seen for parent in PurePosixPath(name).parents):
                raise ValueError("Javadoc archive contains conflicting file and directory paths")
            if archive.getinfo(MANIFEST).file_size > 65536:
                raise ValueError("Javadoc manifest exceeds size limit")
            values = manifest_values(archive.read(MANIFEST))
            expected_values = {
                "manifest-version": "1.0",
                "code-tracer-javadoc-schema": "1",
                "code-tracer-version": version,
                "code-tracer-commit": commit,
                "code-tracer-dirty": "false",
            }
            if any(values.get(key) != value for key, value in expected_values.items()):
                raise ValueError("Javadoc metadata must identify the expected clean release commit")
            for name in ("javadoc-plugin", "build-jdk", "build-jdk-vendor", "build-maven"):
                value = values.get("code-tracer-" + name, "")
                if not value or "${" in value:
                    raise ValueError("Javadoc metadata lacks resolved generator/toolchain identity")
            # Read every member to check its CRC even when the caller needs no extraction.
            if destination is None:
                for item in members:
                    with archive.open(item) as stream:
                        shutil.copyfileobj(stream, _Discard())
            else:
                destination.parent.mkdir(parents=True, exist_ok=True)
                with tempfile.TemporaryDirectory(prefix=".release-javadoc-", dir=destination.parent) as temporary:
                    staged = Path(temporary) / "api"
                    staged.mkdir()
                    for item in members:
                        target = staged / item.filename
                        if item.is_dir():
                            target.mkdir(parents=True, exist_ok=True)
                        else:
                            target.parent.mkdir(parents=True, exist_ok=True)
                            with archive.open(item) as source, target.open("xb") as output:
                                shutil.copyfileobj(source, output)
                    staged.rename(destination)
            return values
    except (zipfile.BadZipFile, UnicodeError, NotImplementedError) as error:
        raise ValueError("Invalid release Javadoc archive") from error


class _Discard:
    """Consume decompressed bytes without retaining entire documentation files."""

    def write(self, data: bytes) -> int:
        """Accept a chunk read for CRC validation."""
        return len(data)


def verify_attestation(
    root: Path, path: Path, version: str, commit: str, repository: str, bundle: Path | None = None
) -> None:
    """Enforce GitHub-certified workflow, source commit, tag, and hosted-runner identity."""
    identity(version, commit)
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Attestation verification requires an explicit GitHub repository")
    subprocess.run(
        [
            "gh",
            "attestation",
            "verify",
            str(path),
            "--bundle",
            str(bundle or Path(str(path) + ".sigstore.json")),
            "--hostname",
            "github.com",
            "--repo",
            repository,
            "--signer-workflow",
            repository + "/.github/workflows/build.yml",
            "--signer-digest",
            commit,
            "--source-digest",
            commit,
            "--source-ref",
            "refs/tags/v" + version,
            "--deny-self-hosted-runners",
            "--predicate-type",
            "https://slsa.dev/provenance/v1",
        ],
        cwd=root,
        check=True,
        timeout=120,
    )


def verify_release_files(root: Path, path: Path, version: str, commit: str, repository: str) -> None:
    """Verify companion checksums and publisher identity for the actual released bytes."""
    validate_archive(path, version, commit)
    checksum = Path(str(path) + ".sha256")
    bundle = Path(str(path) + ".sigstore.json")
    if not stat.S_ISREG(checksum.lstat().st_mode) or checksum.stat().st_size > 256:
        raise ValueError("Invalid Javadoc checksum file")
    if checksum.read_text() != f"{digest(path)}  {path.name}\n":
        raise ValueError("Release Javadoc checksum mismatch")
    if not stat.S_ISREG(bundle.lstat().st_mode) or not 0 < bundle.stat().st_size <= MAX_BUNDLE:
        raise ValueError("Invalid Javadoc attestation bundle")
    verify_attestation(root, path, version, commit, repository, bundle)


def _matches(directory: Path, assets: list[ReleaseAsset]) -> bool:
    """Treat missing or corrupt local download copies as replaceable data."""
    try:
        if not stat.S_ISDIR(directory.lstat().st_mode):
            return False
        return all(
            (directory / asset["name"]).stat().st_size == asset["size"]
            and digest(directory / asset["name"]) == asset["digest"].removeprefix("sha256:")
            for asset in assets
        )
    except OSError, ValueError:
        return False


def _store(directory: Path, source: Path) -> None:
    """Replace a local bundle only after all downloaded files have been verified."""
    try:
        with tempfile.TemporaryDirectory(prefix=".release-store-", dir=directory.parent) as temporary:
            staged = Path(temporary) / "bundle"
            shutil.copytree(source, staged)
            backup = Path(temporary) / "previous"
            if directory.exists() or directory.is_symlink():
                directory.rename(backup)
            try:
                staged.rename(directory)
            except OSError:
                if backup.exists():
                    backup.rename(directory)
                raise
    except OSError:
        print("Release Javadoc cache write failed; verified documentation remains available.")


def restore(root: Path, entry: ReleaseEntry, releases: list[ReleaseRecord], destination: Path) -> str | None:
    """Prefer authenticated published Javadoc; only pre-capability releases may fall back."""
    version = entry["name"]
    commit = entry["sourceCommit"]
    if commit is None:
        raise ValueError("Published Javadoc requires source provenance")
    identity(version, commit)
    required = requires_artifact(root, commit)
    records = [record for record in releases if record["tag_name"] in (version, "v" + version)]
    if len(records) != 1:
        raise ValueError("Release Javadoc requires one matching published release record")
    record = records[0]
    if record.get("draft") or record.get("prerelease"):
        raise ValueError("Release Javadoc requires a published stable release")
    names = filenames(version)
    advertised = [asset for asset in record.get("assets", []) if asset.get("name") in names]
    if not advertised and not required:
        return None
    if len(advertised) != 3 or {asset["name"] for asset in advertised} != set(names):
        raise ValueError("Published release is missing or duplicates required Javadoc artifacts")
    assets = sorted(advertised, key=lambda asset: names.index(asset["name"]))
    for asset, limit in zip(assets, (MAX_ARCHIVE, 256, MAX_BUNDLE), strict=True):
        expected_url = f"https://github.com/{REPOSITORY}/releases/download/v{version}/{asset['name']}"
        if (
            asset.get("browser_download_url") != expected_url
            or type(asset.get("size")) is not int
            or not 0 < asset["size"] <= limit
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", asset.get("digest", ""))
        ):
            raise ValueError("Invalid release Javadoc asset descriptor")
    cache: Path | None = root / "website/.cache/release-javadoc"
    try:
        for directory in (root / "website", cache.parent, cache):
            directory.mkdir(exist_ok=True)
            if not stat.S_ISDIR(directory.lstat().st_mode):
                raise OSError("Redirected cache directory")
        cache = cache / assets[0]["digest"].removeprefix("sha256:")
    except OSError:
        cache = None
        print("Release Javadoc cache unavailable; using verified downloads directly.")
    if cache is not None and _matches(cache, assets):
        # Verify a private copy: a shared cache can change while gh checks its signature.
        with tempfile.TemporaryDirectory(prefix="tracer-verified-javadoc-") as temporary:
            copied = Path(temporary) / "bundle"
            try:
                shutil.copytree(cache, copied, symlinks=True)
            except OSError:
                pass  # A disappearing or unreadable local copy can be downloaded again.
            else:
                if _matches(copied, assets):
                    verify_release_files(root, copied / names[0], version, commit, REPOSITORY)
                    validate_archive(copied / names[0], version, commit, destination)
                    return "release-asset-cache"
    with tempfile.TemporaryDirectory(prefix="tracer-release-javadoc-") as temporary:
        downloaded = Path(temporary)
        for asset in assets:
            path = downloaded / asset["name"]
            with (
                urllib.request.urlopen(asset["browser_download_url"], timeout=30) as response,
                path.open("xb") as output,
            ):
                remaining = asset["size"] + 1
                while remaining:
                    chunk = response.read(min(1024 * 1024, remaining))
                    if not chunk:
                        break
                    output.write(chunk)
                    remaining -= len(chunk)
        if not _matches(downloaded, assets):
            raise ValueError("Downloaded Javadoc artifacts differ from published sizes or checksums")
        verify_release_files(root, downloaded / names[0], version, commit, REPOSITORY)
        validate_archive(downloaded / names[0], version, commit, destination)
        if cache is not None:
            _store(cache, downloaded)
    return "release-asset"


def main() -> None:
    """Check local release archives or verify their published companion evidence."""
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("action", choices=("check", "verify"))
    parser.add_argument("path", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--repository", default=REPOSITORY)
    args = parser.parse_args()
    path = args.path.absolute()
    if args.action == "check":
        validate_archive(path, args.version, args.commit)
        Path(str(path) + ".sha256").write_text(f"{digest(path)}  {path.name}\n")
    else:
        verify_release_files(Path.cwd(), path, args.version, args.commit, args.repository)
    print(f"Release Javadoc {args.action} passed: {path.name}")


if __name__ == "__main__":
    main()
