"""Run the release rehearsal with real Git and external build-tool stand-ins."""

import tempfile
import unittest
from pathlib import Path

from tests.support import copy_scripts, executable, invoke, tool_environment


class RehearsalTests(unittest.TestCase):
    """Verify release retention and failure checks across the whole rehearsal CLI."""

    def test_rehearsal_checks_release_sequence_and_missing_tag(self) -> None:
        """Rehearse unpublished snapshots, publication, retries, and removed provenance."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            copy_scripts(root)
            for folder in ("docs", "src", ".mvn", "website/src"):
                (root / folder).mkdir(parents=True)
            (root / "docs/index.md").write_text("# Documentation\n")
            (root / "pom.xml").write_text("<project><version>1.0.0</version></project>")
            for name in ("package.json", "package-lock.json", "docusaurus.config.js"):
                (root / "website" / name).write_text("{}")
            (root / "website/sidebars.js").write_text("module.exports = {};")
            executable(
                root / "mvnw",
                """
from pathlib import Path
output = Path('target/reports/apidocs')
output.mkdir(parents=True, exist_ok=True)
for name in ('index.html', 'search.html', 'element-list'):
    (output / name).write_text('API content')
""",
            )
            env = tool_environment(root)
            executable(
                root / "bin/npm",
                """
import json, shutil, subprocess, sys
from pathlib import Path
args = sys.argv[1:]
if args[0] == 'ci':
    sys.exit(0)
if 'docs:version' in args:
    version = args[-1]
    shutil.copytree('../docs', 'versioned_docs/version-' + version)
    Path('versioned_sidebars').mkdir(exist_ok=True)
    Path('versioned_sidebars/version-' + version + '-sidebars.json').write_text('{}')
    path = Path('versions.json')
    previous = json.loads(path.read_text()) if path.exists() else []
    path.write_text(json.dumps([version, *previous]))
elif '--prefix' in args:
    subprocess.run([sys.executable, 'scripts/docs_site.py', 'build', *args[args.index('--') + 1:]], check=True)
else:
    plan = json.loads(Path('release-manifest.json').read_text())
    output = Path('build')
    if output.exists():
        shutil.rmtree(output)
    output.mkdir()
    shutil.copytree('generated-api/api', output / 'api')
    for entry in plan['versions']:
        dest = output / entry['docsPath'].strip('/')
        dest.mkdir(parents=True, exist_ok=True)
        (dest / 'index.html').write_text('<a href="' + entry['javadocUrl'] + '">API</a>')
    (output / 'index.html').write_text('<meta http-equiv="refresh" content="0;url=/' + plan['latestVersion'] + '/">')
""",
            )
            result = invoke(root, "rehearse_docs_release.py", env=env)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("Release rehearsal passed", result.stdout)
            self.assertIn("missing tag", result.stdout)
            self.assertFalse((root / "website/build").exists(), "Rehearsal must not overwrite the source project")
