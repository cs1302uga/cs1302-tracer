"""Exercise preparation and release reporting through the script CLI."""

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.docs_release import Project

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

    def test_explicit_pending_target_preserves_snapshot_pom_and_checks_agreement(self) -> None:
        """Prepare from a snapshot without allowing it to pass stable tag validation."""
        pom = "<project><version>1.0.0-SNAPSHOT</version></project>"
        (self.root / "pom.xml").write_text(pom)
        result = invoke(self.root, "docs_release.py", "prepare", "--version", "1.0.0", env=self.env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "pom.xml").read_text(), pom)
        result = invoke(self.root, "docs_release.py", "check", "--pending", "1.0.0", env=self.env)
        self.assertEqual(result.returncode, 0, result.stderr)
        result = invoke(self.root, "docs_release.py", "check", "--tag", "v1.0.0", env=self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Release tag does not match pom.xml", result.stderr)

        (self.root / "docs/index.md").write_text("# Changed\n")
        result = invoke(self.root, "docs_release.py", "check", "--pending", "1.0.0", env=self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Release snapshot differs", result.stderr)
        (self.root / "docs/index.md").write_text("# Current\n")
        (self.site / "sidebars.js").write_text('module.exports = {docs: ["index"]};')
        result = invoke(self.root, "docs_release.py", "check", "--pending", "1.0.0", env=self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Release sidebar differs", result.stderr)

    def test_pending_targets_reject_mismatch_invalid_versions_and_combined_constraints(self) -> None:
        """Require an explicit stable target that agrees with the POM."""
        (self.root / "pom.xml").write_text("<project><version>1.0.0-SNAPSHOT</version></project>")
        for arguments, message in (
            (("prepare",), "Choose a stable x.y.z target"),
            (("prepare", "--version", "1.0.0-rc.1"), "Choose a stable x.y.z target"),
            (("prepare", "--version", "2.0.0"), "Release target must match"),
            (("check", "--pending", "v1.0.0"), "pending versions must be x.y.z"),
            (("check", "--pending", "2.0.0"), "Pending release version does not match"),
            (("check", "--pending", "1.0.0", "--tag", "v1.0.0"), "not allowed with argument"),
        ):
            with self.subTest(arguments=arguments):
                result = invoke(self.root, "docs_release.py", *arguments, env=self.env)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)
                self.assertFalse((self.site / "versions.json").exists())
        with self.assertRaisesRegex(AssertionError, "either a release tag or a pending version"):
            Project(self.root).check(tag="v1.0.0", pending="1.0.0")

    def test_explicit_target_preserves_stable_preparation(self) -> None:
        """Retain the stable-version preparation path for existing callers."""
        result = invoke(self.root, "docs_release.py", "prepare", "--version", "1.0.0", env=self.env)
        self.assertEqual(result.returncode, 0, result.stderr)
        result = invoke(self.root, "docs_release.py", "check", "--tag", "v1.0.0", env=self.env)
        self.assertEqual(result.returncode, 0, result.stderr)
