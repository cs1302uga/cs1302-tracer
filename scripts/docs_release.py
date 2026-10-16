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


class Project:
    """Own documentation release validation, selection, routes, and source provenance."""
    def __init__(self, root):
        self.root = Path(root)
        self.site = self.root / "website"
    def plan(self, mode, releases=None):
        """Resolve documentation versions and their source commits before building."""
        self.check()
        if mode not in ('preview', 'published'):
            raise ValueError('Unknown documentation mode')
        if mode == 'published' and releases is None:
            raise ValueError('Published mode requires release records')
        if mode == 'preview' and releases is not None:
            raise ValueError('Release records require published mode')
        selected = self.versions() if mode == 'preview' else self.select(releases)
        base = '/cs1302-tracer/'
        latest = selected[0] if selected else None
        entries = [{
            'name': 'current', 'label': 'Development (main)',
            'docsPath': 'next' if latest else '/',
            'docsUrl': base + ('next/' if latest else ''),
            'javadocPath': 'api/next', 'javadocUrl': base + 'api/next/index.html',
            'sourceCommit': None, 'banner': 'unreleased' if latest else 'none',
        }]
        for version in selected:
            tags = subprocess.check_output(['git', 'tag', '--list', version, 'v' + version],
                                           cwd=self.root, text=True).splitlines()
            commit = None
            if tags:
                commits = {subprocess.check_output(['git', 'rev-parse', tag + '^{commit}'],
                                                   cwd=self.root, text=True).strip() for tag in tags}
                if len(commits) != 1:
                    raise ValueError(f'Ambiguous release tags for {version}')
                commit = commits.pop()
                pom = subprocess.check_output(['git', 'show', commit + ':pom.xml'], cwd=self.root, text=True)
                actual = ET.fromstring(pom).find('{*}version').text
                if actual != version:
                    raise ValueError(f'Release tag for {version} contains Maven version {actual}')
            elif mode == 'published' or version != self.project_version():
                raise ValueError(f'Missing release tag for {version}')
            entries.append({
                'name': version, 'label': version, 'docsPath': version,
                'docsUrl': base + version + '/', 'javadocPath': 'api/' + version,
                'javadocUrl': base + 'api/' + version + '/index.html',
                'sourceCommit': commit, 'banner': 'none',
            })
        return {'mode': mode, 'baseUrl': base, 'origin': 'https://cs1302uga.github.io',
                'latestVersion': latest, 'versions': entries}
    def versions(self):
        file = self.site / 'versions.json'
        return json.loads(file.read_text()) if file.exists() else []

    def write_manifest(self, plan):
        """Make the selected release decisions available to the site tooling."""
        path = self.site / 'release-manifest.json'
        path.write_text(json.dumps(plan, indent=2) + '\n')
        return path

    def project_version(self):
        return ET.parse(self.root / 'pom.xml').getroot().find('{*}version').text

    @staticmethod
    def files(folder):
        return {p.relative_to(folder).as_posix(): p.read_bytes()
                for p in folder.rglob('*') if p.is_file()}

    def check(self, tag=None, base=None):
        saved = self.versions()
        assert len(saved) == len(set(saved)), 'Duplicate documentation versions'
        assert all(VERSION.fullmatch(v) for v in saved), 'Only stable x.y.z snapshots are supported'
        assert saved == sorted(saved, key=lambda v: tuple(map(int, v.split('.'))), reverse=True), \
            'Snapshots must be ordered newest first'
        for version in saved:
            assert (self.site / f'versioned_docs/version-{version}/index.md').is_file(), \
                f'Missing documentation snapshot for {version}'
            assert (self.site / f'versioned_sidebars/version-{version}-sidebars.json').is_file(), \
                f'Missing sidebar snapshot for {version}'
        if base:
            listed = subprocess.check_output(['git', 'ls-tree', '--name-only', base, 'website/versions.json'], cwd=self.root, text=True).strip()
            previous = json.loads(subprocess.check_output(['git', 'show', f'{base}:website/versions.json'], cwd=self.root, text=True)) if listed else []
            assert set(previous).issubset(saved), 'Existing documentation versions must be retained'
        if tag:
            version = tag.removeprefix('v')
            assert VERSION.fullmatch(version), 'Release tags must be x.y.z or vx.y.z'
            assert version == self.project_version(), 'Release tag does not match pom.xml'
            assert version in saved, 'Prepare and commit the documentation snapshot before tagging'
            assert self.files(self.root / 'docs') == self.files(self.site / f'versioned_docs/version-{version}'), \
                'Release snapshot differs from docs/. Refresh it before tagging.'
            # A sidebar change after snapshot preparation must also invalidate the release.
            current = subprocess.check_output(['node', '-e',
                'process.stdout.write(JSON.stringify(require("./sidebars.js")))'], cwd=self.site, text=True)
            archived = json.loads((self.site / f'versioned_sidebars/version-{version}-sidebars.json').read_text())
            assert json.loads(current) == archived, 'Release sidebar differs from current sidebar'
        print('Documentation release metadata passed.')

    def select(self, releases):
        """Choose only published stable releases, ordered by numeric version, not creation time."""
        tags = {r['tag_name'].removeprefix('v') for r in releases
                if not r.get('draft') and not r.get('prerelease')}
        return sorted((v for v in self.versions() if v in tags),
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
    project = Project(ROOT)
    if args.command == 'prepare':
        version = project.project_version()
        assert VERSION.fullmatch(version), 'Set a stable x.y.z Maven version before preparing docs'
        tags = subprocess.check_output(['git', 'tag', '--list', version, 'v' + version], cwd=ROOT, text=True).strip()
        assert not tags, 'This version is already tagged; choose the next release version'
        assert version not in project.versions(), 'Snapshot already exists; review/edit it explicitly'
        assert not project.versions() or tuple(map(int, version.split('.'))) > tuple(map(int, project.versions()[0].split('.'))), \
            'New snapshots must increase the version'
        project.write_manifest(project.plan('preview'))
        subprocess.run(['npm', 'run', 'docusaurus', '--', 'docs:version', version], cwd=SITE, check=True)
        project.check(version)
        project.write_manifest(project.plan('preview'))
        print(f'Review and commit docs, versions.json, versioned_docs, and versioned_sidebars before tagging v{version}.')
    elif args.command == 'check':
        project.check(args.tag, args.base)
    else:
        project.check()
        selected = project.select(json.loads(args.releases.read_text()))
        (SITE / 'published-versions.json').write_text(json.dumps(selected, indent=2) + '\n')
        print(f'Published versions: {selected}; development remains available.')


if __name__ == '__main__':
    main()
