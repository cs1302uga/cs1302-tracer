#!/usr/bin/env python3
"""Validate rendered same-site links and fragments under the GitHub Pages base path."""

import json
import xml.etree.ElementTree as ET
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urljoin, urlsplit

ROOT = Path(__file__).resolve().parents[1] / "target/pages"


class Page(HTMLParser):
    """Collect page anchors, linked assets, and redirect metadata."""

    def __init__(self, content: str) -> None:
        """Initialize the input state."""
        super().__init__()
        self.ids: set[str] = set()
        self.links: list[str] = []
        self.duplicate_ids: set[str] = set()
        self.redirect = False
        self.feed(content)

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        """Collect anchors and links from an HTML start tag."""
        attributes = dict(attrs)
        if tag == "meta" and (attributes.get("http-equiv") or "").lower() == "refresh":
            self.redirect = True
        if "id" in attributes:
            identifier = attributes["id"] or ""
            if identifier in self.ids:
                self.duplicate_ids.add(identifier)
            self.ids.add(identifier)
        if tag == "a" and attributes.get("href"):
            self.links.append(attributes["href"] or "")
        if tag in ("img", "script") and attributes.get("src"):
            self.links.append(attributes["src"] or "")
        if tag == "link" and attributes.get("href"):
            self.links.append(attributes["href"] or "")


def validate_sitemap(root: Path, origin: str, base: str, pages: dict[Path, Page]) -> list[str]:
    """Require one deterministic sitemap entry per canonical guide/landing page."""
    try:
        tree = ET.parse(root / "sitemap.xml")
    except (OSError, ET.ParseError) as error:
        return [f"Invalid sitemap: {error}"]
    listed = [element.text or "" for element in tree.findall("{*}url/{*}loc")]
    expected = set()
    for path, page in pages.items():
        relative = path.relative_to(root).as_posix()
        canonical = (
            relative.startswith("docs/") and not relative.startswith("docs/latest/") and relative != "docs/index.html"
        ) or relative in ("index.html", "all-versions/index.html")
        if canonical and not page.redirect and path.name == "index.html":
            expected.add(origin + base + relative.removesuffix("index.html"))
    errors = []
    if len(listed) != len(set(listed)):
        errors.append("Duplicate sitemap URLs")
    if set(listed) != expected:
        errors.append(
            f"Sitemap canonical URL mismatch: missing {sorted(expected - set(listed))}; "
            f"unexpected {sorted(set(listed) - expected)}"
        )
    return errors


def main() -> None:
    """Run the command-line workflow."""
    assert (ROOT / "index.html").is_file(), "Build the site first"
    pages = {path: Page(path.read_text()) for path in ROOT.rglob("*.html")}
    errors = []
    plan = json.loads((ROOT / "release-manifest.json").read_text())
    errors.extend(validate_sitemap(ROOT, plan["origin"], plan["baseUrl"], pages))
    base = plan["origin"] + plan["baseUrl"]
    host = urlsplit(base).netloc
    entries = plan["versions"]
    default = next(v for v in entries if v["name"] == (plan["latestVersion"] or "current"))
    for entry in entries:
        for asset in ("index.html", "search.html", "element-list"):
            if not (ROOT / entry["javadocPath"] / asset).is_file():
                errors.append(f"Missing API reference asset: {entry['javadocPath']}/{asset}")
    for file, page in pages.items():
        relative = file.relative_to(ROOT).as_posix()
        route = relative[: -len("index.html")] if file.name == "index.html" else relative
        api_page = any(relative.startswith(v["javadocPath"] + "/") for v in entries)
        if not api_page and page.duplicate_ids:
            errors.append(f"{relative}: duplicate IDs: {sorted(page.duplicate_ids)}")
        if not api_page and not page.redirect:
            matches = [v for v in entries if (plan["baseUrl"] + route).startswith(v["docsUrl"])]
            entry = max(matches, key=lambda v: len(v["docsUrl"])) if matches else default
            if entry["javadocUrl"] not in page.links:
                errors.append(f"{relative}: missing version-matched API link {entry['javadocUrl']}")
        for link in page.links:
            target = urlsplit(urljoin(base + route, link))
            if target.netloc != host or target.scheme not in ("http", "https"):
                continue
            if not target.path.startswith(plan["baseUrl"]):
                errors.append(f"{relative}: escapes project base path: {link}")
                continue
            dest = (ROOT / unquote(target.path.removeprefix(plan["baseUrl"]))).resolve()
            if not dest.is_relative_to(ROOT):
                errors.append(f"{relative}: escapes project base path: {link}")
                continue
            if dest.is_dir():
                dest /= "index.html"
            if not dest.is_file():
                errors.append(f"{relative}: missing {link}")
            elif target.fragment and dest in pages and unquote(target.fragment) not in pages[dest].ids:
                errors.append(f"{relative}: missing fragment {link}")
    if errors:
        raise SystemExit("\n".join(errors))
    print(f"Validated links and fragments in {len(pages)} rendered pages.")


if __name__ == "__main__":
    main()
