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
from docs_timing import BuildTimings
from guide_build import compose, prepare, validate_retention

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    """Run the command-line workflow."""
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("action", choices=("build", "start", "api", "verify"))
    parser.add_argument("--mode", choices=("preview", "published"), default=os.environ.get("DOCSITE_MODE", "preview"))
    parser.add_argument(
        "--guides",
        choices=("all", "active"),
        default=os.environ.get("DOCSITE_GUIDES", "all"),
        help="Build all guides, or build current/recent guides and compose verified historical archives",
    )
    parser.add_argument(
        "--releases",
        type=Path,
        default=os.environ.get("DOCSITE_RELEASES") or None,
        help="GitHub release records; required in published mode",
    )
    parser.add_argument(
        "--javadoc-source",
        choices=("auto", "source"),
        default=os.environ.get("DOCSITE_JAVADOC_SOURCE", "auto"),
        help="Prefer verified published Javadoc (preview uses source), or force historical source with its cache mode",
    )
    parser.add_argument(
        "--javadoc-cache",
        choices=("use", "refresh", "off"),
        default=os.environ.get("DOCSITE_JAVADOC_CACHE", "use"),
        help="Reuse, refresh, or bypass source-generation caches; published artifacts take precedence in auto mode",
    )
    parser.add_argument(
        "--pending-commit",
        default=os.environ.get("DOCSITE_PENDING_COMMIT") or None,
        help="Full release commit SHA for previewing its untagged snapshot after the next development bump",
    )
    args, remaining = parser.parse_known_args()
    if args.guides not in ("all", "active"):
        parser.error("DOCSITE_GUIDES must be all or active")
    if args.action == "start" and args.guides == "active":
        parser.error("Active guides require build or verify followed by serve_docs.py; start supports all guides")
    if args.javadoc_source not in ("auto", "source"):
        parser.error("DOCSITE_JAVADOC_SOURCE must be auto or source")
    if args.javadoc_cache not in ("use", "refresh", "off"):
        parser.error("DOCSITE_JAVADOC_CACHE must be use, refresh, or off")
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
    project = Project(ROOT)
    timings = BuildTimings()
    try:
        with timings.measure("release-selection"):
            records = json.loads(args.releases.read_text()) if args.releases else None
            plan = project.plan(args.mode, records, pending_commit=args.pending_commit)
            validate_retention(ROOT, plan)
        if args.action == "verify":
            with timings.measure("executable-docs"):
                subprocess.run([sys.executable, str(ROOT / "scripts/check_docs.py")], cwd=ROOT, check=True)
            with timings.measure("release-metadata"):
                subprocess.run([sys.executable, str(ROOT / "scripts/docs_release.py"), "check"], cwd=ROOT, check=True)
        build(
            ROOT,
            plan,
            releases=records,
            artifact_mode=args.javadoc_source,
            cache_mode=args.javadoc_cache,
            timings=timings,
        )
        manifest = project.write_manifest(plan)
        if args.action != "api":
            with prepare(ROOT, plan, args.guides, timings) as archives:
                action = "build" if args.action == "verify" else args.action
                with timings.measure("docusaurus"):
                    subprocess.run(["npm", "run", "docusaurus", "--", action, *remaining], cwd=project.site, check=True)
                if args.action in ("build", "verify"):
                    with timings.measure("guide-composition"):
                        compose(ROOT, plan, archives)
                    shutil.copy2(manifest, project.site / "build/release-manifest.json")
        if args.action == "verify":
            with timings.measure("rendered-links"):
                subprocess.run([sys.executable, str(ROOT / "scripts/check_site.py")], cwd=ROOT, check=True)
    finally:
        timings.write(
            project.site / "build-timings.json",
            scope="documentation orchestration only; excludes outer Maven lifecycle and artifact upload",
            action=args.action,
            mode=args.mode,
            javadocCache=args.javadoc_cache,
            javadocSource=args.javadoc_source,
            guides=args.guides,
        )


if __name__ == "__main__":
    main()
