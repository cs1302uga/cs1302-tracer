#!/usr/bin/env python3
"""Preserve published Javadoc bytes and verify the actual uploaded release files."""

import argparse
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

from javadoc_artifact import MAX_ARCHIVE, MAX_BUNDLE, filenames, identity, verify_release_files


def github(root: Path, *arguments: str) -> str:
    """Read GitHub through explicit arguments, preserving authentication and transport failures."""
    return subprocess.run(["gh", *arguments], cwd=root, check=True, capture_output=True, text=True, timeout=120).stdout


def published_sizes(root: Path, version: str, repository: str) -> dict[str, int] | None:
    """Find a complete remote trio; absence is fresh, partial or malformed metadata is an error."""
    tag = "v" + version
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
        raise ValueError("Ambiguous GitHub release tag metadata")
    release = json.loads(
        github(
            root,
            "release",
            "view",
            tag,
            "--repo",
            "github.com/" + repository,
            "--json",
            "tagName,isPrerelease,assets",
        )
    )
    if not isinstance(release, dict) or release.get("tagName") != tag or release.get("isPrerelease") is not False:
        raise ValueError("Existing Javadoc release must identify the expected stable tag")
    assets = release.get("assets")
    if not isinstance(assets, list):
        raise ValueError("Invalid GitHub release asset metadata")
    sizes: dict[str, int] = {}
    for asset in assets:
        if (
            not isinstance(asset, dict)
            or not isinstance(asset.get("name"), str)
            or not asset["name"]
            or type(asset.get("size")) is not int
            or asset["size"] < 0
            or asset["name"] in sizes
        ):
            raise ValueError("Release assets must have unique names and nonnegative integer sizes")
        sizes[asset["name"]] = asset["size"]
    names = filenames(version)
    if not any(name in sizes for name in names):
        return None
    if any(name not in sizes for name in names):
        raise ValueError(
            "Partial release Javadoc assets: automatic replacement is refused. "
            "Recover the original matching companions through a reviewed recovery, or publish a new version."
        )
    if any(not 0 < sizes[name] <= limit for name, limit in zip(names, (MAX_ARCHIVE, 256, MAX_BUNDLE), strict=True)):
        raise ValueError("Published Javadoc asset sizes exceed their bounds")
    return {name: sizes[name] for name in names}


def process(root: Path, path: Path, version: str, commit: str, repository: str, *, preserve: bool) -> bool:
    """Verify existing remote bytes and optionally replace local candidates with that frozen trio."""
    identity(version, commit)
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Publication requires an explicit GitHub repository")
    if path.name != filenames(version)[0]:
        raise ValueError("Javadoc publication requires the exact versioned artifact filename")
    sizes = published_sizes(root, version, repository)
    if sizes is None:
        if preserve:
            return False
        raise ValueError("Published release Javadoc artifacts are missing")
    with tempfile.TemporaryDirectory(prefix="tracer-published-javadoc-") as temporary:
        downloaded = Path(temporary)
        github(
            root,
            "release",
            "download",
            "v" + version,
            "--repo",
            "github.com/" + repository,
            "--dir",
            str(downloaded),
            *(argument for name in sizes for argument in ("--pattern", name)),
        )
        if any((downloaded / name).stat().st_size != size for name, size in sizes.items()):
            raise ValueError("Downloaded Javadoc files differ from published asset sizes")
        verify_release_files(root, downloaded / path.name, version, commit, repository)
        if preserve:
            path.parent.mkdir(parents=True, exist_ok=True)
            for name in sizes:
                shutil.copy2(downloaded / name, path.parent / name)
    return True


def main() -> None:
    """Run release preflight or verify remote assets after an upload without replacing them."""
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("action", choices=("preserve", "verify"))
    parser.add_argument("path", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    preserved = process(
        Path.cwd(), args.path.absolute(), args.version, args.commit, args.repository, preserve=args.action == "preserve"
    )
    if args.github_output:
        with args.github_output.open("a") as output:
            output.write(f"preserved={str(preserved).lower()}\n")
    print(f"Release Javadoc {args.action} passed; existing artifacts {'verified' if preserved else 'absent'}.")


if __name__ == "__main__":
    main()
