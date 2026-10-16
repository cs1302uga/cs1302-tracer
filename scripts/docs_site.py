#!/usr/bin/env python3
"""Build or preview documentation with an explicit release selection mode."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess

from build_javadoc import build
from docs_release import Project

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument('action', choices=('build', 'start', 'api'))
    parser.add_argument('--mode', choices=('preview', 'published'), default='preview')
    parser.add_argument('--releases', type=Path, help='GitHub release records; required in published mode')
    args, remaining = parser.parse_known_args()
    # The release manifest, checker, and preview server all own website/build.
    # Validate forwarded flags so a positional site directory or output/config
    # override cannot silently move the build away from its manifest.
    forwarded = argparse.ArgumentParser(add_help=False, allow_abbrev=False)
    if args.action in ('start', 'build'):
        forwarded.add_argument('--no-minify', action='store_true')
    if args.action == 'start':
        forwarded.add_argument('--host')
        forwarded.add_argument('-p', '--port')
        forwarded.add_argument('--no-open', action='store_true')
    forwarded.parse_args(remaining)
    records = json.loads(args.releases.read_text()) if args.releases else None
    project = Project(ROOT)
    plan = project.plan(args.mode, records)
    build(ROOT, plan)
    manifest = project.write_manifest(plan)
    if args.action != 'api':
        subprocess.run(['npm', 'run', 'docusaurus', '--', args.action, *remaining],
                       cwd=project.site, check=True)
        if args.action == 'build':
            shutil.copy2(manifest, project.site / 'build/release-manifest.json')


if __name__ == '__main__':
    main()
