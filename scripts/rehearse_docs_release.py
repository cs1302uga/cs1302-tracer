#!/usr/bin/env python3
"""Rehearse snapshots and publishing in a disposable tree without GitHub writes."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    with tempfile.TemporaryDirectory(prefix='tracer-docs-rehearsal-') as temporary:
        root = Path(temporary)
        for name in ('docs', 'scripts'):
            shutil.copytree(ROOT / name, root / name, ignore=shutil.ignore_patterns('__pycache__'))
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
            (root / 'pom.xml').write_text(f'<project><version>{version}</version></project>')
            run('python3', 'scripts/docs_release.py', 'prepare')
            run('python3', 'scripts/docs_release.py', 'check', '--tag', 'v' + version)

        releases = root / 'releases.json'
        # The newer prepared snapshot must stay private until its software release exists.
        releases.write_text(json.dumps([{'tag_name': 'v9998.0.0'}]))
        run('python3', 'scripts/docs_release.py', 'published', str(releases))
        run('npm', '--prefix', 'website', 'run', 'build')
        run('python3', 'scripts/check_site.py')
        assert (site / 'build/9998.0.0/index.html').exists()
        assert (site / 'build/next/index.html').exists()
        assert not (site / 'build/9999.0.0').exists()
        assert '/9998.0.0/' in (site / 'build/index.html').read_text()

        releases.write_text(json.dumps([{'tag_name': 'v9998.0.0'}, {'tag_name': 'v9999.0.0'}]))
        run('python3', 'scripts/docs_release.py', 'published', str(releases))
        run('npm', '--prefix', 'website', 'run', 'build')
        run('python3', 'scripts/check_site.py')
        assert (site / 'build/9998.0.0/index.html').exists()
        assert (site / 'build/9999.0.0/index.html').exists()
        assert '/9999.0.0/' in (site / 'build/index.html').read_text()
        # Retry selection is deterministic and retains both release versions.
        selected = (site / 'published-versions.json').read_text()
        run('python3', 'scripts/docs_release.py', 'published', str(releases))
        assert selected == (site / 'published-versions.json').read_text()
        print('Release rehearsal passed: hidden pending snapshot, stable default, archives, and retry.')


if __name__ == '__main__':
    main()
