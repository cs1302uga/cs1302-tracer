#!/usr/bin/env python3
"""Generate development and selected release API references for the documentation site."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

import docs_release

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / 'website'
OUTPUT = SITE / 'generated-api'


def generate(source, destination):
    generated = source / 'target/reports/apidocs'
    if generated.exists():
        shutil.rmtree(generated)
    subprocess.run([str(ROOT / 'mvnw'), '-B', '-ntp', '-f', str(source / 'pom.xml'),
                    '-Djacoco.skip=true', 'javadoc:javadoc'], cwd=source, check=True)
    if not (generated / 'index.html').is_file():
        raise RuntimeError(f'Javadoc index missing in {generated}')
    shutil.copytree(generated, destination)


def main():
    docs_release.check()
    selection = SITE / 'published-versions.json'
    published = selection.exists()
    versions = json.loads(selection.read_text()) if published else docs_release.versions()
    if len(versions) != len(set(versions)) or not set(versions).issubset(docs_release.versions()):
        raise ValueError('API versions must be selected from documentation snapshots')
    # Stage everything before replacing the output; a failed release build must not
    # leave a partially generated site or silently substitute development sources.
    with tempfile.TemporaryDirectory(prefix='tracer-javadoc-') as temporary:
        staging = Path(temporary)
        api = staging / 'api'
        api.mkdir()
        generate(ROOT, api / 'next')
        for version in versions:
            tags = subprocess.check_output(
                ['git', 'tag', '--list', version, 'v' + version], cwd=ROOT, text=True).splitlines()
            if not tags:
                if not published and version == docs_release.project_version():
                    shutil.copytree(api / 'next', api / version)
                    continue
                raise ValueError(f'Missing release tag for {version}; fetch tags before building API docs')
            commits = {subprocess.check_output(['git', 'rev-parse', tag + '^{commit}'],
                                               cwd=ROOT, text=True).strip() for tag in tags}
            if len(commits) != 1:
                raise ValueError(f'Ambiguous release tags for {version}')
            source = staging / ('source-' + version)
            source.mkdir()
            archive = staging / 'source.tar'
            subprocess.run(['git', 'archive', '--format=tar', '-o', str(archive), commits.pop()],
                           cwd=ROOT, check=True)
            subprocess.run(['tar', '-xf', str(archive), '-C', str(source)], check=True)
            actual = ET.parse(source / 'pom.xml').getroot().find('{*}version').text
            if actual != version:
                raise ValueError(f'Release tag for {version} contains Maven version {actual}')
            generate(source, api / version)
        if OUTPUT.exists():
            shutil.rmtree(OUTPUT)
        OUTPUT.mkdir()
        shutil.copytree(api, OUTPUT / 'api')
    print(f'Generated API reference for development and {len(versions)} releases.')


if __name__ == '__main__':
    main()
