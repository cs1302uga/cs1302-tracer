#!/usr/bin/env python3
"""Preserve reviewed documentation publications and propose verified archive catalog entries."""

import argparse
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Any

from guide_archive import filenames, inspect_archive, source_digest, verify_files
from javadoc_artifact import MAX_ARCHIVE, MAX_BUNDLE, digest, identity


def github(root: Path, *arguments: str) -> str:
    """Keep every GitHub operation explicit and preserve failures instead of treating them as absence."""
    return subprocess.run(["gh", *arguments], cwd=root, check=True, capture_output=True, text=True, timeout=120).stdout


def context(root: Path, versions: str, builder: str, repository: str) -> dict[str, str]:
    """Validate the selected versions and bind their software tags to a clean current-main builder."""
    selected = versions.split(",")
    for version in selected:
        identity(version, builder)
    if len(selected) != len(set(selected)):
        raise ValueError("Guide versions must be unique comma-separated stable versions")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Guide publication requires an explicit GitHub repository")
    for ref in ("HEAD", "origin/main"):
        actual = subprocess.check_output(["git", "rev-parse", ref], cwd=root, text=True).strip()
        if actual != builder:
            raise ValueError("Guide publication must use the selected current-main builder commit")
    if subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=normal"], cwd=root):
        raise ValueError("Guide publication requires a clean builder checkout")
    releases = {}
    for version in selected:
        commit = subprocess.check_output(["git", "rev-parse", f"v{version}^{{commit}}"], cwd=root, text=True).strip()
        identity(version, commit)
        releases[version] = commit
    return releases


def remote(root: Path, releases: dict[str, str], builder: str, repository: str) -> dict[str, Any] | None:
    """Require the preserved publication to match the selected artifact set."""
    tag = "docs-" + builder
    refs = json.loads(
        github(
            root,
            "api",
            "--hostname",
            "github.com",
            f"repos/{repository}/git/matching-refs/tags/{tag}",
        )
    )
    if not isinstance(refs, list) or any(not isinstance(ref, dict) for ref in refs):
        raise ValueError("Invalid documentation publication ref metadata")
    matching = [ref for ref in refs if ref.get("ref") == "refs/tags/" + tag]
    if matching and (
        len(matching) != 1
        or not isinstance(matching[0].get("object"), dict)
        or matching[0]["object"].get("type") != "commit"
        or matching[0]["object"].get("sha") != builder
    ):
        raise ValueError("Documentation publication tag must name the exact builder commit")
    tags = github(
        root,
        "api",
        "--hostname",
        "github.com",
        "--paginate",
        f"repos/{repository}/releases?per_page=100",
        "--jq",
        ".[].tag_name",
    ).splitlines()
    if tag not in tags:
        return None
    if tags.count(tag) != 1:
        raise ValueError("Ambiguous documentation publication tag")
    if not matching:
        raise ValueError("Documentation publication tag is missing")
    release = json.loads(
        github(
            root,
            "release",
            "view",
            tag,
            "--repo",
            "github.com/" + repository,
            "--json",
            "tagName,isDraft,isPrerelease,assets",
        )
    )
    if (
        not isinstance(release, dict)
        or release.get("tagName") != tag
        or release.get("isPrerelease") is not True
        or type(release.get("isDraft")) is not bool
        or not isinstance(release.get("assets"), list)
    ):
        raise ValueError("Invalid documentation prerelease metadata")
    limits = {
        name: limit
        for version in releases
        for name, limit in zip(filenames(version), (MAX_ARCHIVE, 256, MAX_BUNDLE), strict=True)
    }
    sizes: dict[str, int] = {}
    for asset in release["assets"]:
        if (
            not isinstance(asset, dict)
            or not isinstance(asset.get("name"), str)
            or asset["name"] not in limits
            or asset["name"] in sizes
            or type(asset.get("size")) is not int
            or not 0 < asset["size"] <= limits[asset["name"]]
        ):
            raise ValueError("Invalid, unexpected, or duplicate documentation release asset")
        sizes[asset["name"]] = asset["size"]
    if set(sizes) != set(limits):
        raise ValueError(
            "Partial documentation publication: preserve existing bytes and recover original companions "
            "through reviewed repair, or use a newly reviewed builder commit; do not overwrite this tag."
        )
    release["sizes"] = sizes
    return release


def verify_local(
    root: Path, directory: Path, releases: dict[str, str], builder: str, repository: str
) -> dict[str, Any]:
    """Authenticate every guide archive and match the reviewed working snapshot."""
    records = {}
    for version, commit in releases.items():
        path = directory / filenames(version)[0]
        metadata = verify_files(root, path, version, commit, builder, repository)
        if metadata["sourceDigest"] != source_digest(root, version):
            raise ValueError("Guide publication does not match the reviewed snapshot")
        records[version] = metadata
    return records


def downloaded(
    root: Path, directory: Path, releases: dict[str, str], builder: str, repository: str, release: dict[str, Any]
) -> dict[str, Any]:
    """Download exactly the declared trio for each version into private storage and verify it."""
    sizes = release["sizes"]
    github(
        root,
        "release",
        "download",
        "docs-" + builder,
        "--repo",
        "github.com/" + repository,
        "--dir",
        str(directory),
        *(argument for name in sizes for argument in ("--pattern", name)),
    )
    if any((directory / name).stat().st_size != size for name, size in sizes.items()):
        raise ValueError("Downloaded guide artifacts differ from the published sizes")
    return verify_local(root, directory, releases, builder, repository)


def process(
    root: Path,
    action: str,
    directory: Path,
    releases: dict[str, str],
    builder: str,
    repository: str,
    catalog_output: Path | None = None,
) -> tuple[bool, bool]:
    """Preserve remote bytes, verify candidates, or write a catalog candidate from actual remote bytes."""
    if action == "plan":
        for version in releases:
            print(version)
        return False, False
    if action == "verify-local":
        verify_local(root, directory, releases, builder, repository)
        return False, False
    if action == "preserve":
        for version, commit in releases.items():
            metadata = inspect_archive(directory / filenames(version)[0], version, commit, builder)
            if metadata["sourceDigest"] != source_digest(root, version):
                raise ValueError("Guide candidate does not match the reviewed snapshot")
    release = remote(root, releases, builder, repository)
    if release is None:
        if action == "preserve":
            return False, False
        raise ValueError("Documentation publication is missing")
    if action == "catalog" and (release["isDraft"] or catalog_output is None):
        raise ValueError("A catalog candidate requires a published prerelease and an output path")
    with tempfile.TemporaryDirectory(prefix="tracer-guide-publication-") as temporary:
        downloaded_directory = Path(temporary)
        records = downloaded(root, downloaded_directory, releases, builder, repository, release)
        if action == "preserve":
            for name in release["sizes"]:
                shutil.copy2(downloaded_directory / name, directory / name)
        if action == "catalog":
            catalog: dict[str, Any] = {"schemaVersion": 1, "archives": {}}
            for version, metadata in records.items():
                record = {
                    key: metadata[key]
                    for key in ("version", "releaseCommit", "sourceDigest", "documentationCommit", "builderCommit")
                }
                record["publication"] = "docs-" + builder
                record["assets"] = [
                    {
                        "name": name,
                        "url": f"https://github.com/{repository}/releases/download/docs-{builder}/{name}",
                        "size": (downloaded_directory / name).stat().st_size,
                        "sha256": digest(downloaded_directory / name),
                    }
                    for name in filenames(version)
                ]
                catalog["archives"][version] = record
            assert catalog_output is not None
            catalog_output.parent.mkdir(parents=True, exist_ok=True)
            catalog_output.write_text(json.dumps(catalog, indent=2) + "\n")
    return True, not release["isDraft"]


def main() -> None:
    """Run one explicit phase without publishing, moving tags, or modifying the reviewed catalog."""
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("action", choices=("plan", "preserve", "verify-local", "verify-remote", "catalog"))
    parser.add_argument("--versions", required=True)
    parser.add_argument("--builder-commit", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--catalog-output", type=Path)
    args = parser.parse_args()
    root = Path.cwd()
    releases = context(root, args.versions, args.builder_commit, args.repository)
    preserved, published = process(
        root,
        args.action,
        args.directory.absolute(),
        releases,
        args.builder_commit,
        args.repository,
        args.catalog_output,
    )
    if args.github_output:
        with args.github_output.open("a") as output:
            output.write(f"preserved={str(preserved).lower()}\n")
            output.write(f"published={str(published).lower()}\n")


if __name__ == "__main__":
    main()
