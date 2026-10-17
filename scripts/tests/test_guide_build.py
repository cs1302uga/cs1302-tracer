"""Exercise isolated guide rendering, bounded selection, and complete-site composition."""

import contextlib
import hashlib
import io
import json
import os
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import TYPE_CHECKING
from unittest import mock

from docs_release import Project
from docs_timing import BuildTimings
from guide_archive import create, filenames
from guide_build import compose, fingerprint, inventory, prepare, preview_archive, render, select, validate_retention
from tests.support import copy_scripts, executable, invoke, tool_environment

if TYPE_CHECKING:
    from docs_release import ReleaseEntry, ReleasePlan, ReleaseRecord


class GuideBuildTests(unittest.TestCase):
    """Use real Git snapshots and external Node/npm stand-ins without weakening orchestration checks."""

    def setUp(self) -> None:
        """Create three tagged guide releases and deterministic frontend tools."""
        temporary = tempfile.TemporaryDirectory(prefix="guide builds ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        copy_scripts(self.root)
        self.site = self.root / "website"
        self.site.mkdir()
        for name in ("static", "src", "node_modules", "versioned_sidebars"):
            (self.site / name).mkdir()
        (self.site / "src/component.js").write_text("renderer")
        (self.site / "static/logo.svg").write_text("logo")
        for name in ("docusaurus.config.js", "sidebars.js", "package.json", "package-lock.json"):
            (self.site / name).write_text("{}")
        (self.root / "docs").mkdir()
        (self.root / "docs/index.md").write_text("# Development")
        (self.root / ".gitignore").write_text("bin/\nwebsite/.cache/\nwebsite/node_modules/\nwebsite/build/\n*.log\n")
        self.git("init", "-q")
        versions = []
        for version in ("1.0.0", "2.0.0", "3.0.0"):
            versions.insert(0, version)
            (self.root / "pom.xml").write_text(f"<project><version>{version}</version></project>")
            snapshot = self.site / f"versioned_docs/version-{version}"
            snapshot.mkdir(parents=True)
            (snapshot / "index.md").write_text(f"# Guide {version}")
            (self.site / f"versioned_sidebars/version-{version}-sidebars.json").write_text("{}")
            (self.site / "versions.json").write_text(json.dumps(versions))
            self.git("add", ".")
            self.git("commit", "-qm", version)
            self.git("tag", "v" + version)
        (self.root / "pom.xml").write_text("<project><version>4.0.0-SNAPSHOT</version></project>")
        self.git("add", "pom.xml")
        self.git("commit", "-qm", "Next development")
        self.plan = Project(self.root).plan("preview")
        self.entry = self.plan["versions"][-1]
        self.environment = tool_environment(self.root)
        self.environment["GUIDE_TEST_LOG"] = str(self.root / "npm.log")
        environment = mock.patch.dict(os.environ, self.environment)
        environment.start()
        self.addCleanup(environment.stop)
        executable(self.root / "bin/node", "print('v24.test')\n")
        executable(
            self.root / "bin/npm",
            """
import json, os, sys
from pathlib import Path
if '--version' in sys.argv:
    print('11.test')
    sys.exit(0)
site = Path.cwd()
selection = json.loads((site / 'guide-build.json').read_text())
plan = json.loads((site / 'release-manifest.json').read_text())
assert selection['kind'] == 'archive'
assert len(plan['versions']) == 1
assert not (site / 'generated-api').exists()
assert (site / 'node_modules').is_symlink()
assert sorted(p.name for p in (site / 'versioned_docs').iterdir()) == ['version-' + selection['version']]
assert (site / 'src/component.js').read_text() == 'renderer'
with open(os.environ['GUIDE_TEST_LOG'], 'a') as log:
    log.write(json.dumps({'selection':selection,'plan':plan}) + '\\n')
if os.environ.get('GUIDE_TEST_FAIL'):
    sys.exit(1)
output = Path(sys.argv[sys.argv.index('--out-dir') + 1])
(output / 'topic').mkdir(parents=True)
(output / 'assets').mkdir()
api = '<a href="' + plan['versions'][0]['javadocUrl'] + '">API Reference</a>'
(output / 'index.html').write_text('<h1>Archived guide</h1>' + api)
(output / 'topic/index.html').write_text('<h1 id="topic">Topic</h1>' + api)
(output / 'assets/app.js').write_text('window.guide = true;')
""",
        )

    def git(self, *arguments: str) -> str:
        """Create temporary history without host signing or hook configuration."""
        return subprocess.check_output(
            [
                "git",
                "-c",
                "user.name=Guide Test",
                "-c",
                "user.email=guide@example.invalid",
                "-c",
                "commit.gpgsign=false",
                "-c",
                "tag.gpgsign=false",
                "-c",
                "core.hooksPath=/dev/null",
                *arguments,
            ],
            cwd=self.root,
            text=True,
        ).strip()

    def test_selection_preserves_all_and_bounds_active_with_one_pending(self) -> None:
        """Select by numeric stable version while retaining current and a distinct pending snapshot."""
        self.assertEqual(select(self.plan, "all")["versions"], ["current", "3.0.0", "2.0.0", "1.0.0"])
        self.assertEqual(select(self.plan, "active")["versions"], ["current", "3.0.0", "2.0.0"])
        pending: ReleaseEntry = {**self.entry, "name": "4.0.0", "banner": "unreleased", "sourceCommit": None}
        self.plan["versions"].insert(1, pending)
        self.assertEqual(select(self.plan, "active")["versions"], ["current", "4.0.0", "3.0.0", "2.0.0"])
        self.plan["versions"].insert(1, {**pending, "name": "5.0.0"})
        with self.assertRaisesRegex(ValueError, "at most one"):
            select(self.plan, "active")
        with self.assertRaisesRegex(ValueError, "Unknown guide"):
            select(self.plan, "invalid")

    def test_published_retention_rejects_removed_reclassified_and_retargeted_releases(self) -> None:
        """Mutable GitHub release state cannot silently remove already pinned documentation."""
        project = Project(self.root)
        published = project.plan("published", [{"tag_name": "v" + version} for version in project.versions()])
        validate_retention(self.root, published)
        catalog = self.site / "guide-archives.json"
        catalog.write_text('{"schemaVersion":1,"archives":{}}')
        validate_retention(self.root, published)
        catalog.write_text(
            json.dumps({"schemaVersion": 1, "archives": {"1.0.0": {"releaseCommit": self.entry["sourceCommit"]}}})
        )
        validate_retention(self.root, published)
        for flags in (None, {"draft": True}, {"prerelease": True}):
            records: list[ReleaseRecord] = [{"tag_name": "v3.0.0"}, {"tag_name": "v2.0.0"}]
            if flags is not None:
                records.append(
                    {
                        "tag_name": "v1.0.0",
                        "draft": flags.get("draft", False),
                        "prerelease": flags.get("prerelease", False),
                    }
                )
            with self.assertRaisesRegex(ValueError, "retain pinned guide 1.0.0"):
                validate_retention(self.root, project.plan("published", records))
        (self.root / "pom.xml").write_text("<project><version>1.0.0</version></project>")
        self.git("add", "pom.xml")
        self.git("commit", "-qm", "Retargeted release")
        self.git("tag", "-f", "v1.0.0")
        with self.assertRaisesRegex(ValueError, "retarget pinned guide 1.0.0"):
            validate_retention(self.root, project.plan("published", [{"tag_name": "v1.0.0"}]))
        for value in ([], {}, {"schemaVersion": True}, {"schemaVersion": 2}, {"schemaVersion": 1, "archives": []}):
            catalog.write_text(json.dumps(value))
            with self.assertRaisesRegex(ValueError, "catalog schema"):
                validate_retention(self.root, published)
        catalog.write_text('{"schemaVersion":1,"archives":{"1.0.0":null}}')
        with self.assertRaisesRegex(ValueError, "retarget pinned guide"):
            validate_retention(self.root, published)
        validate_retention(self.root, self.plan)

    def test_fingerprint_tracks_relevant_bytes_and_tools_without_global_catalog_or_head(self) -> None:
        """Unrelated commits and other versions do not invalidate an isolated archive."""
        key = fingerprint(self.root, self.entry)
        for name in ("guide-catalog.json", "guide-build.json", "release-manifest.json", "guide-archives.json"):
            (self.site / name).write_text('{"unrelated":true}')
        (self.root / "unrelated.txt").write_text("unrelated")
        self.git("add", "unrelated.txt")
        self.git("commit", "-qm", "Unrelated")
        (self.site / "versioned_docs/version-3.0.0/index.md").write_text("Another guide changed")
        self.assertEqual(fingerprint(self.root, self.entry), key)
        snapshot = self.site / "versioned_docs/version-1.0.0/index.md"
        snapshot.write_text("Corrected historical guide")
        self.assertNotEqual(fingerprint(self.root, self.entry), key)
        key = fingerprint(self.root, self.entry)
        (self.site / "package-lock.json").write_text('{"new":"dependency"}')
        self.assertNotEqual(fingerprint(self.root, self.entry), key)
        key = fingerprint(self.root, self.entry)
        executable(self.root / "bin/node", "print('v25.test')\n")
        self.assertNotEqual(fingerprint(self.root, self.entry), key)
        key = fingerprint(self.root, self.entry)
        (self.site / "static/logo.svg").write_text("updated shared logo")
        self.assertNotEqual(fingerprint(self.root, self.entry), key)

    def test_render_and_preview_cache_use_complete_isolated_trees(self) -> None:
        """Reuse exact guide output and rebuild missing, corrupt, and extra-file cache entries."""
        destination = self.root / "first"
        self.assertEqual(preview_archive(self.root, self.plan, self.entry, destination), "miss")
        expected = inventory(destination)
        first_call = json.loads((self.root / "npm.log").read_text())
        self.assertEqual(first_call["selection"], {"kind": "archive", "version": "1.0.0", "versions": ["1.0.0"]})
        self.assertEqual([entry["name"] for entry in first_call["plan"]["versions"]], ["1.0.0"])
        self.assertEqual(preview_archive(self.root, self.plan, self.entry, self.root / "second"), "hit")
        self.assertEqual(inventory(self.root / "second"), expected)
        self.assertEqual(len((self.root / "npm.log").read_text().splitlines()), 1)
        cached = next((self.site / ".cache/guides").iterdir())
        (cached / "site/assets/app.js").write_text("changed")
        self.assertEqual(preview_archive(self.root, self.plan, self.entry, self.root / "third"), "miss")
        (cached / "site/extra.txt").write_text("unexpected")
        self.assertEqual(preview_archive(self.root, self.plan, self.entry, self.root / "fourth"), "miss")
        (cached / "files.json").write_text("malformed")
        self.assertEqual(preview_archive(self.root, self.plan, self.entry, self.root / "fifth"), "miss")
        self.assertEqual(inventory(self.root / "fifth"), expected)

    def test_inventory_rejects_missing_index_links_and_special_entries(self) -> None:
        """Never accept a partial or nonregular output tree as an archive."""
        tree = self.root / "tree"
        tree.mkdir()
        with self.assertRaisesRegex(ValueError, "index.html"):
            inventory(tree)
        (tree / "index.html").write_text("complete")
        (tree / "link").symlink_to(tree / "index.html")
        with self.assertRaisesRegex(ValueError, "Unsupported"):
            inventory(tree)
        (tree / "link").unlink()
        os.mkfifo(tree / "fifo")
        with self.assertRaisesRegex(ValueError, "Unsupported"):
            inventory(tree)
        (tree / "fifo").unlink()
        link = self.root / "tree-link"
        link.symlink_to(tree, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "regular directory"):
            inventory(link)

    def test_cache_manifest_is_bounded_regular_and_recoverable(self) -> None:
        """Never block on a FIFO or abort for oversized, redirected, or deeply nested cache metadata."""
        self.assertEqual(preview_archive(self.root, self.plan, self.entry, self.root / "first"), "miss")
        cached = next((self.site / ".cache/guides").iterdir())
        manifest = cached / "files.json"
        for kind in ("fifo", "large", "nested", "symlink"):
            with self.subTest(kind=kind):
                manifest.unlink()
                if kind == "fifo":
                    os.mkfifo(manifest)
                elif kind == "large":
                    manifest.write_bytes(b" " * (2 * 1024 * 1024 + 1))
                elif kind == "nested":
                    manifest.write_text("[" * 10000 + "]" * 10000)
                else:
                    target = self.root / "outside-manifest"
                    target.write_text("{}")
                    manifest.symlink_to(target)
                destination = self.root / kind
                self.assertEqual(preview_archive(self.root, self.plan, self.entry, destination), "miss")
                self.assertTrue((destination / "index.html").is_file())

    def test_cache_copy_race_and_failed_storage_keep_fresh_output(self) -> None:
        """Rebuild a mutated private copy and treat cache-write failures as advisory."""
        self.assertEqual(preview_archive(self.root, self.plan, self.entry, self.root / "first"), "miss")
        copytree = shutil.copytree
        destination = self.root / "second"

        def changing_copy(source: Path, target: Path, *args, **kwargs) -> Path:
            result = copytree(source, target, *args, **kwargs)
            if Path(target) == destination:
                (destination / "assets/app.js").write_text("cache changed while copying")
            return result

        with mock.patch("shutil.copytree", side_effect=changing_copy):
            self.assertEqual(preview_archive(self.root, self.plan, self.entry, destination), "miss")
        self.assertEqual((destination / "assets/app.js").read_text(), "window.guide = true;")
        shutil.rmtree(self.site / ".cache/guides")
        captured = io.StringIO()
        with (
            mock.patch.object(Path, "rename", side_effect=OSError("cache unavailable")),
            contextlib.redirect_stdout(captured),
        ):
            self.assertEqual(preview_archive(self.root, self.plan, self.entry, self.root / "third"), "miss")
        self.assertIn("could not store guide cache", captured.getvalue())
        self.assertTrue((self.root / "third/index.html").is_file())

    def test_symlink_cache_never_reuses_or_writes_through_it(self) -> None:
        """Use fresh output when either a cache entry or its directory is a symbolic link."""
        self.assertEqual(preview_archive(self.root, self.plan, self.entry, self.root / "first"), "miss")
        cache = self.site / ".cache/guides"
        stored = next(cache.iterdir())
        moved = self.root / "moved-cache"
        stored.rename(moved)
        stored.symlink_to(moved, target_is_directory=True)
        captured = io.StringIO()
        with contextlib.redirect_stdout(captured):
            self.assertEqual(preview_archive(self.root, self.plan, self.entry, self.root / "second"), "miss")
        self.assertIn("symbolic link", captured.getvalue())
        stored.unlink()
        cache.rmdir()
        other = self.root / "other-cache"
        other.mkdir()
        moved.rename(other / stored.name)
        cache.symlink_to(other, target_is_directory=True)
        with contextlib.redirect_stdout(captured):
            self.assertEqual(preview_archive(self.root, self.plan, self.entry, self.root / "third"), "miss")
        self.assertTrue(cache.is_symlink())

    def test_preparation_restores_before_build_and_composition_keeps_all_routes(self) -> None:
        """Bound compilation without removing any guide or API identity from the final catalog."""
        timings = BuildTimings()
        with prepare(self.root, self.plan, "active", timings) as archives:
            self.assertEqual(set(archives), {"1.0.0"})
            selected = json.loads((self.site / "guide-build.json").read_text())
            self.assertEqual(selected["versions"], ["current", "3.0.0", "2.0.0"])
            output = self.site / "build"
            output.mkdir()
            (output / "index.html").write_text('<meta http-equiv="refresh" content="0;url=/cs1302-tracer/3.0.0/">')
            for entry in self.plan["versions"][:-1]:
                folder = output / entry["docsPath"]
                folder.mkdir()
                (folder / "index.html").write_text(f'<a href="{entry["javadocUrl"]}">API Reference</a>')
            for entry in self.plan["versions"]:
                api = output / entry["javadocPath"]
                api.mkdir(parents=True)
                for name in ("index.html", "search.html", "element-list"):
                    (api / name).write_text("API")
            compose(self.root, self.plan, archives)
            self.assertEqual((output / "1.0.0/assets/app.js").read_text(), "window.guide = true;")
            catalog = json.loads((output / "guide-catalog.json").read_text())
            self.assertEqual(len(catalog["versions"]), 4)
            self.assertEqual(catalog["versions"][-1]["routes"], ["", "topic/"])
            self.assertIn("/cs1302-tracer/api/1.0.0/index.html", (output / "all-versions/index.html").read_text())
            sitemap = ET.parse(output / "sitemap.xml")
            urls = [node.text for node in sitemap.findall(".//{*}loc")]
            self.assertIn("https://cs1302uga.github.io/cs1302-tracer/1.0.0/topic/", urls)
            (output / "release-manifest.json").write_text(json.dumps(self.plan))
            result = invoke(self.root, "check_site.py", env=self.environment)
            self.assertEqual(result.returncode, 0, result.stderr)
            with self.assertRaisesRegex(ValueError, "collision"):
                compose(self.root, self.plan, archives)
        self.assertFalse(next(iter(archives.values())).exists())
        self.assertEqual(timings.stages[0]["source"], "local-archive")
        with prepare(self.root, self.plan, "all", BuildTimings()) as archives:
            self.assertEqual(archives, {})

    def test_published_preparation_requires_and_verifies_durable_archives(self) -> None:
        """Require catalog coverage before any build, then verify real archive bytes at external boundaries."""
        plan: ReleasePlan = {**self.plan, "mode": "published"}
        catalog = self.site / "guide-archives.json"
        catalog.write_text('{"schemaVersion":1,"archives":{}}')
        with self.assertRaisesRegex(ValueError, "before retiring"):
            with prepare(self.root, plan, "active", BuildTimings()):
                self.fail("Missing archives must fail before the build body")
        catalog.unlink()
        (self.site / "guide-build.json").unlink()
        temporary = tempfile.TemporaryDirectory(prefix="published guide ")
        self.addCleanup(temporary.cleanup)
        assets = Path(temporary.name)
        tree = assets / "tree"
        render(self.root, self.plan, self.entry, tree)
        metadata = create(self.root, self.entry, tree, assets)
        self.assertFalse(metadata["dirty"])
        archive, _, bundle = filenames(self.entry["name"])
        (assets / bundle).write_text('{"verified":"external gh boundary"}')
        publication = "docs-" + metadata["builderCommit"]
        record = {
            **metadata,
            "publication": publication,
            "assets": [
                {
                    "name": name,
                    "url": f"https://github.com/cs1302uga/cs1302-tracer/releases/download/{publication}/{name}",
                    "size": (assets / name).stat().st_size,
                    "sha256": hashlib.sha256((assets / name).read_bytes()).hexdigest(),
                }
                for name in filenames(self.entry["name"])
            ],
        }
        catalog.write_text(json.dumps({"schemaVersion": 1, "archives": {self.entry["name"]: record}}))
        executable(self.root / "bin/gh", "pass\n")

        def download(url: str, timeout: int) -> io.BytesIO:
            self.assertEqual(timeout, 30)
            return io.BytesIO((assets / url.rsplit("/", 1)[-1]).read_bytes())

        timings = BuildTimings()
        with mock.patch("urllib.request.urlopen", side_effect=download):
            with prepare(self.root, plan, "active", timings) as archives:
                self.assertEqual(inventory(archives["1.0.0"]), inventory(tree))
        self.assertEqual(timings.stages[0]["source"], "published-archive")
        self.assertTrue((assets / archive).is_file())

    def test_composition_requires_each_guide_index_and_excludes_api_routes(self) -> None:
        """A development-only site does not advertise Javadoc pages as guide routes."""
        output = self.site / "build"
        output.mkdir()
        with self.assertRaisesRegex(ValueError, "Guide index missing"):
            compose(self.root, self.plan, {})
        plan: ReleasePlan = {
            **self.plan,
            "latestVersion": None,
            "versions": [{**self.plan["versions"][0], "docsUrl": self.plan["baseUrl"]}],
        }
        (output / "index.html").write_text("development")
        (output / "api/next").mkdir(parents=True)
        (output / "api/next/index.html").write_text("API")
        (output / "api-guide").mkdir()
        (output / "api-guide/index.html").write_text("Guide to the API")
        compose(self.root, plan, {})
        catalog = json.loads((output / "guide-catalog.json").read_text())
        self.assertEqual(catalog["versions"][0]["routes"], ["", "api-guide/"])

    def test_failed_frontend_render_does_not_create_cache_entry(self) -> None:
        """Surface the external compiler's failure without claiming a reusable archive."""
        with mock.patch.dict(os.environ, {"GUIDE_TEST_FAIL": "1"}):
            with self.assertRaises(subprocess.CalledProcessError):
                render(self.root, self.plan, self.entry, self.root / "failed")
        self.assertFalse((self.site / ".cache/guides").exists())

    def test_archive_cli_rejects_unknown_and_development_versions(self) -> None:
        """Require a tagged stable snapshot before packaging any durable artifact."""
        for version in ("missing", "current"):
            with self.subTest(version=version):
                result = invoke(
                    self.root,
                    "guide_build.py",
                    "archive",
                    "--version",
                    version,
                    "--output",
                    str(self.root / "bundles"),
                    env=self.environment,
                )
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn("tagged stable", result.stderr)
        result = invoke(
            self.root,
            "guide_build.py",
            "archive",
            "--version",
            "1.0.0",
            "--output",
            str(self.root / "bundles"),
            env=self.environment,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / "bundles/cs1302-tracer-1.0.0-guides.zip").is_file())
        self.assertTrue((self.root / "bundles/cs1302-tracer-1.0.0-guides.zip.sha256").is_file())


if __name__ == "__main__":
    unittest.main()
