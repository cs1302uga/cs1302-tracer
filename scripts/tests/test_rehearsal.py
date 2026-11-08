"""Run the release rehearsal with real Git and external build-tool stand-ins."""

import tempfile
import unittest
from pathlib import Path

from scripts import rehearse_docs_release

from tests.support import FAKE_SITE, copy_scripts, executable, invoke, tool_environment


class RehearsalTests(unittest.TestCase):
    """Verify release retention and failure checks across the whole rehearsal CLI."""

    def test_import_does_not_run_the_rehearsal(self) -> None:
        """Importing the workflow exposes its callable without building or changing files."""
        self.assertTrue(rehearse_docs_release.ROOT.is_dir())

    def test_rehearsal_checks_release_sequence_and_missing_tag(self) -> None:
        """Rehearse unpublished snapshots, publication, retries, and removed provenance."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            copy_scripts(root)
            for folder in ("docs", "src/site/resources", "src/site/landing", ".mvn"):
                (root / folder).mkdir(parents=True)
            (root / "docs/index.md").write_text("# Documentation\n")
            (root / "pom.xml").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                "<version>1.0.0</version><properties><maven-site-plugin.version>3.22.0</maven-site-plugin.version>"
                "</properties></project>"
            )
            (root / "src/site/navigation.json").write_text('{"docs":["index"]}')
            (root / "src/site/site.xml").write_text("<site><body/></site>")
            (root / "src/site/landing/index.md").write_text("# Home\n")
            (root / "src/site/resources/dependencies.js").write_text("")
            (root / "src/site/resources/dependencies.css").write_text("")
            executable(
                root / "mvnw",
                """
import os,sys
from pathlib import Path
if 'site:site' in sys.argv:
    exec(os.environ['DOCS_TEST_RENDERER'])
output = Path('target/reports/apidocs')
output.mkdir(parents=True, exist_ok=True)
for name in ('index.html', 'search.html', 'element-list'):
    (output / name).write_text('API content')
""",
            )
            env = tool_environment(root)
            env["DOCS_TEST_RENDERER"] = FAKE_SITE
            result = invoke(root, "rehearse_docs_release.py", env=env)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("Release rehearsal passed", result.stdout)
            self.assertIn("missing tag", result.stdout)
            self.assertFalse((root / "target/pages").exists(), "Rehearsal must not overwrite the source project")
