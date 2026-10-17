#!/usr/bin/env python3
"""Run the serial, local release gates around Maven's strict Java and docsite build."""

import argparse
import os
import subprocess
import tempfile
from pathlib import Path

from docs_release import VERSION, Project

ROOT = Path(__file__).resolve().parents[1]


def initialize(root: Path) -> None:
    """Retain stale execution data as audit evidence without merging it into a new run."""
    previous = root / "target/jacoco.exec"
    if previous.exists():
        audit = root / "target/release-validation"
        audit.mkdir(parents=True, exist_ok=True)
        destination = Path(tempfile.mkdtemp(prefix="previous-coverage-", dir=audit)) / previous.name
        previous.rename(destination)
        print(f"Preserved previous Java coverage in {destination}", flush=True)


def verify(root: Path, version: str) -> None:
    """Fail on the first failed gate, keeping guest execution and documentation serial."""
    # Maven provisions this Node distribution before verify; use it for sidebar validation too.
    os.environ["PATH"] = str(root / "target/frontend/node") + os.pathsep + os.environ.get("PATH", "")
    Project(root).check(tag="v" + version)
    environment = dict(
        os.environ,
        COVERAGE_RCFILE=str(root / "scripts/pyproject.toml"),
        COVERAGE_FILE=str(root / ".coverage"),
        UVW_GIT_COMMIT="never",
    )
    uv = [str(root / "scripts/uv"), "run", "--locked"]
    commands = [
        [*uv, "ruff", "check", "scripts"],
        [*uv, "ruff", "format", "--check", "scripts"],
        [*uv, "ty", "check", "scripts"],
        [*uv, "coverage", "erase"],
        [*uv, "coverage", "run", "-m", "unittest", "discover", "-s", "scripts/tests", "-t", "scripts"],
        [*uv, "coverage", "combine"],
        [*uv, "coverage", "report"],
        [*uv, "python", "-m", "unittest", "discover", "-s", "examples", "-p", "test_verify.py"],
        [*uv, "python", "examples/verify.py"],
        [str(root / "target/code-tracer"), "--version"],
        [str(root / "target/code-tracer"), "--help"],
        [
            "javadoc",
            "-quiet",
            "-private",
            "--release",
            "21",
            "-Xdoclint:all,-missing",
            "-classpath",
            "target/code-tracer.jar",
            "-sourcepath",
            "src/main/java",
            "-subpackages",
            "cs1302.tracer",
            "-overview",
            "src/main/javadoc/overview.html",
            "-d",
            "target/internal-apidocs",
        ],
    ]
    for command in commands:
        print("Release validation: " + " ".join(command), flush=True)
        subprocess.run(command, cwd=root, env=environment, check=True)
    print("Local release gates passed; Maven's docsite gate must also finish before tagging.", flush=True)


def main() -> None:
    """Dispatch the Maven lifecycle hook against stable release-version sources."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("initialize", "verify"))
    args = parser.parse_args()
    version = Project(ROOT).project_version()
    if not VERSION.fullmatch(version):
        parser.error("Release validation requires a stable x.y.z POM and prepared documentation snapshot")
    if args.phase == "initialize":
        initialize(ROOT)
    else:
        verify(ROOT, version)


if __name__ == "__main__":
    main()
