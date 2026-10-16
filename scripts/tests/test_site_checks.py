"""Exercise rendered-site checks through their command-line interface."""

import json
import tempfile
import unittest
from pathlib import Path

from tests.support import copy_scripts, invoke


class SiteChecksTests(unittest.TestCase):
    """Validate generated navigation, assets, and fragment destinations."""

    def setUp(self) -> None:
        """Create a minimal site with development and release API references."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        copy_scripts(self.root)
        self.build = self.root / "website/build"
        self.build.mkdir(parents=True)
        self.plan: dict[str, object] = {
            "origin": "https://example.test",
            "baseUrl": "/project/",
            "latestVersion": "1.0.0",
            "versions": [
                {
                    "name": "current",
                    "docsUrl": "/project/next/",
                    "javadocPath": "api/next",
                    "javadocUrl": "/project/api/next/index.html",
                },
                {
                    "name": "1.0.0",
                    "docsUrl": "/project/1.0.0/",
                    "javadocPath": "api/1.0.0",
                    "javadocUrl": "/project/api/1.0.0/index.html",
                },
            ],
        }
        (self.build / "release-manifest.json").write_text(json.dumps(self.plan))
        for version in ("next", "1.0.0"):
            api = self.build / "api" / version
            api.mkdir(parents=True)
            (api / "index.html").write_text('<h1 id="a b">API</h1><a href="search.html">Search</a>')
            (api / "search.html").write_text('<a href="index.html#a%20b">API</a>')
            (api / "element-list").write_text("example")
        (self.build / "index.html").write_text('<meta http-equiv="refresh" content="0;url=1.0.0/">')
        for route in ("next", "1.0.0", "unmatched"):
            page = self.build / route
            page.mkdir()
            api_version = "next" if route == "next" else "1.0.0"
            (page / "index.html").write_text(
                f'<a href="/project/api/{api_version}/index.html">API</a>'
                '<a href="https://external.test/">External</a><a href="mailto:a@example.test">Mail</a>'
                '<img src="/project/image.svg"><script src="/project/app.js"></script>'
                '<link href="/project/style.css"><a href="/project/next/">Next</a>'
                '<div id="local"></div><a href="#local">Local</a><a>Unlinked</a>'
            )
        for asset in ("image.svg", "app.js", "style.css"):
            (self.build / asset).write_text("")

    def test_valid_navigation_assets_and_encoded_fragments(self) -> None:
        """Accept valid version links, relative assets, and encoded fragments."""
        result = invoke(self.root, "check_site.py")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Validated links and fragments", result.stdout)

    def test_reports_all_broken_destinations(self) -> None:
        """Report missing API assets, wrong-version links, escapes, and fragments together."""
        (self.build / "api/next/search.html").unlink()
        (self.build / "next/index.html").write_text(
            '<a href="/project/api/1.0.0/index.html">Wrong API</a>'
            '<a href="/outside">Escape</a><a href="missing.html">Missing</a>'
            '<a href="/project/api/next/index.html#missing">Fragment</a>'
        )
        result = invoke(self.root, "check_site.py")
        self.assertNotEqual(result.returncode, 0)
        for diagnostic in (
            "Missing API reference asset",
            "missing version-matched API link",
            "escapes project base path",
            "missing missing.html",
            "missing fragment",
        ):
            self.assertIn(diagnostic, result.stderr)

    def test_development_only_site_and_missing_build(self) -> None:
        """Select development when there is no published version, and reject absent builds."""
        self.plan["latestVersion"] = None
        (self.build / "release-manifest.json").write_text(json.dumps(self.plan))
        (self.build / "unmatched/index.html").write_text('<a href="/project/api/next/index.html">API</a>')
        result = invoke(self.root, "check_site.py")
        self.assertEqual(result.returncode, 0, result.stderr)
        (self.build / "index.html").unlink()
        self.assertIn("Build the site first", invoke(self.root, "check_site.py").stderr)
