"""Exercise preparation and release reporting through the script CLI."""

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from tests.support import copy_scripts, executable, invoke, tool_environment


class ReleaseCliTests(unittest.TestCase):
    """Check release preparation's externally visible files and diagnostics."""

    def setUp(self) -> None:
        """Create a Git repository and external Docusaurus stand-in."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        copy_scripts(self.root)
        self.site = self.root / "website"
        self.site.mkdir()
        (self.root / "docs").mkdir()
        (self.root / "docs/index.md").write_text("# Current\n")
        (self.root / "pom.xml").write_text("<project><version>1.0.0</version></project>")
        (self.site / "sidebars.js").write_text("module.exports = {};")
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        self.env = tool_environment(self.root)
        executable(
            self.root / "bin/npm",
            """
import json, shutil, sys
from pathlib import Path
version = sys.argv[-1]
shutil.copytree('../docs', 'versioned_docs/version-' + version)
Path('versioned_sidebars').mkdir(exist_ok=True)
Path('versioned_sidebars/version-' + version + '-sidebars.json').write_text('{}')
p = Path('versions.json')
versions = json.loads(p.read_text()) if p.exists() else []
p.write_text(json.dumps([version, *versions]))
""",
        )

    def test_prepare_check_and_published_report(self) -> None:
        """Prepare snapshots, validate tags, and export only published versions."""
        for version in ("1.0.0", "2.0.0"):
            (self.root / "pom.xml").write_text(f"<project><version>{version}</version></project>")
            result = invoke(self.root, "docs_release.py", "prepare", env=self.env)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual((self.site / f"versioned_docs/version-{version}/index.md").read_text(), "# Current\n")
            if version == "1.0.0":
                for arguments in (
                    ["add", "."],
                    [
                        "-c",
                        "user.name=Tests",
                        "-c",
                        "user.email=tests@example.test",
                        "-c",
                        "commit.gpgsign=false",
                        "commit",
                        "-qm",
                        "release",
                    ],
                    ["-c", "tag.gpgSign=false", "tag", "v1.0.0"],
                ):
                    subprocess.run(["git", *arguments], cwd=self.root, check=True)

        result = invoke(self.root, "docs_release.py", "check", "--tag", "v2.0.0")
        self.assertEqual(result.returncode, 0, result.stderr)
        records = self.root / "releases.json"
        records.write_text('[{"tag_name":"v1.0.0"}]')
        result = invoke(self.root, "docs_release.py", "published", str(records))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads((self.site / "published-versions.json").read_text()), ["1.0.0"])
        self.assertIn("Snapshot already exists", invoke(self.root, "docs_release.py", "prepare", env=self.env).stderr)
