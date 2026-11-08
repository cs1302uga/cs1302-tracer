#!/usr/bin/env python3
"""Prepare, validate, and select documentation release snapshots."""

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import NotRequired, TypedDict, cast

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / "src/site"
VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\Z")


class ReleaseAsset(TypedDict):
    """Published asset identity and integrity fields used by the Javadoc consumer."""

    name: str
    browser_download_url: str
    size: int
    digest: str


class ReleaseRecord(TypedDict):
    """GitHub release fields used to select public documentation."""

    tag_name: str
    draft: NotRequired[bool]
    prerelease: NotRequired[bool]
    assets: NotRequired[list[ReleaseAsset]]


class ReleaseEntry(TypedDict):
    """Routes and source provenance for one documentation version."""

    name: str
    label: str
    docsPath: str
    docsUrl: str
    javadocPath: str
    javadocUrl: str
    sourceCommit: str | None
    banner: str


class ReleasePlan(TypedDict):
    """Validated release selection consumed by builds and navigation."""

    mode: str
    baseUrl: str
    origin: str
    latestVersion: str | None
    versions: list[ReleaseEntry]


def maven_version(pom: str) -> str:
    """Read the required project version from Maven XML."""
    element = ET.fromstring(pom).find("{*}version")
    if element is None or not element.text:
        raise ValueError("Maven project requires a nonempty version")
    return element.text


class Project:
    """Own documentation release validation, selection, routes, and source provenance."""

    def __init__(self, root: Path) -> None:
        """Initialize the input state."""
        self.root = Path(root)
        self.site = self.root / "src/site"

    def plan(
        self, mode: str, releases: list[ReleaseRecord] | None = None, pending_commit: str | None = None
    ) -> ReleasePlan:
        """Resolve documentation versions and their source commits before building."""
        self.check()
        if mode not in ("preview", "published"):
            raise ValueError("Unknown documentation mode")
        if mode == "published" and releases is None:
            raise ValueError("Published mode requires release records")
        if mode == "preview" and releases is not None:
            raise ValueError("Release records require published mode")
        if pending_commit and mode != "preview":
            raise ValueError("Pending release commits require preview mode")
        pending_version = self.pending_release(pending_commit) if pending_commit else None
        selected = self.versions() if mode == "preview" else self.select(cast("list[ReleaseRecord]", releases))
        base = "/cs1302-tracer/"
        entries: list[ReleaseEntry] = [
            {
                "name": "current",
                "label": "Development (main)",
                "docsPath": "docs/dev",
                "docsUrl": base + "docs/dev/",
                "javadocPath": "api/next",
                "javadocUrl": base + "api/next/index.html",
                "sourceCommit": None,
                "banner": "unreleased",
            }
        ]
        for version in selected:
            tags = subprocess.check_output(
                ["git", "tag", "--list", version, "v" + version], cwd=self.root, text=True
            ).splitlines()
            commit = None
            if tags:
                commits = {
                    subprocess.check_output(["git", "rev-parse", tag + "^{commit}"], cwd=self.root, text=True).strip()
                    for tag in tags
                }
                if len(commits) != 1:
                    raise ValueError(f"Ambiguous release tags for {version}")
                commit = commits.pop()
                pom = subprocess.check_output(["git", "show", commit + ":pom.xml"], cwd=self.root, text=True)
                actual = maven_version(pom)
                if actual != version:
                    raise ValueError(f"Release tag for {version} contains Maven version {actual}")
                if version == pending_version and commit != pending_commit:
                    raise ValueError(f"Release tag for {version} differs from the pending commit")
            elif version == pending_version:
                commit = pending_commit
            elif mode == "published" or version != self.project_version().removesuffix("-SNAPSHOT"):
                raise ValueError(f"Missing release tag for {version}")
            entries.append(
                {
                    "name": version,
                    "label": "Release " + version,
                    "docsPath": "docs/" + version,
                    "docsUrl": base + "docs/" + version + "/",
                    "javadocPath": "api/" + version,
                    "javadocUrl": base + "api/" + version + "/index.html",
                    "sourceCommit": commit,
                    "banner": "unreleased" if not tags else "none",
                }
            )
        latest = next((entry["name"] for entry in entries[1:] if entry["banner"] == "none"), None)
        self.validate_retention(entries, mode)
        return {
            "mode": mode,
            "baseUrl": base,
            "origin": "https://cs1302uga.github.io",
            "latestVersion": latest,
            "versions": entries,
        }

    def versions(self) -> list[str]:
        """Read saved documentation versions."""
        file = self.site / "versions.json"
        return json.loads(file.read_text()) if file.exists() else []

    def write_manifest(self, plan: ReleasePlan) -> Path:
        """Make the selected release decisions available to the site tooling."""
        path = self.root / "target/docsite/release-manifest.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(plan, indent=2) + "\n")
        return path

    def project_version(self) -> str:
        """Read the current Maven version."""
        return maven_version((self.root / "pom.xml").read_text())

    @staticmethod
    def files(folder: Path) -> dict[str, bytes]:
        """Read a snapshot as relative paths and bytes."""
        return {
            p.relative_to(folder).as_posix(): p.read_bytes()
            for p in folder.rglob("*")
            if p.is_file() and not (folder.name == "docs" and p.relative_to(folder).parts[0] == "releases")
        }

    def revision_files(self, commit: str, folder: str) -> dict[str, bytes]:
        """Read committed files without altering the checkout or executing its code."""
        paths = (
            subprocess.check_output(["git", "ls-tree", "-r", "-z", "--name-only", commit, "--", folder], cwd=self.root)
            .decode()
            .split("\0")
        )
        return {
            path.removeprefix(folder + "/"): subprocess.check_output(
                ["git", "show", commit + ":" + path], cwd=self.root
            )
            for path in paths
            if path and not (folder == "docs" and path.startswith("docs/releases/"))
        }

    def pending_release(self, commit: str) -> str:
        """Validate a pinned, unpublished release ancestor for next-development previews."""
        assert re.fullmatch(r"[0-9a-f]{40}", commit), "Pending release source must be a full commit SHA"
        resolved = subprocess.check_output(
            ["git", "rev-parse", "--verify", commit + "^{commit}"], cwd=self.root, text=True
        ).strip()
        assert resolved == commit, "Pending release source must name a commit, not a tag object"
        assert subprocess.run(["git", "merge-base", "--is-ancestor", commit, "HEAD"], cwd=self.root).returncode == 0, (
            "Pending release commit must be an ancestor of HEAD"
        )
        pom = subprocess.check_output(["git", "show", commit + ":pom.xml"], cwd=self.root, text=True)
        version = maven_version(pom)
        assert VERSION.fullmatch(version), "Pending release commit requires a stable Maven version"
        assert self.versions() and version == self.versions()[0], "Pending release must be the newest snapshot"
        self.check_snapshot(version)
        assert self.revision_files(commit, "docs") == self.files(self.root / "docs"), (
            "Pending release commit documentation differs from current docs/"
        )
        snapshot = f"docs/releases/{version}"
        assert self.revision_files(commit, snapshot) == self.files(self.root / snapshot), (
            "Pending release commit snapshot differs from the prepared snapshot"
        )
        for name in ("versions.json", "navigation.json", f"releases/{version}-navigation.json"):
            archived = subprocess.check_output(["git", "show", commit + ":src/site/" + name], cwd=self.root)
            assert archived == (self.site / name).read_bytes(), (
                "Pending release commit metadata differs from current files"
            )
        return version

    def check_snapshot(self, version: str) -> None:
        """Require exact agreement between current documentation and its pending snapshot."""
        assert version in self.versions(), "Prepare and commit the documentation snapshot before tagging"
        assert self.files(self.root / "docs") == self.files(self.root / "docs/releases" / version), (
            "Release snapshot differs from docs/. Refresh it before tagging."
        )
        current = json.loads((self.site / "navigation.json").read_text())
        archived = json.loads((self.site / f"releases/{version}-navigation.json").read_text())
        assert current == archived, "Release sidebar differs from current sidebar"

    def snapshot_digest(self, version: str) -> str:
        """Hash exact retained prose and its navigation with deterministic path boundaries."""
        digest = hashlib.sha256()
        for name, content in sorted(self.files(self.root / "docs/releases" / version).items()):
            digest.update(name.encode() + b"\0" + content + b"\0")
        digest.update(b"navigation.json\0" + (self.site / f"releases/{version}-navigation.json").read_bytes() + b"\0")
        return digest.hexdigest()

    def validate_retention(self, entries: list[ReleaseEntry], mode: str) -> None:
        """Protect pinned software identity and frozen prose independently of the renderer."""
        file = self.site / "release-provenance.json"
        if not file.exists():
            return
        catalog = json.loads(file.read_text())
        assert catalog["schemaVersion"] == 1, "Invalid release provenance schema"
        selected = {entry["name"]: entry for entry in entries}
        for version, record in catalog["releases"].items():
            assert version in self.versions(), f"Existing documentation versions must be retained: {version}"
            assert self.snapshot_digest(version) == record["sourceDigest"], f"Frozen documentation changed: {version}"
            if mode == "published":
                assert version in selected, f"Published release records must retain pinned guide {version}"
                assert selected[version]["sourceCommit"] == record["releaseCommit"], f"Retargeted release {version}"

    def check(self, tag: str | None = None, base: str | None = None, pending: str | None = None) -> None:
        """Validate snapshot metadata and optional release or base constraints."""
        assert not (tag and pending), "Choose either a release tag or a pending version"
        saved = self.versions()
        assert len(saved) == len(set(saved)), "Duplicate documentation versions"
        assert all(VERSION.fullmatch(v) for v in saved), "Only stable x.y.z snapshots are supported"
        assert saved == sorted(saved, key=lambda v: tuple(map(int, v.split("."))), reverse=True), (
            "Snapshots must be ordered newest first"
        )
        for version in saved:
            assert (self.root / "docs/releases" / version / "index.md").is_file(), (
                f"Missing documentation snapshot for {version}"
            )
            assert (self.site / f"releases/{version}-navigation.json").is_file(), (
                f"Missing sidebar snapshot for {version}"
            )
        if base:
            listed = subprocess.check_output(
                ["git", "ls-tree", "--name-only", base, "src/site/versions.json", "website/versions.json"],
                cwd=self.root,
                text=True,
            ).strip()
            previous = (
                json.loads(
                    subprocess.check_output(
                        ["git", "show", f"{base}:" + listed.splitlines()[0]], cwd=self.root, text=True
                    )
                )
                if listed
                else []
            )
            assert set(previous).issubset(saved), "Existing documentation versions must be retained"
        if tag or pending:
            version = tag.removeprefix("v") if tag else cast("str", pending)
            assert VERSION.fullmatch(version), "Release tags must be x.y.z or vx.y.z; pending versions must be x.y.z"
            if pending:
                assert self.project_version() in (version, version + "-SNAPSHOT"), (
                    "Pending release version does not match pom.xml"
                )
            else:
                assert version == self.project_version(), "Release tag does not match pom.xml"
            self.check_snapshot(version)
        self.validate_retention([], "preview")
        print("Documentation release metadata passed.")

    def select(self, releases: list[ReleaseRecord]) -> list[str]:
        """Choose only published stable releases, ordered by numeric version, not creation time."""
        tags = {r["tag_name"].removeprefix("v") for r in releases if not r.get("draft") and not r.get("prerelease")}
        return sorted(
            (v for v in self.versions() if v in tags), key=lambda v: tuple(map(int, v.split("."))), reverse=True
        )


def main() -> None:
    """Run the command-line workflow."""
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    preparation = sub.add_parser("prepare")
    preparation.add_argument("--version", help="Stable release target matching pom.xml, with or without -SNAPSHOT")
    validation = sub.add_parser("check")
    target = validation.add_mutually_exclusive_group()
    target.add_argument("--tag")
    target.add_argument("--pending", help="Require an exact snapshot for this stable target before release preparation")
    validation.add_argument("--base", help="Base commit whose versions must be retained")
    published = sub.add_parser("published")
    published.add_argument("releases", type=Path, help="JSON array from GitHub releases API")
    args = parser.parse_args()
    project = Project(ROOT)
    if args.command == "prepare":
        version = args.version or project.project_version()
        assert VERSION.fullmatch(version), "Choose a stable x.y.z target with --version, or set a stable Maven version"
        assert project.project_version() in (version, version + "-SNAPSHOT"), (
            "Release target must match the Maven version with or without -SNAPSHOT"
        )
        tags = subprocess.check_output(["git", "tag", "--list", version, "v" + version], cwd=ROOT, text=True).strip()
        assert not tags, "This version is already tagged; choose the next release version"
        assert version not in project.versions(), "Snapshot already exists; review/edit it explicitly"
        assert not project.versions() or tuple(map(int, version.split("."))) > tuple(
            map(int, project.versions()[0].split("."))
        ), "New snapshots must increase the version"
        project.write_manifest(project.plan("preview"))
        shutil.copytree(ROOT / "docs", ROOT / "docs/releases" / version, ignore=shutil.ignore_patterns("releases"))
        (SITE / "releases").mkdir(exist_ok=True)
        shutil.copy2(SITE / "navigation.json", SITE / f"releases/{version}-navigation.json")
        (SITE / "versions.json").write_text(json.dumps([version, *project.versions()], indent=2) + "\n")
        project.check(pending=version)
        project.write_manifest(project.plan("preview"))
        print(f"Review and commit docs/releases, src/site/versions.json, and navigation before tagging v{version}.")
    elif args.command == "check":
        project.check(args.tag, args.base, args.pending)
    else:
        project.check()
        selected = project.select(json.loads(args.releases.read_text()))
        report = ROOT / "target/docsite/published-versions.json"
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(selected, indent=2) + "\n")
        print(f"Published versions: {selected}; development remains available.")


if __name__ == "__main__":
    main()
