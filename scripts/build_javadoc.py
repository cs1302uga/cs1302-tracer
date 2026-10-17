#!/usr/bin/env python3
"""Generate development and selected release API references for the documentation site."""

import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import TYPE_CHECKING

from docs_timing import BuildTimings
from javadoc_cache import REQUIRED, JavadocCache

if TYPE_CHECKING:
    from docs_release import ReleasePlan


def generate(root: Path, source: Path, destination: Path) -> None:
    """Build and copy the requested Javadoc."""
    generated = source / "target/reports/apidocs"
    if generated.exists():
        shutil.rmtree(generated)
    subprocess.run(
        [
            str(root / "mvnw"),
            "-B",
            "-ntp",
            "-f",
            str(source / "pom.xml"),
            "-Djacoco.skip=true",
            "-P!docsite",
            "validate",
            "javadoc:javadoc",
        ],
        cwd=source,
        check=True,
    )
    if not (generated / "index.html").is_file():
        raise RuntimeError(f"Javadoc index missing in {generated}")
    if any(not (generated / name).is_file() for name in REQUIRED):
        raise RuntimeError(f"Javadoc assets missing in {generated}")
    shutil.copytree(generated, destination)


def build(root: Path, plan: ReleasePlan, *, cache_mode: str = "use", timings: BuildTimings | None = None) -> None:
    """Generate exactly the releases and pinned revisions selected by the release module."""
    output = root / "website/generated-api"
    timings = timings or BuildTimings()
    cache = JavadocCache(root, cache_mode)
    with tempfile.TemporaryDirectory(prefix="tracer-javadoc-") as temporary:
        staging = Path(temporary)
        development = staging / plan["versions"][0]["javadocPath"]
        with timings.measure("javadoc/" + plan["versions"][0]["name"]) as record:
            record["cache"] = "development"
            generate(root, root, development)
        for entry in plan["versions"][1:]:
            destination = staging / entry["javadocPath"]
            with timings.measure("javadoc/" + entry["name"]) as record:
                if entry["sourceCommit"] is None:
                    record["cache"] = "pending"
                    shutil.copytree(development, destination)
                    continue
                historical = entry["banner"] == "none"
                key = cache.key(entry["sourceCommit"]) if historical else None
                record["cache"] = ("miss" if cache_mode == "use" else cache_mode) if historical else "pending"
                if historical and cache.restore(key, destination):
                    record["cache"] = "hit"
                    continue
                source = staging / ("source-" + entry["name"])
                source.mkdir()
                archive = staging / "source.tar"
                subprocess.run(
                    ["git", "archive", "--format=tar", "-o", str(archive), entry["sourceCommit"]], cwd=root, check=True
                )
                subprocess.run(["tar", "-xf", str(archive), "-C", str(source)], check=True)
                generate(root, source, destination)
                cache.save(key, destination)
        if output.exists():
            shutil.rmtree(output)
        output.mkdir()
        shutil.copytree(staging / "api", output / "api")
    print(f"Generated API reference for {len(plan['versions'])} documentation versions.")
