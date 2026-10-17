#!/usr/bin/env python3
"""Generate development and selected release API references for the documentation site."""

import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import TYPE_CHECKING

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
    shutil.copytree(generated, destination)


def build(root: Path, plan: ReleasePlan) -> None:
    """Generate exactly the releases and pinned revisions selected by the release module."""
    output = root / "website/generated-api"
    with tempfile.TemporaryDirectory(prefix="tracer-javadoc-") as temporary:
        staging = Path(temporary)
        development = staging / plan["versions"][0]["javadocPath"]
        generate(root, root, development)
        for entry in plan["versions"][1:]:
            destination = staging / entry["javadocPath"]
            if entry["sourceCommit"] is None:
                shutil.copytree(development, destination)
                continue
            source = staging / ("source-" + entry["name"])
            source.mkdir()
            archive = staging / "source.tar"
            subprocess.run(
                ["git", "archive", "--format=tar", "-o", str(archive), entry["sourceCommit"]], cwd=root, check=True
            )
            subprocess.run(["tar", "-xf", str(archive), "-C", str(source)], check=True)
            generate(root, source, destination)
        if output.exists():
            shutil.rmtree(output)
        output.mkdir()
        shutil.copytree(staging / "api", output / "api")
    print(f"Generated API reference for {len(plan['versions'])} documentation versions.")
