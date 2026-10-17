#!/usr/bin/env python3
"""Package and authenticate isolated guide applications without changing published routes."""

import argparse
import hashlib
import json
import re
import stat
import subprocess
import tempfile
import urllib.request
import zipfile
from pathlib import Path, PurePosixPath
from typing import TYPE_CHECKING, Any

from javadoc_artifact import (
    MAX_ARCHIVE,
    MAX_BUNDLE,
    MAX_ENTRIES,
    MAX_EXPANDED,
    MAX_MEMBER,
    REPOSITORY,
    digest,
    identity,
)

if TYPE_CHECKING:
    from docs_release import ReleaseEntry

MANIFEST = "guide-manifest.json"
BASE = "/cs1302-tracer/"
HEX = re.compile(r"[0-9a-f]{64}\Z")


def filenames(version: str) -> tuple[str, str, str]:
    """Name a guide application and its independently verifiable companions."""
    name = f"cs1302-tracer-{version}-guides.zip"
    return name, name + ".sha256", name + ".sigstore.json"


def inventory(tree: Path) -> dict[str, str]:
    """Hash a real tree, rejecting redirects instead of following them outside its root."""
    if not stat.S_ISDIR(tree.lstat().st_mode):
        raise ValueError("Guide input must be a real directory")
    files = {}
    for path in sorted(tree.rglob("*")):
        if path.is_symlink():
            raise ValueError("Guide trees cannot contain symbolic links")
        if path.is_file():
            files[path.relative_to(tree).as_posix()] = digest(path)
        elif not path.is_dir():
            raise ValueError("Guide trees can contain only regular files and directories")
    return files


def source_digest(root: Path, version: str) -> str:
    """Identify the reviewed snapshot and sidebar independently of the current renderer."""
    identity(version, "0" * 40)
    site = root / "website"
    files = {"docs/" + name: value for name, value in inventory(site / f"versioned_docs/version-{version}").items()}
    sidebar = site / f"versioned_sidebars/version-{version}-sidebars.json"
    if not stat.S_ISREG(sidebar.lstat().st_mode):
        raise ValueError("Guide sidebar must be a regular file")
    files["sidebar"] = digest(sidebar)
    # The archive's complete inventory and attested builder bind its frozen assets.
    # Unrelated future shared assets must not invalidate every historical snapshot.
    return hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()


def routes(files: dict[str, str]) -> list[str]:
    """Identify document routes independently of generated chunk names."""
    return sorted(
        name.removesuffix("index.html") for name in files if name == "index.html" or name.endswith("/index.html")
    )


def create(root: Path, entry: ReleaseEntry, tree: Path, output: Path) -> dict[str, Any]:
    """Create a local candidate; dirty candidates can be inspected but never published."""
    version, release_commit = entry["name"], entry["sourceCommit"]
    identity(version, release_commit or "")
    builder = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    identity(version, builder)
    dirty = bool(subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=normal"], cwd=root))
    files = inventory(tree)
    if "index.html" not in files or MANIFEST in files:
        raise ValueError("Guide tree requires an index and cannot supply its own provenance manifest")
    metadata = {
        "schemaVersion": 1,
        "version": version,
        "releaseCommit": release_commit,
        "sourceDigest": source_digest(root, version),
        "documentationCommit": builder,
        "builderCommit": builder,
        "dirty": dirty,
        "baseUrl": BASE + version + "/",
        "files": files,
        "routes": routes(files),
    }
    output.mkdir(parents=True, exist_ok=True)
    path = output / filenames(version)[0]
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in files:
            archive.write(tree / name, name)
        archive.writestr(MANIFEST, json.dumps(metadata, sort_keys=True) + "\n")
    Path(str(path) + ".sha256").write_text(f"{digest(path)}  {path.name}\n")
    return metadata


def safe_name(name: str) -> bool:
    """Allow only portable relative file paths in a deployable application."""
    return bool(name) and not (
        PurePosixPath(name).is_absolute()
        or any(part in ("", ".", "..") for part in name.split("/"))
        or "\\" in name
        or ":" in name
        or any(ord(char) < 32 for char in name)
    )


def inspect_archive(
    path: Path,
    version: str,
    release_commit: str,
    builder_commit: str,
    *,
    require_clean: bool = True,
    destination: Path | None = None,
) -> dict[str, Any]:
    """Validate identity, bounded ZIP contents, and every file before atomic extraction."""
    identity(version, release_commit)
    identity(version, builder_commit)
    if not stat.S_ISREG(path.lstat().st_mode) or not 0 < path.stat().st_size <= MAX_ARCHIVE:
        raise ValueError("Guide archive must be a bounded regular file")
    with zipfile.ZipFile(path) as archive:
        members = archive.infolist()
        if len(members) > MAX_ENTRIES or sum(item.file_size for item in members) > MAX_EXPANDED:
            raise ValueError("Guide archive exceeds expansion limits")
        names: set[str] = set()
        for item in members:
            name = item.filename
            if (
                not safe_name(name)
                or item.orig_filename != name
                or name.casefold() in names
                or stat.S_IFMT(item.external_attr >> 16) not in (0, stat.S_IFREG)
                or item.flag_bits & 1
                or item.file_size > MAX_MEMBER
            ):
                raise ValueError("Unsafe or duplicate guide archive entry")
            names.add(name.casefold())
        if any(parent.as_posix() in names for name in names for parent in PurePosixPath(name).parents):
            raise ValueError("Conflicting guide archive paths")
        if MANIFEST not in archive.namelist() or archive.getinfo(MANIFEST).file_size > 2 * 1024 * 1024:
            raise ValueError("Missing or oversized guide archive manifest")
        metadata = json.loads(archive.read(MANIFEST))
        expected = {
            "schemaVersion": 1,
            "version": version,
            "releaseCommit": release_commit,
            "documentationCommit": builder_commit,
            "builderCommit": builder_commit,
            "baseUrl": BASE + version + "/",
        }
        if (
            not isinstance(metadata, dict)
            or any(metadata.get(key) != value for key, value in expected.items())
            or type(metadata.get("schemaVersion")) is not int
            or type(metadata.get("dirty")) is not bool
            or (require_clean and metadata["dirty"])
            or not isinstance(metadata.get("sourceDigest"), str)
            or not HEX.fullmatch(metadata["sourceDigest"])
        ):
            raise ValueError("Guide archive must identify the expected clean documentation build")
        files = metadata.get("files")
        if (
            not isinstance(files, dict)
            or set(files) != set(archive.namelist()) - {MANIFEST}
            or "index.html" not in files
            or any(not isinstance(value, str) or not HEX.fullmatch(value) for value in files.values())
            or metadata.get("routes") != routes(files)
        ):
            raise ValueError("Guide archive has an invalid complete-file inventory")
        parent = destination.parent if destination is not None else None
        if parent is not None:
            parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".verified-guides-", dir=parent) as temporary:
            staged = Path(temporary) / "site"
            staged.mkdir()
            for name, expected_digest in files.items():
                content = archive.read(name)
                if hashlib.sha256(content).hexdigest() != expected_digest:
                    raise ValueError("Guide archive file checksum mismatch")
                if destination is not None:
                    target = staged / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(content)
            if destination is not None:
                staged.rename(destination)
    return metadata


def verify_files(
    root: Path, path: Path, version: str, release_commit: str, builder_commit: str, repository: str
) -> dict[str, Any]:
    """Require an attested main-branch documentation publisher, not a software-tag claim."""
    metadata = inspect_archive(path, version, release_commit, builder_commit)
    checksum, bundle = Path(str(path) + ".sha256"), Path(str(path) + ".sigstore.json")
    if not stat.S_ISREG(checksum.lstat().st_mode) or checksum.stat().st_size > 256:
        raise ValueError("Invalid guide checksum file")
    if checksum.read_text() != f"{digest(path)}  {path.name}\n":
        raise ValueError("Guide archive checksum mismatch")
    if not stat.S_ISREG(bundle.lstat().st_mode) or not 0 < bundle.stat().st_size <= MAX_BUNDLE:
        raise ValueError("Invalid guide attestation bundle")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Guide verification requires an explicit GitHub repository")
    subprocess.run(
        [
            "gh",
            "attestation",
            "verify",
            str(path),
            "--bundle",
            str(bundle),
            "--hostname",
            "github.com",
            "--repo",
            repository,
            "--signer-workflow",
            repository + "/.github/workflows/docs-archive.yml",
            "--signer-digest",
            builder_commit,
            "--source-digest",
            builder_commit,
            "--source-ref",
            "refs/heads/main",
            "--deny-self-hosted-runners",
            "--predicate-type",
            "https://slsa.dev/provenance/v1",
        ],
        cwd=root,
        check=True,
        timeout=120,
    )
    return metadata


def catalog_entry(root: Path, entry: ReleaseEntry) -> dict[str, Any]:
    """Resolve a reviewed immutable publication and its current snapshot agreement."""
    catalog = json.loads((root / "website/guide-archives.json").read_text())
    if not isinstance(catalog, dict) or type(catalog.get("schemaVersion")) is not int or catalog["schemaVersion"] != 1:
        raise ValueError("Invalid guide archive catalog schema")
    records = catalog.get("archives")
    record = records.get(entry["name"]) if isinstance(records, dict) else None
    if not isinstance(record, dict):
        raise ValueError(f"Publish and review a guide archive before retiring {entry['name']}")
    version = entry["name"]
    identity(version, entry["sourceCommit"] or "")
    builder = record.get("builderCommit", "")
    if not isinstance(builder, str):
        raise ValueError("Invalid guide builder identity")
    identity(version, builder)
    if (
        record.get("version") != version
        or record.get("releaseCommit") != entry["sourceCommit"]
        or record.get("documentationCommit") != builder
        or record.get("publication") != "docs-" + builder
        or record.get("sourceDigest") != source_digest(root, version)
    ):
        raise ValueError("Guide catalog does not match the selected release and reviewed snapshot")
    assets = record.get("assets")
    names = filenames(version)
    if not isinstance(assets, list) or len(assets) != 3:
        raise ValueError("Guide publication requires its archive, checksum, and attestation")
    for asset, name, limit in zip(assets, names, (MAX_ARCHIVE, 256, MAX_BUNDLE), strict=True):
        if (
            not isinstance(asset, dict)
            or asset.get("name") != name
            or asset.get("url") != f"https://github.com/{REPOSITORY}/releases/download/docs-{builder}/{name}"
            or type(asset.get("size")) is not int
            or not 0 < asset["size"] <= limit
            or not isinstance(asset.get("sha256"), str)
            or not HEX.fullmatch(asset["sha256"])
        ):
            raise ValueError("Invalid pinned guide asset descriptor")
    return record


def restore(root: Path, entry: ReleaseEntry, destination: Path) -> dict[str, Any]:
    """Download and verify pinned artifacts in private storage before installing guides."""
    record = catalog_entry(root, entry)
    assets = record["assets"]
    # Downloaded files are private: shared caches cannot change attested bytes during extraction.
    with tempfile.TemporaryDirectory(prefix="tracer-guide-download-") as temporary:
        directory = Path(temporary)
        for asset in assets:
            with urllib.request.urlopen(asset["url"], timeout=30) as response:
                content = response.read(asset["size"] + 1)
            if len(content) != asset["size"] or hashlib.sha256(content).hexdigest() != asset["sha256"]:
                raise ValueError("Downloaded guide artifact differs from its pinned size or checksum")
            (directory / asset["name"]).write_bytes(content)
        path = directory / assets[0]["name"]
        metadata = verify_files(root, path, entry["name"], record["releaseCommit"], record["builderCommit"], REPOSITORY)
        if metadata["sourceDigest"] != record["sourceDigest"]:
            raise ValueError("Published guide snapshot differs from the reviewed catalog")
        inspect_archive(path, entry["name"], record["releaseCommit"], record["builderCommit"], destination=destination)
    return metadata


def main() -> None:
    """Check local candidate structure or verify its adjacent publication evidence."""
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("action", choices=("check", "verify"))
    parser.add_argument("path", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--release-commit", required=True)
    parser.add_argument("--builder-commit", required=True)
    parser.add_argument("--repository", default=REPOSITORY)
    args = parser.parse_args()
    path = args.path.absolute()
    if args.action == "verify":
        metadata = verify_files(
            Path.cwd(), path, args.version, args.release_commit, args.builder_commit, args.repository
        )
    else:
        metadata = inspect_archive(path, args.version, args.release_commit, args.builder_commit)
    if metadata["sourceDigest"] != source_digest(Path.cwd(), args.version):
        raise ValueError("Guide candidate does not match the working documentation snapshot")
    print(f"Guide archive {args.action} passed: {path.name}")


if __name__ == "__main__":
    main()
