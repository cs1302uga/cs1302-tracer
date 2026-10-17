#!/usr/bin/env python3
"""Build or preview documentation with an explicit release selection mode."""

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

from build_javadoc import build
from docs_release import Project

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    """Run the command-line workflow."""
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("action", choices=("build", "start", "api", "verify"))
    parser.add_argument("--mode", choices=("preview", "published"), default=os.environ.get("DOCSITE_MODE", "preview"))
    parser.add_argument(
        "--releases",
        type=Path,
        default=os.environ.get("DOCSITE_RELEASES") or None,
        help="GitHub release records; required in published mode",
    )
    parser.add_argument(
        "--pending-commit",
        default=os.environ.get("DOCSITE_PENDING_COMMIT") or None,
        help="Full release commit SHA for previewing its untagged snapshot after the next development bump",
    )
    args, remaining = parser.parse_known_args()
    # The release manifest, checker, and preview server all own website/build.
    # Validate forwarded flags so a positional site directory or output/config
    # override cannot silently move the build away from its manifest.
    forwarded = argparse.ArgumentParser(add_help=False, allow_abbrev=False)
    if args.action in ("start", "build"):
        forwarded.add_argument("--no-minify", action="store_true")
    if args.action == "start":
        forwarded.add_argument("--host")
        forwarded.add_argument("-p", "--port")
        forwarded.add_argument("--no-open", action="store_true")
    forwarded.parse_args(remaining)
    records = json.loads(args.releases.read_text()) if args.releases else None
    project = Project(ROOT)
    plan = project.plan(args.mode, records, pending_commit=args.pending_commit)
    if args.action == "verify":
        subprocess.run([sys.executable, str(ROOT / "scripts/check_docs.py")], cwd=ROOT, check=True)
        subprocess.run([sys.executable, str(ROOT / "scripts/docs_release.py"), "check"], cwd=ROOT, check=True)
    build(ROOT, plan)
    manifest = project.write_manifest(plan)
    if args.action != "api":
        action = "build" if args.action == "verify" else args.action
        subprocess.run(["npm", "run", "docusaurus", "--", action, *remaining], cwd=project.site, check=True)
        if args.action in ("build", "verify"):
            shutil.copy2(manifest, project.site / "build/release-manifest.json")
    if args.action == "verify":
        subprocess.run([sys.executable, str(ROOT / "scripts/check_site.py")], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
