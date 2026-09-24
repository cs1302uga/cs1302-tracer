#!/usr/bin/env python3
"""Prepare, validate, and select documentation release snapshots."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / 'website'
VERSION = re.compile(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\Z')


def versions():
    file = SITE / 'versions.json'
    return json.loads(file.read_text()) if file.exists() else []


def project_version():
    return ET.parse(ROOT / 'pom.xml').getroot().find('{*}version').text


def files(folder):
    return {p.relative_to(folder).as_posix(): p.read_bytes()
            for p in folder.rglob('*') if p.is_file()}


def check(tag=None, base=None):
    saved = versions()
    assert len(saved) == len(set(saved)), 'Duplicate documentation versions'
    assert all(VERSION.fullmatch(v) for v in saved), 'Only stable x.y.z snapshots are supported'
    assert saved == sorted(saved, key=lambda v: tuple(map(int, v.split('.'))), reverse=True), \
        'Snapshots must be ordered newest first'
    for version in saved:
        assert (SITE / f'versioned_docs/version-{version}/index.md').is_file(), \
            f'Missing documentation snapshot for {version}'
        assert (SITE / f'versioned_sidebars/version-{version}-sidebars.json').is_file(), \
            f'Missing sidebar snapshot for {version}'
    if base:
        listed = subprocess.check_output(['git', 'ls-tree', '--name-only', base, 'website/versions.json'], cwd=ROOT, text=True).strip()
        previous = json.loads(subprocess.check_output(['git', 'show', f'{base}:website/versions.json'], cwd=ROOT, text=True)) if listed else []
        assert set(previous).issubset(saved), 'Existing documentation versions must be retained'
    if tag:
        version = tag.removeprefix('v')
        assert VERSION.fullmatch(version), 'Release tags must be x.y.z or vx.y.z'
        assert version == project_version(), 'Release tag does not match pom.xml'
        assert version in saved, 'Prepare and commit the documentation snapshot before tagging'
        assert files(ROOT / 'docs') == files(SITE / f'versioned_docs/version-{version}'), \
            'Release snapshot differs from docs/. Refresh it before tagging.'
        # A sidebar change after snapshot preparation must also invalidate the release.
        current = subprocess.check_output(['node', '-e',
            'process.stdout.write(JSON.stringify(require("./sidebars.js")))'], cwd=SITE, text=True)
        archived = json.loads((SITE / f'versioned_sidebars/version-{version}-sidebars.json').read_text())
        assert json.loads(current) == archived, 'Release sidebar differs from current sidebar'
    print('Documentation release metadata passed.')


def select(releases):
    """Choose only published stable releases, ordered by numeric version, not creation time."""
    tags = {r['tag_name'].removeprefix('v') for r in releases
            if not r.get('draft') and not r.get('prerelease')}
    return sorted((v for v in versions() if v in tags),
                  key=lambda v: tuple(map(int, v.split('.'))), reverse=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('prepare')
    validation = sub.add_parser('check')
    validation.add_argument('--tag')
    validation.add_argument('--base', help='Base commit whose versions must be retained')
    published = sub.add_parser('published')
    published.add_argument('releases', type=Path, help='JSON array from GitHub releases API')
    args = parser.parse_args()
    if args.command == 'prepare':
        version = project_version()
        assert VERSION.fullmatch(version), 'Set a stable x.y.z Maven version before preparing docs'
        tags = subprocess.check_output(['git', 'tag', '--list', version, 'v' + version], cwd=ROOT, text=True).strip()
        assert not tags, 'This version is already tagged; choose the next release version'
        assert version not in versions(), 'Snapshot already exists; review/edit it explicitly'
        assert not versions() or tuple(map(int, version.split('.'))) > tuple(map(int, versions()[0].split('.'))), \
            'New snapshots must increase the version'
        subprocess.run(['npm', 'run', 'docusaurus', '--', 'docs:version', version], cwd=SITE, check=True)
        check(version)
        print(f'Review and commit docs, versions.json, versioned_docs, and versioned_sidebars before tagging v{version}.')
    elif args.command == 'check':
        check(args.tag, args.base)
    else:
        check()
        selected = select(json.loads(args.releases.read_text()))
        (SITE / 'published-versions.json').write_text(json.dumps(selected, indent=2) + '\n')
        print(f'Published versions: {selected}; development remains available.')


if __name__ == '__main__':
    main()
