"""Exercise the documentation CLI with real projects and external tool stand-ins."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parent


class DocumentationSiteTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.site = self.root / 'website'
        self.site.mkdir()
        scripts = self.root / 'scripts'
        scripts.mkdir()
        for name in ('docs_site.py', 'docs_release.py', 'build_javadoc.py'):
            shutil.copy2(SCRIPTS / name, scripts / name)
        (self.root / 'pom.xml').write_text('<project><version>1.0.0</version></project>')
        (self.root / 'source.txt').write_text('released source')
        (self.site / 'versions.json').write_text('["1.0.0"]')
        snapshot = self.site / 'versioned_docs/version-1.0.0'
        snapshot.mkdir(parents=True)
        (snapshot / 'index.md').write_text('# Release')
        sidebars = self.site / 'versioned_sidebars'
        sidebars.mkdir()
        (sidebars / 'version-1.0.0-sidebars.json').write_text('{}')
        self.git('init', '-q')
        self.git('add', '.')
        self.git('commit', '-qm', 'Release')
        self.git('tag', 'v1.0.0')
        (self.root / 'source.txt').write_text('working source')
        self.log = self.root / 'tools.jsonl'
        self.environment = dict(os.environ, DOCS_TEST_LOG=str(self.log))
        self.executable(self.root / 'mvnw', '''
import json, os
from pathlib import Path
import sys
source = Path.cwd()
with open(os.environ['DOCS_TEST_LOG'], 'a') as log:
    log.write(json.dumps(['maven', source.joinpath('source.txt').read_text()]) + '\\n')
if os.environ.get('DOCS_TEST_FAIL_RELEASE') and source.joinpath('source.txt').read_text() == 'released source':
    sys.exit(1)
output = source / 'target/reports/apidocs'
output.mkdir(parents=True, exist_ok=True)
output.joinpath('index.html').write_text(source.joinpath('source.txt').read_text())
''')
        binaries = self.root / 'bin'
        binaries.mkdir()
        self.executable(binaries / 'npm', '''
import json, os, shutil
from pathlib import Path
import sys
with open(os.environ['DOCS_TEST_LOG'], 'a') as log:
    log.write(json.dumps(['npm', *sys.argv[1:]]) + '\\n')
if os.environ.get('DOCS_TEST_FAIL_NPM'):
    sys.exit(1)
if 'build' in sys.argv:
    output = Path('build')
    if '--out-dir' in sys.argv:
        output = Path(sys.argv[sys.argv.index('--out-dir') + 1])
    output.mkdir(parents=True, exist_ok=True)
    output.joinpath('index.html').write_text('completed site')
    shutil.copytree('generated-api/api', output / 'api', dirs_exist_ok=True)
''')
        self.environment['PATH'] = str(binaries) + os.pathsep + os.environ['PATH']

    def executable(self, path, body):
        path.write_text('#!' + sys.executable + '\n' + body)
        path.chmod(0o755)

    def git(self, *args):
        return subprocess.check_output(['git', '-c', 'user.name=Docs Test',
                                        '-c', 'user.email=docs@example.invalid',
                                        '-c', 'commit.gpgsign=false', '-c', 'tag.gpgSign=false',
                                        '-c', 'core.hooksPath=/dev/null', *args],
                                       cwd=self.root, text=True).strip()

    def run_site(self, *args):
        return subprocess.run([sys.executable, str(self.root / 'scripts/docs_site.py'), *args],
                              cwd=self.root, env=self.environment, text=True, capture_output=True)

    def test_rejects_output_relocation_before_generating_or_changing_a_build(self):
        completed = self.site / 'build'
        completed.mkdir()
        manifest = completed / 'release-manifest.json'
        manifest.write_text('{"previous": "published"}')
        for options in (('--out-dir', 'custom'), ('--out-dir=custom',), ('other-site',)):
            with self.subTest(options=options):
                result = self.run_site('build', *options)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('unrecognized arguments', result.stderr)
                self.assertFalse(self.log.exists(), 'Invalid options must fail before invoking tools')
                self.assertEqual(manifest.read_text(), '{"previous": "published"}')
                self.assertFalse((self.site / 'release-manifest.json').exists())

    def test_development_server_accepts_host_and_port_options(self):
        result = self.run_site('start', '--host', '127.0.0.1', '--port=3100', '--no-open')
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertIn(['npm', 'run', 'docusaurus', '--', 'start', '--host', '127.0.0.1',
                       '--port=3100', '--no-open'], calls)

    def test_build_keeps_pinned_release_api_and_its_manifest_together(self):
        result = self.run_site('build', '--no-minify')
        self.assertEqual(result.returncode, 0, result.stderr)
        completed = self.site / 'build'
        self.assertEqual((completed / 'api/next/index.html').read_text(), 'working source')
        self.assertEqual((completed / 'api/1.0.0/index.html').read_text(), 'released source')
        manifest = json.loads((completed / 'release-manifest.json').read_text())
        self.assertEqual(manifest['versions'][1]['sourceCommit'], self.git('rev-parse', 'v1.0.0^{commit}'))
        self.assertEqual(manifest['latestVersion'], '1.0.0')

    def test_release_generation_failure_retains_previous_complete_api(self):
        result = self.run_site('api')
        self.assertEqual(result.returncode, 0, result.stderr)
        previous_manifest = (self.site / 'release-manifest.json').read_bytes()
        (self.root / 'source.txt').write_text('new working source')
        self.environment['DOCS_TEST_FAIL_RELEASE'] = '1'
        result = self.run_site('api')
        self.assertNotEqual(result.returncode, 0)
        api = self.site / 'generated-api/api'
        self.assertEqual((api / 'next/index.html').read_text(), 'working source')
        self.assertEqual((api / '1.0.0/index.html').read_text(), 'released source')
        self.assertEqual((self.site / 'release-manifest.json').read_bytes(), previous_manifest)
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertEqual(calls[-2:], [['maven', 'new working source'], ['maven', 'released source']])

    def test_failed_site_command_does_not_publish_a_new_build_manifest(self):
        records = self.root / 'releases.json'
        records.write_text('[]')
        result = self.run_site('build', '--mode', 'published', '--releases', str(records))
        self.assertEqual(result.returncode, 0, result.stderr)
        completed_manifest = self.site / 'build/release-manifest.json'
        previous = completed_manifest.read_bytes()
        self.assertEqual(json.loads(previous)['mode'], 'published')
        self.environment['DOCS_TEST_FAIL_NPM'] = '1'
        result = self.run_site('build')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(completed_manifest.read_bytes(), previous)
        self.assertEqual(json.loads((self.site / 'release-manifest.json').read_text())['mode'], 'preview')


if __name__ == '__main__':
    unittest.main()
