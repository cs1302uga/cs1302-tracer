#!/usr/bin/env python3
"""Build selected guides and compose independently verified historical applications."""

import argparse
import hashlib
import html
import json
import platform
import shutil
import stat
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from contextlib import contextmanager
from pathlib import Path
from typing import TYPE_CHECKING, TypedDict

from docs_release import Project
from guide_archive import create, restore, source_digest

if TYPE_CHECKING:
    from collections.abc import Generator

    from docs_release import ReleaseEntry, ReleasePlan
    from docs_timing import BuildTimings

ROOT = Path(__file__).resolve().parents[1]


class Selection(TypedDict):
    """The guide compilation subset, independent of the complete release plan."""

    kind: str
    versions: list[str]


def validate_retention(root: Path, plan: ReleasePlan) -> None:
    """Refuse publication when mutable release records remove or retarget a pinned guide."""
    catalog = root / "website/guide-archives.json"
    if plan["mode"] != "published" or not catalog.exists():
        return
    value = json.loads(catalog.read_text())
    if (
        not isinstance(value, dict)
        or type(value.get("schemaVersion")) is not int
        or value["schemaVersion"] != 1
        or not isinstance(value.get("archives"), dict)
    ):
        raise ValueError("Invalid guide archive catalog schema")
    entries = {entry["name"]: entry for entry in plan["versions"] if entry["name"] != "current"}
    for version, record in value["archives"].items():
        if version not in entries:
            raise ValueError(f"Published release records must retain pinned guide {version}")
        if not isinstance(record, dict) or record.get("releaseCommit") != entries[version]["sourceCommit"]:
            raise ValueError(f"Published release records retarget pinned guide {version}")


def select(plan: ReleasePlan, mode: str) -> Selection:
    """Keep development, the two newest stable guides, and at most one pending release."""
    if mode not in ("all", "active"):
        raise ValueError("Unknown guide mode")
    entries = plan["versions"]
    if mode == "all":
        return {"kind": mode, "versions": [entry["name"] for entry in entries]}
    pending = [entry["name"] for entry in entries if entry["name"] != "current" and entry["banner"] != "none"]
    if len(pending) > 1:
        raise ValueError("Active guides permit at most one pending release")
    stable = sorted(
        (entry["name"] for entry in entries if entry["name"] != "current" and entry["banner"] == "none"),
        key=lambda version: tuple(map(int, version.split("."))),
        reverse=True,
    )[:2]
    selected = {"current", *stable, *pending}
    return {"kind": mode, "versions": [entry["name"] for entry in entries if entry["name"] in selected]}


def renderer_inputs(root: Path) -> list[Path]:
    """List shared renderer files without mutable global version catalogs or generated output."""
    site = root / "website"
    files = [*site.glob("*.js"), site / "package.json", site / "package-lock.json"]
    for folder in ("src", "static"):
        files.extend(path for path in (site / folder).rglob("*") if path.is_file())
    return sorted(files)


def fingerprint(root: Path, entry: ReleaseEntry) -> str:
    """Identify actual source and renderer inputs independently of unrelated Git commits."""
    inputs = {
        "source": source_digest(root, entry["name"]),
        "release": entry["sourceCommit"],
        "mount": entry["docsUrl"],
        "platform": platform.platform(),
        "node": subprocess.check_output(["node", "--version"], text=True).strip(),
        "npm": subprocess.check_output(["npm", "--version"], text=True).strip(),
    }
    for path in (*renderer_inputs(root), root / "scripts/guide_build.py", root / "scripts/guide_archive.py"):
        inputs[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return hashlib.sha256(json.dumps(inputs, sort_keys=True).encode()).hexdigest()


def inventory(tree: Path) -> dict[str, str]:
    """Require an index and enumerate every regular archive file, rejecting symbolic links."""
    if tree.is_symlink() or not (tree / "index.html").is_file():
        raise ValueError("Guide output requires a regular directory and index.html")
    result = {}
    for path in sorted(tree.rglob("*")):
        if path.is_symlink() or not (path.is_file() or path.is_dir()):
            raise ValueError(f"Unsupported guide output entry: {path}")
        if path.is_file():
            result[path.relative_to(tree).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def render(root: Path, plan: ReleasePlan, entry: ReleaseEntry, destination: Path) -> None:
    """Compile one snapshot in a disposable project with its permanent mount path."""
    site = root / "website"
    with tempfile.TemporaryDirectory(prefix="tracer-guide-") as temporary:
        stage = Path(temporary)
        staged_site = stage / "website"
        staged_site.mkdir()
        (stage / "docs").mkdir()
        for path in renderer_inputs(root):
            target = stage / path.relative_to(root)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
        version = entry["name"]
        snapshot = f"versioned_docs/version-{version}"
        shutil.copytree(site / snapshot, staged_site / snapshot)
        sidebar = f"versioned_sidebars/version-{version}-sidebars.json"
        (staged_site / sidebar).parent.mkdir()
        shutil.copy2(site / sidebar, staged_site / sidebar)
        (staged_site / "versions.json").write_text(json.dumps([version]))
        (staged_site / "node_modules").symlink_to(site / "node_modules", target_is_directory=True)
        isolated = {**plan, "latestVersion": version, "versions": [entry]}
        (staged_site / "release-manifest.json").write_text(json.dumps(isolated))
        (staged_site / "guide-build.json").write_text(
            json.dumps({"kind": "archive", "version": version, "versions": [version]})
        )
        subprocess.run(
            ["npm", "run", "docusaurus", "--", "build", "--out-dir", str(destination.resolve())],
            cwd=staged_site,
            check=True,
        )
    inventory(destination)


def preview_archive(root: Path, plan: ReleasePlan, entry: ReleaseEntry, destination: Path) -> str:
    """Reuse complete local isolated output or rebuild it without any artifact network access."""
    key = fingerprint(root, entry)
    cache = root / "website/.cache/guides"
    stored = cache / key
    try:
        if any(path.is_symlink() for path in (cache.parent, cache, stored)):
            raise ValueError("Guide cache path is a symbolic link")
        manifest = stored / "files.json"
        details = manifest.lstat()
        if not stat.S_ISREG(details.st_mode) or details.st_size > 2 * 1024 * 1024:
            raise ValueError("Guide cache manifest must be a bounded regular file")
        expected = json.loads(manifest.read_text())
        if inventory(stored / "site") != expected:
            raise ValueError("Guide cache inventory mismatch")
        shutil.copytree(stored / "site", destination, symlinks=True)
        if inventory(destination) != expected:
            raise ValueError("Guide cache changed during copying")
        return "hit"
    except OSError, ValueError, RecursionError:
        if destination.exists():
            shutil.rmtree(destination)
    render(root, plan, entry, destination)
    try:
        if any(path.is_symlink() for path in (cache.parent, cache, stored)):
            raise ValueError("Guide cache path is a symbolic link")
        cache.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".write-", dir=cache) as temporary:
            stage = Path(temporary) / "entry"
            shutil.copytree(destination, stage / "site")
            (stage / "files.json").write_text(json.dumps(inventory(stage / "site"), sort_keys=True))
            if stored.exists():
                shutil.rmtree(stored)
            stage.rename(stored)
    except (OSError, ValueError) as error:
        print(f"Warning: could not store guide cache: {error}")
    return "miss"


@contextmanager
def prepare(root: Path, plan: ReleasePlan, mode: str, timings: BuildTimings) -> Generator[dict[str, Path]]:
    """Resolve excluded guides before the main application changes its output directory."""
    selection = select(plan, mode)
    (root / "website/guide-build.json").write_text(json.dumps(selection, indent=2) + "\n")
    with tempfile.TemporaryDirectory(prefix="tracer-guides-") as temporary:
        archives = {}
        for entry in plan["versions"]:
            if entry["name"] in selection["versions"]:
                continue
            destination = Path(temporary) / entry["name"]
            with timings.measure("guides/" + entry["name"]) as timing:
                if plan["mode"] == "published":
                    restore(root, entry, destination)
                    timing["source"] = "published-archive"
                else:
                    timing["cache"] = preview_archive(root, plan, entry, destination)
                    timing["source"] = "local-archive"
            archives[entry["name"]] = destination
        yield archives


def compose(root: Path, plan: ReleasePlan, archives: dict[str, Path]) -> None:
    """Merge independent applications and emit a live catalog and complete guide sitemap."""
    output = root / "website/build"
    for entry in plan["versions"]:
        if entry["name"] in archives:
            destination = output / entry["docsPath"]
            if destination.exists():
                raise ValueError(f"Guide output collision: {entry['name']}")
            shutil.copytree(archives[entry["name"]], destination)
    versions = []
    for entry in plan["versions"]:
        folder = output / entry["docsUrl"].removeprefix(plan["baseUrl"])
        if not (folder / "index.html").is_file():
            raise ValueError(f"Guide index missing: {entry['name']}")
        routes = sorted(
            path.relative_to(folder).as_posix().removesuffix("index.html")
            for path in folder.rglob("index.html")
            if path.relative_to(folder).parts[0] != "api"
        )
        versions.append({key: entry[key] for key in ("name", "label", "docsUrl", "javadocUrl")} | {"routes": routes})
    catalog = {"schema": 1, "baseUrl": plan["baseUrl"], "latestVersion": plan["latestVersion"], "versions": versions}
    (output / "guide-catalog.json").write_text(json.dumps(catalog, indent=2) + "\n")
    links = "".join(
        f'<li><a href="{html.escape(entry["docsUrl"])}">{html.escape(entry["label"])}</a> '
        f'(<a href="{html.escape(entry["javadocUrl"])}">API Reference</a>)</li>'
        for entry in plan["versions"]
    )
    listing = output / "all-versions"
    listing.mkdir(exist_ok=True)
    (listing / "index.html").write_text(
        '<!doctype html><html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        "<title>Documentation versions — cs1302-tracer</title>"
        "<style>body{font:1rem/1.6 system-ui,sans-serif;margin:0;color:#17212b;background:#fff}"
        "main{max-width:52rem;margin:auto;padding:2rem 1.25rem}a{color:#005ea8}"
        "li{margin:.75rem 0}a:focus-visible{outline:3px solid #005ea8;outline-offset:3px}</style></head>"
        '<body><main><h1>Documentation versions</h1><p><a href="'
        + html.escape(plan["baseUrl"])
        + '">Latest documentation</a></p><ul>'
        + links
        + "</ul></main></body></html>\n"
    )
    namespace = "http://www.sitemaps.org/schemas/sitemap/0.9"
    sitemap = ET.Element("urlset", xmlns=namespace)
    urls = {plan["origin"] + plan["baseUrl"], plan["origin"] + plan["baseUrl"] + "all-versions/"}
    for version in versions:
        urls.update(plan["origin"] + str(version["docsUrl"]) + route for route in version["routes"])
    for url in sorted(urls):
        ET.SubElement(ET.SubElement(sitemap, "url"), "loc").text = url
    ET.ElementTree(sitemap).write(output / "sitemap.xml", encoding="utf-8", xml_declaration=True)


def main() -> None:
    """Create a durable guide bundle from a tagged snapshot and the current renderer."""
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("action", choices=("archive",))
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    plan = Project(ROOT).plan("preview")
    entries = [entry for entry in plan["versions"] if entry["name"] == args.version and entry["banner"] == "none"]
    if not entries or args.version == "current":
        parser.error("An archive requires a tagged stable documentation version")
    with tempfile.TemporaryDirectory(prefix="tracer-guide-output-") as temporary:
        tree = Path(temporary) / "site"
        render(ROOT, plan, entries[0], tree)
        create(ROOT, entries[0], tree, args.output.resolve())


if __name__ == "__main__":
    main()
