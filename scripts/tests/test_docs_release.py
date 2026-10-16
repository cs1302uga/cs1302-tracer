"""Release regression tests: prevent unpublished or mismatched docs becoming stable."""

import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts import docs_release as release


class ReleaseTests(unittest.TestCase):
    """Verify documentation behavior."""

    def setUp(self) -> None:
        """Create an isolated documentation project."""
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.site = self.root / "website"
        self.site.mkdir()
        (self.root / "docs").mkdir()
        (self.root / "docs/index.md").write_text("# Current\n")
        (self.root / "pom.xml").write_text("<project><version>2.0.0</version></project>")
        (self.site / "sidebars.js").write_text('module.exports = {docs: ["index"]};')
        self.project = release.Project(self.root)
        self.snapshot("2.0.0")
        self.snapshot("1.0.0")
        (self.site / "versions.json").write_text('["2.0.0", "1.0.0"]')

    def snapshot(self, version: str) -> None:
        """Create a documentation snapshot."""
        directory = self.site / f"versioned_docs/version-{version}"
        directory.mkdir(parents=True)
        (directory / "index.md").write_text("# Current\n")
        sidebars = self.site / "versioned_sidebars"
        sidebars.mkdir(exist_ok=True)
        (sidebars / f"version-{version}-sidebars.json").write_text('{"docs":["index"]}')

    def test_release_requires_matching_tag_and_snapshot(self) -> None:
        """Release requires matching tag and snapshot."""
        self.project.check("v2.0.0")
        with self.assertRaisesRegex(AssertionError, "pom.xml"):
            self.project.check("v1.0.0")
        (self.root / "docs/index.md").write_text("# Changed after preparation\n")
        with self.assertRaisesRegex(AssertionError, "differs"):
            self.project.check("2.0.0")

    def test_sidebar_changes_invalidate_tag(self) -> None:
        """Sidebar changes invalidate tag."""
        (self.site / "sidebars.js").write_text('module.exports = {docs: ["other"]};')
        with self.assertRaisesRegex(AssertionError, "sidebar differs"):
            self.project.check("v2.0.0")

    def test_unpublished_snapshot_never_becomes_default(self) -> None:
        """Unpublished snapshot never becomes default."""
        selected = self.project.select([{"tag_name": "v1.0.0"}])
        self.assertEqual(selected, ["1.0.0"])

    def test_drafts_prereleases_and_historical_unsnapshotted_releases_excluded(self) -> None:
        """Drafts prereleases and historical unsnapshotted releases excluded."""
        selected = self.project.select(
            [
                {"tag_name": "v2.0.0", "draft": True},
                {"tag_name": "v1.0.0", "prerelease": True},
                {"tag_name": "v0.1.0"},
            ]
        )
        self.assertEqual(selected, [])

    def test_old_release_retry_does_not_change_default_or_drop_versions(self) -> None:
        """Old release retry does not change default or drop versions."""
        releases: list[release.ReleaseRecord] = [{"tag_name": "v1.0.0"}, {"tag_name": "v2.0.0"}]
        self.assertEqual(self.project.select(releases), ["2.0.0", "1.0.0"])
        self.assertEqual(self.project.select(list(reversed(releases))), ["2.0.0", "1.0.0"])

    def test_invalid_version_rejected_before_becoming_url(self) -> None:
        """Invalid version rejected before becoming url."""
        (self.site / "versions.json").write_text('["../../other"]')
        with self.assertRaisesRegex(AssertionError, "stable"):
            self.project.check()

    def test_removing_an_existing_version_fails_against_pr_base(self) -> None:
        """Removing an existing version fails against pr base."""
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        subprocess.run(
            [
                "git",
                "-c",
                "user.name=Docs Test",
                "-c",
                "user.email=docs@example.invalid",
                "-c",
                "commit.gpgsign=false",
                "commit",
                "-qm",
                "Snapshots",
            ],
            cwd=self.root,
            check=True,
        )
        (self.site / "versions.json").write_text('["2.0.0"]')
        with self.assertRaisesRegex(AssertionError, "retained"):
            self.project.check(base="HEAD")

    def test_missing_archive_fails_validation(self) -> None:
        """Missing archive fails validation."""
        (self.site / "versioned_docs/version-1.0.0/index.md").unlink()
        with self.assertRaisesRegex(AssertionError, "Missing documentation"):
            self.project.check()


if __name__ == "__main__":
    unittest.main()
