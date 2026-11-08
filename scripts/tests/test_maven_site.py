"""Exercise permanent routes, headings, input identity and version-specific navigation."""

import json
import tempfile
import unittest
from pathlib import Path
from typing import TYPE_CHECKING

from scripts.docs_release import Project, maven_version
from scripts.maven_site import (
    adjacent_pages,
    anchors,
    guide_navigation,
    inputs,
    insert_controls,
    navigation,
    redirect,
    render,
    retired_links,
    rewrite,
    route,
    slug,
    source_digest,
)

if TYPE_CHECKING:
    from scripts.docs_release import ReleaseEntry, ReleasePlan


class MavenSiteTests(unittest.TestCase):
    """Keep routing and compatibility decisions independent of Maven process execution."""

    def setUp(self) -> None:
        """Create real current and retained guide inputs with distinct page inventories."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        for folder in ("docs/releases/1.0.0", "src/site/releases", "scripts", ".mvn"):
            (self.root / folder).mkdir(parents=True)
        (self.root / "pom.xml").write_text("<project><version>1.0.1-SNAPSHOT</version></project>")
        (self.root / "mvnw").write_text("wrapper")
        (self.root / "docs/index.md").write_text("# Current")
        (self.root / "docs/task.md").write_text("# Task")
        (self.root / "docs/releases/1.0.0/index.md").write_text("# Stable")
        (self.root / "src/site/navigation.json").write_text(
            json.dumps({"docs": ["index", {"label": "Tasks", "items": ["task"]}]})
        )
        (self.root / "src/site/releases/1.0.0-navigation.json").write_text('{"docs":["index"]}')
        self.current: ReleaseEntry = {
            "name": "current",
            "label": "Development (main)",
            "docsPath": "docs/dev",
            "docsUrl": "/project/docs/dev/",
            "javadocPath": "api/next",
            "javadocUrl": "/project/api/next/index.html",
            "sourceCommit": None,
            "banner": "unreleased",
        }
        self.release: ReleaseEntry = {
            "name": "1.0.0",
            "label": "Release 1.0.0",
            "docsPath": "docs/1.0.0",
            "docsUrl": "/project/docs/1.0.0/",
            "javadocPath": "api/1.0.0",
            "javadocUrl": "/project/api/1.0.0/index.html",
            "sourceCommit": "a" * 40,
            "banner": "none",
        }
        self.plan: ReleasePlan = {
            "mode": "preview",
            "baseUrl": "/project/",
            "origin": "https://example.test",
            "latestVersion": "1.0.0",
            "versions": [self.current, self.release],
        }

    def test_routes_and_legacy_anchor_collisions(self) -> None:
        """Retain existing IDs and disambiguate repeated Markdown headings deterministically."""
        self.assertEqual(route("index.html"), "")
        self.assertEqual(route("docs/dev/task.html"), "docs/dev/task/")
        self.assertEqual(route("docs/dev/index.html"), "docs/dev/")
        self.assertEqual(route("assets/a.json"), "assets/a.json")
        text = anchors('<a id="same"></a><h1>Same</h1><h2>Same</h2><h2>Use <code>Java</code> &amp; JSON</h2>')
        self.assertEqual(text.count('id="same"'), 1)
        self.assertIn('id="same-1"', text)
        self.assertIn('id="use-java--json"', text)
        self.assertEqual(slug("Café — Java"), "café--java")

    def test_link_rewriting_preserves_external_api_and_fragment_semantics(self) -> None:
        """Rewrite only local guide filenames and assets, preserving queries and fragments."""
        content = (
            '<a href="../task.html?q=1&amp;x=2#use-java">Task</a><img src="../../images/x.svg">'
            '<a href="/project/api/next/index.html#method">API</a><a href="#local">Local</a>'
            '<a href="https://other.test/a.html">External</a><a href="//other.test/x">Other</a>'
        )
        result = rewrite(content, "docs/dev/nested/page.html", self.plan)
        self.assertIn("/project/docs/dev/task/?q=1&amp;x=2#use-java", result)
        self.assertIn("/project/docs/images/x.svg", result)
        self.assertIn("/project/api/next/index.html#method", result)
        self.assertIn('href="#local"', result)
        self.assertIn("https://other.test/a.html", result)
        self.assertIn("//other.test/x", result)

    def test_matching_page_switches_fall_back_only_when_a_page_is_absent(self) -> None:
        """Keep current task routes and send older versions lacking the task to their index."""
        result = navigation(self.root, self.plan, self.current, "task")
        self.assertIn("/project/docs/dev/task/", result)
        self.assertIn('/project/docs/1.0.0/"', result)
        self.assertNotIn("/project/docs/1.0.0/task/", result)
        self.assertIn(self.current["javadocUrl"], result)
        self.assertIn("Latest release (1.0.0)", result)
        (self.root / "docs/releases/1.0.0/task.md").write_text("# Stable task")
        self.assertIn("/project/docs/latest/task/", navigation(self.root, self.plan, self.current, "task"))
        self.plan["latestVersion"] = None
        self.assertNotIn("Latest release", navigation(self.root, self.plan, self.current, "index"))

    def test_native_navigation_uses_frozen_version_inventory_and_rejects_typos(self) -> None:
        """Render nested current navigation and the independently retained release navigation."""
        result = guide_navigation(self.root, self.current)
        self.assertIn("Tasks", result)
        self.assertIn('class="supa-nav-details"', result)
        self.assertNotIn('open="open"', result)
        active = guide_navigation(self.root, self.current, "task")
        self.assertIn('open="open"', active)
        self.assertIn('aria-current="page"', active)
        self.assertIn("supa-nav-sublist list-unstyled", active)
        self.assertIn("/project/docs/dev/task/index.html", result)
        self.assertIn(">Task</span></a>", result)
        self.assertNotIn("task/", guide_navigation(self.root, self.release))
        (self.root / "docs/task.md").write_text("No heading")
        self.assertIn(">task</span></a>", guide_navigation(self.root, self.current))
        (self.root / "docs/task.md").unlink()
        with self.assertRaisesRegex(ValueError, "Navigation page missing"):
            guide_navigation(self.root, self.current)

    def test_category_landing_links_and_invalid_navigation_are_checked(self) -> None:
        """Keep category landing pages reachable and fail rather than hide malformed entries."""
        path = self.root / "src/site/navigation.json"
        path.write_text(json.dumps({"docs": [{"label": "Category", "link": {"id": "task"}, "items": []}]}))
        self.assertIn("/project/docs/dev/task/", guide_navigation(self.root, self.current))
        active = guide_navigation(self.root, self.current, "task")
        self.assertIn("has-link is-active-branch", active)
        self.assertIn('open="open"', active)
        (self.root / "docs/task.md").unlink()
        with self.assertRaisesRegex(ValueError, "Navigation page missing"):
            guide_navigation(self.root, self.current)
        path.write_text('{"docs":[3]}')
        with self.assertRaisesRegex(ValueError, "Invalid navigation"):
            guide_navigation(self.root, self.current)

    def test_channel_controls_stay_inside_the_content_shell(self) -> None:
        """Place responsive native controls before article content, retaining a plain HTML fallback."""
        content = '<body><header>Skin</header><main id="supa-main-content">Article</main></body>'
        result = insert_controls(content, "<nav>Channels</nav>")
        self.assertIn("</header><nav>Channels</nav><main", result)
        self.assertEqual(
            insert_controls("<body>Plain</body>", "<nav>Channels</nav>"), "<body><nav>Channels</nav>Plain</body>"
        )

    def test_missing_maven_versions_and_renderer_configuration_fail_before_execution(self) -> None:
        """Treat required renderer configuration as part of the build's validated input contract."""
        for text in ("<project/>", "<project><version/></project>"):
            with self.assertRaisesRegex(ValueError, "nonempty version"):
                maven_version(text)
        for directory in ("src/site/resources", "src/site/landing"):
            (self.root / directory).mkdir()
        (self.root / "src/site/site.xml").write_text("<site/>")
        (self.root / "src/site/landing/index.md").write_text("# Landing")
        for properties in ("", "<properties/>", "<properties><maven-site-plugin.version/></properties>"):
            (self.root / "pom.xml").write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0"><version>1</version>' + properties + "</project>"
            )
            with self.assertRaises(AssertionError):
                render(self.root, self.plan)

    def test_retired_documentation_release_mapping_is_exact_and_preserves_software_links(self) -> None:
        """Historical source bytes stay frozen while rendered hrefs retain browsable provenance."""
        old = "https://github.com/cs1302uga/cs1302-tracer/releases/tag/docs-" + "a" * 40
        target = old.replace("releases/tag/", "tree/")
        software = "https://github.com/cs1302uga/cs1302-tracer/releases/tag/v1.0.0"
        content = (
            '<a href="' + old + '">Old publication</a><a href="' + software + '">Software</a><code>' + old + "</code>"
        )
        self.assertEqual(retired_links(self.root, content), content)
        (self.root / "src/site/retired-publications.json").write_text(json.dumps({old: target}))
        result = retired_links(self.root, content)
        self.assertIn('href="' + target + '"', result)
        self.assertIn('href="' + software + '"', result)
        self.assertIn("<code>" + old + "</code>", result)
        self.assertNotIn('href="' + old + '"', result)

    def test_search_candidates_and_adjacent_cards_use_existing_index_resources(self) -> None:
        """Match the shipped skin's .html candidate filter without changing canonical directories."""
        sidebar = guide_navigation(self.root, self.current, "task")
        import re

        links = re.findall(r'<a class="supa-nav-link[^"]*" href="([^"]+)"', sidebar)
        self.assertTrue(links)
        self.assertTrue(all(link.endswith("/index.html") for link in links))
        self.assertEqual(
            rewrite('<a href="/project/docs/dev/task/index.html#task">Task</a>', "docs/dev/index.html", self.plan),
            '<a href="/project/docs/dev/task/index.html#task">Task</a>',
        )
        self.assertEqual(adjacent_pages(self.root, self.release, "index"), "")
        first = adjacent_pages(self.root, self.current, "index")
        self.assertIn("supa-next-page", first)
        self.assertNotIn("supa-prev-page", first)
        last = adjacent_pages(self.root, self.current, "task")
        self.assertIn("supa-prev-page", last)
        self.assertNotIn("supa-next-page", last)
        self.assertEqual(adjacent_pages(self.root, self.current, "unlisted-review"), "")
        (self.root / "docs/last.md").write_text("No heading")
        file = self.root / "src/site/navigation.json"
        file.write_text(
            json.dumps({"docs": ["index", {"label": "Category", "link": {"id": "task"}, "items": ["task", "last"]}]})
        )
        middle = adjacent_pages(self.root, self.current, "task")
        self.assertIn("supa-prev-page", middle)
        self.assertIn("supa-next-page", middle)
        self.assertIn(">last</div>", middle)
        file.write_text('{"docs":[3]}')
        with self.assertRaisesRegex(ValueError, "Invalid navigation"):
            adjacent_pages(self.root, self.current, "index")

    def test_input_digest_tracks_all_production_sources_without_tool_environments(self) -> None:
        """Bind Java, docs, snapshots, descriptors, Python and wrappers to deployment identity."""
        before = source_digest(self.root)
        (self.root / "scripts/.venv").mkdir()
        (self.root / "scripts/.venv/tool.py").write_text("irrelevant")
        (self.root / "scripts/__pycache__").mkdir()
        (self.root / "scripts/__pycache__/cache.pyc").write_text("irrelevant")
        (self.root / "scripts/.ruff_cache").mkdir()
        (self.root / "scripts/.ruff_cache/content").write_text("lint cache")
        self.assertEqual(source_digest(self.root), before)
        (self.root / "src/Example.java").write_text("class Example {}")
        self.assertNotEqual(source_digest(self.root), before)
        self.assertTrue(all(".venv" not in path.parts for path in inputs(self.root)))
        self.assertEqual(Project.files(self.root / "docs"), {"index.md": b"# Current", "task.md": b"# Task"})

    def test_compatibility_redirect_has_static_fallback_and_preserves_dynamic_fragment(self) -> None:
        """Keep an explicit fallback link and transfer search/fragment to the canonical page."""
        target = self.root / "output/next/task/index.html"
        redirect(target, "/project/docs/dev/task/")
        content = target.read_text()
        self.assertIn('http-equiv="refresh"', content)
        self.assertIn('href="/project/docs/dev/task/"', content)
        self.assertIn("location.search + location.hash", content)

    def test_retained_release_provenance_rejects_deletion_retargeting_and_source_drift(self) -> None:
        """Keep frozen prose/navigation and software identity independent from renderer changes."""
        project = Project(self.root)
        (self.root / "src/site/versions.json").write_text('["1.0.0"]')
        file = self.root / "src/site/release-provenance.json"
        record = {"releaseCommit": "a" * 40, "sourceDigest": project.snapshot_digest("1.0.0")}
        file.write_text(json.dumps({"schemaVersion": 1, "releases": {"1.0.0": record}}))
        project.validate_retention([self.current, self.release], "preview")
        project.validate_retention([self.current, self.release], "published")
        with self.assertRaisesRegex(AssertionError, "retain pinned guide"):
            project.validate_retention([self.current], "published")
        self.release["sourceCommit"] = "b" * 40
        with self.assertRaisesRegex(AssertionError, "Retargeted"):
            project.validate_retention([self.current, self.release], "published")
        (self.root / "src/site/versions.json").write_text("[]")
        with self.assertRaisesRegex(AssertionError, "must be retained"):
            project.validate_retention([], "preview")
        (self.root / "src/site/versions.json").write_text('["1.0.0"]')
        (self.root / "docs/releases/1.0.0/index.md").write_text("Changed historical prose")
        with self.assertRaisesRegex(AssertionError, "Frozen documentation changed"):
            project.validate_retention([], "preview")
        file.write_text('{"schemaVersion":2}')
        with self.assertRaisesRegex(AssertionError, "schema"):
            project.validate_retention([], "preview")


if __name__ == "__main__":
    unittest.main()
