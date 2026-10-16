#!/usr/bin/env python3
"""Rehearse snapshots and publishing in a disposable tree without GitHub writes."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main():
    with tempfile.TemporaryDirectory(prefix='tracer-docs-rehearsal-') as temporary:
        root = Path(temporary)
        for name in ('docs', 'scripts', 'src', '.mvn'):
            shutil.copytree(ROOT / name, root / name, ignore=shutil.ignore_patterns('__pycache__'))
        shutil.copy2(ROOT / 'mvnw', root / 'mvnw')
        site = root / 'website'
        site.mkdir()
        for name in ('package.json', 'package-lock.json', 'docusaurus.config.js', 'sidebars.js'):
            shutil.copy2(ROOT / 'website' / name, site / name)
        shutil.copytree(ROOT / 'website/src', site / 'src')
        # Isolate bundler caches as well as content; sharing node_modules can mix versions.
        subprocess.run(['npm', 'ci', '--no-audit', '--no-fund'], cwd=site, check=True)
        subprocess.run(['git', 'init', '-q', str(root)], check=True)

        def run(*args):
            subprocess.run(args, cwd=root, check=True)

        for version in ('9998.0.0', '9999.0.0'):
            pom = (ROOT / 'pom.xml').read_text()
            current = ET.fromstring(pom).find('{*}version').text
            (root / 'pom.xml').write_text(pom.replace(f'<version>{current}</version>',
                                                    f'<version>{version}</version>', 1))
            run('python3', 'scripts/docs_release.py', 'prepare')
            run('python3', 'scripts/docs_release.py', 'check', '--tag', 'v' + version)
            run('git', 'add', 'pom.xml', 'src', 'mvnw', '.mvn')
            run('git', '-c', 'user.name=Docs Test', '-c', 'user.email=docs@example.invalid',
                '-c', 'commit.gpgsign=false', 'commit', '-qm', version)
            run('git', '-c', 'tag.gpgSign=false', 'tag', 'v' + version)

        releases = root / 'releases.json'
        # The newer prepared snapshot must stay private until its software release exists.
        releases.write_text(json.dumps([{'tag_name': 'v9998.0.0'}]))
        run('python3', 'scripts/docs_release.py', 'published', str(releases))
        run('npm', '--prefix', 'website', 'run', 'build', '--', '--mode', 'published',
            '--releases', str(releases))
        run('python3', 'scripts/check_site.py')
        assert (site / 'build/9998.0.0/index.html').exists()
        assert (site / 'build/next/index.html').exists()
        assert not (site / 'build/9999.0.0').exists()
        assert not (site / 'build/api/9999.0.0').exists()
        assert '/9998.0.0/' in (site / 'build/index.html').read_text()

        releases.write_text(json.dumps([{'tag_name': 'v9998.0.0'}, {'tag_name': 'v9999.0.0'}]))
        run('python3', 'scripts/docs_release.py', 'published', str(releases))
        run('npm', '--prefix', 'website', 'run', 'build', '--', '--mode', 'published',
            '--releases', str(releases))
        run('python3', 'scripts/check_site.py')
        assert (site / 'build/9998.0.0/index.html').exists()
        assert (site / 'build/9999.0.0/index.html').exists()
        assert (site / 'build/api/9998.0.0/index.html').exists()
        assert (site / 'build/api/9999.0.0/index.html').exists()
        assert '/9999.0.0/' in (site / 'build/index.html').read_text()
        # Retry selection is deterministic and retains both release versions.
        selected = (site / 'published-versions.json').read_text()
        run('python3', 'scripts/docs_release.py', 'published', str(releases))
        assert selected == (site / 'published-versions.json').read_text()
        # A published release must never fall back to the current working source,
        # even when its version matches the current POM. Failed generation also
        # leaves the previous complete API output available for inspection.
        api_index = site / 'generated-api/api/9999.0.0/index.html'
        previous_index = api_index.read_bytes()
        run('git', 'tag', '-d', 'v9999.0.0')
        missing = subprocess.run(['python3', 'scripts/docs_site.py', 'api', '--mode', 'published',
                                  '--releases', str(releases)], cwd=root,
                                 text=True, capture_output=True)
        assert missing.returncode != 0 and 'Missing release tag for 9999.0.0' in missing.stderr
        assert api_index.read_bytes() == previous_index
        print('Release rehearsal passed: hidden pending snapshot, stable default, archives, retry, and missing tag.')


if __name__ == '__main__':
    main()
