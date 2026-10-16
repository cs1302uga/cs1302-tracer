"""Check actionable failures for incomplete documentation inputs."""

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts import check_docs, docs_release


class InputDiagnosticsTests(unittest.TestCase):
    """Validate failures through the documentation tooling's callable interfaces."""

    def test_marked_example_requires_a_code_block(self) -> None:
        """Report which example lacks its required code block."""
        with self.assertRaisesRegex(ValueError, "quickstart.*code block"):
            check_docs.block("<!-- quickstart:start -->\nNo code\n<!-- quickstart:end -->", "quickstart")

    def test_project_requires_a_nonempty_maven_version(self) -> None:
        """Reject missing Maven version metadata before release selection."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for xml in ("<project/>", "<project><version/></project>"):
                with self.subTest(xml=xml):
                    (root / "pom.xml").write_text(xml)
                    with self.assertRaisesRegex(ValueError, "Maven.*version"):
                        docs_release.Project(root).project_version()

    def test_importing_script_modules_does_not_run_commands(self) -> None:
        """Allow reuse of script interfaces without triggering their CLI workflows."""
        scripts = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                [
                    sys.executable,
                    "-c",
                    "import build_javadoc, check_docs, check_external_links, check_site; "
                    "import docs_release, docs_site, rehearse_docs_release, serve_docs",
                ],
                cwd=directory,
                env=dict(os.environ, PYTHONPATH=str(scripts)),
                text=True,
                capture_output=True,
                timeout=10,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, "")
            self.assertEqual(list(Path(directory).iterdir()), [])
