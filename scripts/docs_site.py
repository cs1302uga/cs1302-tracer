#!/usr/bin/env python3
"""Build or preview documentation with an explicit release selection mode."""

import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

from build_javadoc import build
from docs_release import Project
from docs_timing import BuildTimings
from maven_site import render, source_digest

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    """Run the command-line workflow."""
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("action", choices=("build", "start", "api", "verify", "check-inputs"))
    parser.add_argument("--mode", choices=("preview", "published"), default=os.environ.get("DOCSITE_MODE", "preview"))
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
    parser.add_argument("--host", default="127.0.0.1", help="Preview server bind address for start")
    parser.add_argument("--port", type=int, default=3000, help="Preview server port for start")
    args = parser.parse_args()
    if args.javadoc_source not in ("auto", "source"):
        parser.error("DOCSITE_JAVADOC_SOURCE must be auto or source")
    if args.javadoc_cache not in ("use", "refresh", "off"):
        parser.error("DOCSITE_JAVADOC_CACHE must be use, refresh, or off")
    project = Project(ROOT)
    timings = BuildTimings()
    try:
        with timings.measure("release-selection"):
            records = json.loads(args.releases.read_text()) if args.releases else None
            plan = project.plan(args.mode, records, pending_commit=args.pending_commit)
        if args.action == "check-inputs":
            manifest = json.loads((ROOT / "target/pages/release-manifest.json").read_text())
            assert manifest["sourceDigest"] == source_digest(ROOT), "Stale deployment source inputs"
            for field in ("mode", "baseUrl", "origin", "latestVersion", "versions"):
                assert manifest[field] == plan[field], f"Stale deployment release selection: {field}"
            release_digest = hashlib.sha256(
                json.dumps(sorted(records or [], key=lambda r: r["tag_name"]), sort_keys=True).encode()
            ).hexdigest()
            assert manifest["releaseInputsDigest"] == release_digest, "Stale deployment release asset records"
            head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
            assert head == manifest["builderCommit"], "Stale deployment builder revision"
            print("Deployment source inputs and release selection passed.")
            return
        if args.action == "verify":
            with timings.measure("dependency-inventory"):
                subprocess.run(
                    [sys.executable, str(ROOT / "scripts/dependency_docs.py"), "check"], cwd=ROOT, check=True
                )
            with timings.measure("executable-docs"):
                subprocess.run([sys.executable, str(ROOT / "scripts/check_docs.py")], cwd=ROOT, check=True)
            with timings.measure("release-metadata"):
                subprocess.run([sys.executable, str(ROOT / "scripts/docs_release.py"), "check"], cwd=ROOT, check=True)
        initial_digest = source_digest(ROOT)
        build(
            ROOT,
            plan,
            releases=records,
            artifact_mode=args.javadoc_source,
            cache_mode=args.javadoc_cache,
            timings=timings,
        )
        project.write_manifest(plan)
        if args.action != "api":
            with timings.measure("maven-site"):
                render(ROOT, plan)
                manifest_path = ROOT / "target/pages/release-manifest.json"
                manifest = json.loads(manifest_path.read_text())
                assert source_digest(ROOT) == initial_digest, "Documentation source inputs changed during generation"
                manifest["releaseInputsDigest"] = hashlib.sha256(
                    json.dumps(sorted(records or [], key=lambda r: r["tag_name"]), sort_keys=True).encode()
                ).hexdigest()
                manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
        if args.action == "start":
            subprocess.run(
                [sys.executable, str(ROOT / "scripts/serve_docs.py"), "--host", args.host, "--port", str(args.port)],
                cwd=ROOT,
                check=True,
            )
        if args.action == "verify":
            with timings.measure("rendered-links"):
                subprocess.run([sys.executable, str(ROOT / "scripts/check_site.py")], cwd=ROOT, check=True)
    finally:
        timings.write(
            ROOT / "target/docsite/build-timings.json",
            scope="documentation orchestration only; excludes outer Maven lifecycle and artifact upload",
            action=args.action,
            mode=args.mode,
            javadocCache=args.javadoc_cache,
            javadocSource=args.javadoc_source,
        )


if __name__ == "__main__":
    main()
