#!/usr/bin/env python3
"""Stage authoritative Markdown and assemble every guide with the current Maven renderer."""

import hashlib
import html
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import TYPE_CHECKING
from urllib.parse import urljoin, urlsplit, urlunsplit

from docs_release import Project

if TYPE_CHECKING:
    from docs_release import ReleaseEntry, ReleasePlan


def inputs(root: Path) -> list[Path]:
    """Enumerate source inputs without generated files or recursive snapshot staging."""
    files = [root / "pom.xml", root / "mvnw"]
    for folder in ("docs", "src", "scripts", ".mvn"):
        files.extend(
            p
            for p in (root / folder).rglob("*")
            if p.is_file() and not any(s in ("__pycache__", ".venv", ".uv", "tests", ".ruff_cache") for s in p.parts)
        )
    return sorted(files)


def source_digest(root: Path) -> str:
    """Bind deployment to all current prose, snapshots, renderer and orchestration bytes."""
    digest = hashlib.sha256()
    for path in inputs(root):
        digest.update(path.relative_to(root).as_posix().encode() + b"\0" + path.read_bytes() + b"\0")
    return digest.hexdigest()


def route(path: str) -> str:
    """Convert a generated Doxia filename to its permanent directory route."""
    if path.endswith("index.html"):
        return path.removesuffix("index.html")
    return path.removesuffix(".html") + "/" if path.endswith(".html") else path


def slug(text: str) -> str:
    """Preserve the former Markdown heading fragments, including Unicode letters."""
    text = html.unescape(re.sub(r"<[^>]+>", "", text)).lower()
    return re.sub(r"[^\w\- ]", "", text).replace(" ", "-")


def anchors(content: str) -> str:
    """Add legacy fragments only when they do not collide with existing Doxia IDs."""
    identifiers = set(re.findall(r'\bid="([^"]+)"', content))
    counts: dict[str, int] = {}

    def alias(match: re.Match[str]) -> str:
        text = match.group(0)
        name = slug(match.group(2))
        count = counts.get(name, 0)
        counts[name] = count + 1
        name = name + (f"-{count}" if count else "")
        if name in identifiers:
            return text
        identifiers.add(name)
        return f'<a id="{html.escape(name, quote=True)}"></a>' + text

    return re.sub(r"<h([1-6])\b[^>]*>(.*?)</h\1>", alias, content, flags=re.S)


def redirect(destination: Path, url: str, canonical: str | None = None) -> None:
    """Preserve fragments on an explicit compatibility redirect with a usable static link."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    escaped = html.escape(url, quote=True)
    destination.write_text(
        '<!doctype html><html lang="en"><head><meta charset="utf-8">'
        f'<meta http-equiv="refresh" content="0;url={escaped}"><title>Moved</title>'
        f'<link rel="canonical" href="https://cs1302uga.github.io{html.escape(canonical or url, quote=True)}"></head>'
        f'<body><a href="{escaped}">Continue to documentation</a>'
        f"<script>location.replace({json.dumps(url)} + location.search + location.hash);</script></body></html>\n"
    )


def rewrite(content: str, original: str, plan: ReleasePlan) -> str:
    """Make generated assets and guide links independent of the Doxia file layout."""
    base = plan["baseUrl"]

    def link(match: re.Match[str]) -> str:
        value = html.unescape(match.group(2))
        target = urlsplit(value)
        if target.scheme or target.netloc or value.startswith("#"):
            return match.group(0)
        if value.startswith(base + "docs/") and target.path.endswith("/index.html"):
            return match.group(0)
        resolved = urlsplit(urljoin(base + original, value))
        path = resolved.path
        if path.startswith(base) and not path.startswith(base + "api/"):
            path = route(path)
        return (
            match.group(1)
            + '="'
            + html.escape(urlunsplit(("", "", path, resolved.query, resolved.fragment)), quote=True)
            + '"'
        )

    return re.sub(r'(href|src)="([^"]*)"', link, content)


def retired_links(root: Path, content: str) -> str:
    """Map only reviewed retired documentation-publication hrefs to their retained source tags."""
    file = root / "src/site/retired-publications.json"
    if file.exists():
        for old, replacement in json.loads(file.read_text()).items():
            content = content.replace('href="' + old + '"', 'href="' + replacement + '"')
    return content


def navigation(root: Path, plan: ReleasePlan, entry: ReleaseEntry, page: str) -> str:
    """Link matching pages across channels, with an explicit version-matched API reference."""
    links = [
        f'<a href="{plan["baseUrl"]}">Project home</a>',
        f'<a href="{plan["baseUrl"]}all-versions/">All versions</a>',
        f'<a href="{entry["javadocUrl"]}">API Reference ({html.escape(entry["label"])})</a>',
    ]
    for other in plan["versions"]:
        source = root / "docs" if other["name"] == "current" else root / "docs/releases" / other["name"]
        suffix = page if (source / (page + ".md")).is_file() else "index"
        url = other["docsUrl"] + ("" if suffix == "index" else suffix + "/")
        links.append(f'<a href="{url}">{html.escape(other["label"])}</a>')
    if plan["latestVersion"]:
        latest_source = root / "docs/releases" / plan["latestVersion"]
        suffix = "" if page == "index" or not (latest_source / (page + ".md")).is_file() else page + "/"
        links.append(f'<a href="{plan["baseUrl"]}docs/latest/{suffix}">Latest release ({plan["latestVersion"]})</a>')
    return (
        '<nav aria-label="Documentation channels" class="documentation-channels">'
        + " ".join(links[:3])
        + '<details class="documentation-version-picker"><summary>Version: '
        + html.escape(entry["label"])
        + "</summary><ul>"
        + "".join("<li>" + link + "</li>" for link in links[3:])
        + "</ul></details></nav>"
    )


def insert_controls(content: str, controls: str) -> str:
    """Keep channel navigation inside the skin's content shell with a plain-HTML fallback."""
    marker = r'(<main\b[^>]*id="supa-main-content"[^>]*>)'
    if re.search(marker, content):
        return re.sub(marker, lambda match: controls + match[0], content, count=1)
    return re.sub(r"(<body\b[^>]*>)", lambda match: match[0] + controls, content, count=1)


def guide_navigation(root: Path, entry: ReleaseEntry, page: str = "index") -> str:
    """Render frozen navigation with the skin's native disclosure and active-branch markup."""
    file = (
        root
        / "src/site"
        / ("navigation.json" if entry["name"] == "current" else "releases/" + entry["name"] + "-navigation.json")
    )
    data = json.loads(file.read_text())
    source = root / "docs" if entry["name"] == "current" else root / "docs/releases" / entry["name"]

    def anchor(identifier: str, label: str) -> str:
        if not (source / (identifier + ".md")).is_file():
            raise ValueError(f"Navigation page missing: {identifier}")
        selected = identifier == page
        url = entry["docsUrl"] + ("" if identifier == "index" else identifier + "/") + "index.html"
        return (
            '<a class="supa-nav-link'
            + (" active" if selected else "")
            + '" href="'
            + url
            + '"'
            + (' aria-current="page"' if selected else "")
            + '><span class="supa-nav-text">'
            + html.escape(label)
            + "</span></a>"
        )

    def items(nodes: list[object], depth: int = 1) -> str:
        result = []
        for node in nodes:
            if isinstance(node, str):
                document = source / (node + ".md")
                if not document.is_file():
                    raise ValueError(f"Navigation page missing: {document}")
                match = re.search(r"^# (.+)$", document.read_text(), flags=re.M)
                label = match[1] if match else node
                result.append(
                    '<li class="supa-nav-item" data-depth="' + str(depth) + '">' + anchor(node, label) + "</li>"
                )
            elif isinstance(node, dict):
                label = str(node.get("label", ""))
                linked = node.get("link", {}).get("id")
                children = items(node.get("items", []), depth + 1)
                active = 'aria-current="page"' in children or linked == page
                parent = anchor(linked, label) if linked else ""
                result.append(
                    '<li class="supa-nav-item supa-nav-has-children'
                    + (" has-link" if linked else "")
                    + (" is-active-branch" if active else "")
                    + '" data-depth="'
                    + str(depth)
                    + '">'
                    + parent
                    + '<details class="supa-nav-details"'
                    + (' open="open"' if active else "")
                    + ">"
                    + '<summary class="supa-nav-summary" aria-label="Toggle section: '
                    + html.escape(label, quote=True)
                    + '">'
                    + ("" if linked else '<span class="supa-nav-link supa-nav-label">' + html.escape(label) + "</span>")
                    + '<span class="supa-nav-chevron" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" '
                    + 'width="16" height="16" stroke="currentColor" stroke-width="2">'
                    + '<polyline points="9 18 15 12 9 6"/></svg></span></summary>'
                    + children
                    + "</details></li>"
                )
            else:
                raise ValueError("Invalid navigation item")
        classname = "supa-nav-list" if depth == 1 else "supa-nav-sublist"
        return '<ul class="' + classname + ' list-unstyled">' + "".join(result) + "</ul>"

    return (
        '<div class="supa-nav-group"><div class="supa-nav-caption" role="heading" aria-level="2">'
        + html.escape(entry["label"])
        + "</div>"
        + items(data.get("docs", []))
        + "</div>"
    )


def adjacent_pages(root: Path, entry: ReleaseEntry, page: str) -> str:
    """Restore native skin cards using the same frozen guide-navigation order."""
    file = (
        root
        / "src/site"
        / ("navigation.json" if entry["name"] == "current" else "releases/" + entry["name"] + "-navigation.json")
    )
    source = root / "docs" if entry["name"] == "current" else root / "docs/releases" / entry["name"]

    def flatten(nodes: list[object]) -> list[str]:
        result = []
        for node in nodes:
            if isinstance(node, str):
                result.append(node)
            elif isinstance(node, dict):
                if node.get("link"):
                    result.append(node["link"]["id"])
                result.extend(flatten(node.get("items", [])))
            else:
                raise ValueError("Invalid navigation item")
        return result

    ordered = list(dict.fromkeys(flatten(json.loads(file.read_text()).get("docs", []))))
    if page not in ordered:
        return ""
    position = ordered.index(page)
    cards = []
    for offset, label, classname in ((-1, "Previous", "supa-prev-page"), (1, "Next", "supa-next-page")):
        index = position + offset
        if not 0 <= index < len(ordered):
            continue
        identifier = ordered[index]
        heading = re.search(r"^# (.+)$", (source / (identifier + ".md")).read_text(), flags=re.M)
        title = heading[1] if heading else identifier
        url = entry["docsUrl"] + ("" if identifier == "index" else identifier + "/") + "index.html"
        cards.append(
            '<a class="'
            + classname
            + '" href="'
            + url
            + '"><div class="supa-page-info">'
            + '<div class="supa-context"><span>'
            + label
            + '</span></div><div class="supa-title">'
            + html.escape(title)
            + "</div></div></a>"
        )
    return (
        ('<nav class="supa-related-pages not-prose" aria-label="Page navigation">' + "".join(cards) + "</nav>")
        if cards
        else ""
    )


def render(root: Path, plan: ReleasePlan) -> None:
    """Render all versions serially in one disposable Maven project, then replace the complete output."""
    stage = root / "target/docsite/renderer"
    if stage.exists():
        shutil.rmtree(stage)
    markdown = stage / "src/site/markdown"
    markdown.mkdir(parents=True)
    shutil.copy2(root / "src/site/site.xml", stage / "src/site/site.xml")
    shutil.copytree(root / "src/site/resources", stage / "src/site/resources")
    shutil.copy2(root / "src/site/landing/index.md", markdown / "index.md")
    (markdown / "all-versions.md").write_text(
        "# Documentation versions\n\n"
        + "\n".join(
            f"- [{entry['label']}]({entry['docsUrl']}) — [API Reference]({entry['javadocUrl']})"
            for entry in plan["versions"]
        )
        + "\n"
    )
    for entry in plan["versions"]:
        source = root / "docs" if entry["name"] == "current" else root / "docs/releases" / entry["name"]
        for path in sorted(source.rglob("*")):
            if not path.is_file() or path.relative_to(source).parts[0] == "releases":
                continue
            relative = path.relative_to(source)
            target = markdown / entry["docsPath"] / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if path.suffix == ".md":
                content = re.sub(r"\A---\n.*?\n---\n", "", path.read_text(), count=1, flags=re.S)

                def repository_link(match: re.Match[str]) -> str:
                    url = match.group(1)
                    parsed = urlsplit(url)
                    if parsed.scheme or parsed.netloc or not parsed.path or parsed.path.startswith("/"):
                        return match.group(0)
                    destination = (path.parent / parsed.path).resolve()
                    if destination.is_relative_to(source.resolve()) and destination.exists():
                        return match.group(0)
                    repository_path = urljoin("docs/" + relative.as_posix(), parsed.path)
                    if repository_path.split("/", 1)[0] not in {
                        "README.md",
                        "HACKING.md",
                        "GLOSSARY.md",
                        "src",
                        "scripts",
                        "examples",
                        "pom.xml",
                        ".github",
                        ".githooks",
                    }:
                        return match.group(0)
                    commit = entry["sourceCommit"] or "main"
                    return (
                        "](https://github.com/cs1302uga/cs1302-tracer/blob/"
                        + commit
                        + "/"
                        + repository_path
                        + ("#" + parsed.fragment if parsed.fragment else "")
                        + ")"
                    )

                content = re.sub(r"\]\(([^\s)]+)\)", repository_link, content)
                target.write_text(content)
            else:
                target = stage / "src/site/resources" / entry["docsPath"] / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, target)
    pom = ET.parse(root / "pom.xml").getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    properties = pom.find("m:properties", ns)
    assert properties is not None
    version = properties.find("m:maven-site-plugin.version", ns)
    assert version is not None and version.text
    (stage / "pom.xml").write_text(
        '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
        f"<groupId>io.github.cs1302uga</groupId><artifactId>documentation</artifactId><version>{Project(root).project_version()}</version>"
        "<name>cs1302-tracer</name><url>https://cs1302uga.github.io/cs1302-tracer/</url>"
        "<properties><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>"
        "<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-site-plugin</artifactId>"
        f"<version>{version.text}</version><configuration><generateReports>false</generateReports>"
        "<relativizeSiteLinks>false</relativizeSiteLinks></configuration></plugin></plugins></build></project>"
    )
    subprocess.run([str(root / "mvnw"), "-B", "-ntp", "-f", str(stage / "pom.xml"), "site:site"], cwd=root, check=True)
    raw = stage / "target/site"
    output = root / "target/pages"
    if output.exists():
        shutil.rmtree(output)
    shutil.copytree(raw, output)
    for path in sorted(raw.rglob("*.html")):
        rendered_relative = path.relative_to(raw).as_posix()
        content = anchors(path.read_text())
        selected_entry = next(
            (
                selected_entry
                for selected_entry in plan["versions"]
                if rendered_relative.startswith(selected_entry["docsPath"] + "/")
            ),
            None,
        )
        if selected_entry:
            page = rendered_relative.removeprefix(selected_entry["docsPath"] + "/").removesuffix(".html")
            channel = html.escape(selected_entry["label"])
            content = insert_controls(
                content,
                navigation(root, plan, selected_entry, page)
                + f'<p class="documentation-channel">{channel} — '
                + (
                    "Unreleased development documentation"
                    if selected_entry["banner"] == "unreleased"
                    else "Release documentation"
                )
                + "</p>",
            )
        else:
            default = next((e for e in plan["versions"] if e["name"] == plan["latestVersion"]), plan["versions"][0])
            content = insert_controls(content, navigation(root, plan, default, "index"))
        actual_version = (
            Project(root).project_version()
            if selected_entry is None or selected_entry["name"] == "current"
            else selected_entry["name"]
        )
        content = re.sub(
            r'data-supa-project-version="[^"]*"', 'data-supa-project-version="' + actual_version + '"', content
        )
        content = re.sub(
            r'(<span\b[^>]*class="[^"]*supa-version-badge[^>]*>)[^<]*(</span>)',
            lambda m: m[1] + html.escape(actual_version) + m[2],
            content,
        )
        title = re.search(r"<h1\b[^>]*>(.*?)</h1>", content, flags=re.S)
        label = selected_entry["label"] if selected_entry else "Project home"
        page_title = html.unescape(re.sub(r"<[^>]+>", "", title[1])) if title else "Documentation"
        content = re.sub(
            r"<title>.*?</title>",
            lambda _: "<title>" + html.escape(page_title + " — " + label + " — cs1302-tracer") + "</title>",
            content,
            count=1,
            flags=re.S,
        )
        if selected_entry:
            sidebar = guide_navigation(root, selected_entry, page)
            content = content.replace("</main>", "</main>" + adjacent_pages(root, selected_entry, page), 1)
            content = re.sub(
                r'(<nav class="supa-sidebar-tree"[^>]*>).*?(</nav>)',
                lambda m: m[1] + sidebar + m[2],
                content,
                count=1,
                flags=re.S,
            )
        depth = len(Path(route(rendered_relative)).parts)
        content = re.sub(
            r'data-supa-relative-path="[^"]*"',
            'data-supa-relative-path="' + ("/".join([".."] * depth) or ".") + '"',
            content,
        )
        content = rewrite(retired_links(root, content), rendered_relative, plan)
        canonical = plan["origin"] + plan["baseUrl"] + route(rendered_relative)
        content = re.sub(r'<link\b[^>]*rel="canonical"[^>]*>', "", content)
        content = content.replace("</head>", '<link rel="canonical" href="' + canonical + '"></head>')
        content = content.replace(
            "</head>",
            f'<link rel="stylesheet" href="{plan["baseUrl"]}dependencies.css">'
            f'<script defer src="{plan["baseUrl"]}dependencies.js"></script></head>',
        )
        destination = output / route(rendered_relative) / "index.html"
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination != output / rendered_relative:
            (output / rendered_relative).unlink()
        destination.write_text(content)
        if selected_entry:
            old = "next" if selected_entry["name"] == "current" else selected_entry["name"]
            suffix = rendered_relative.removeprefix(selected_entry["docsPath"] + "/")
            redirect(output / old / route(suffix) / "index.html", plan["baseUrl"] + route(rendered_relative))
    for selected_entry in plan["versions"]:
        old = "next" if selected_entry["name"] == "current" else selected_entry["name"]
        redirect(output / old / "404.html", selected_entry["docsUrl"])
    shutil.copytree(root / "target/generated-api/api", output / "api")
    compatibility = root / "src/site/legacy-assets.json"
    if compatibility.exists():
        for public, record in json.loads(compatibility.read_text()).items():
            source = root / record["source"]
            assert hashlib.sha256(source.read_bytes()).hexdigest() == record["sha256"], "Legacy asset changed"
            target = output / public
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    latest = plan["latestVersion"]
    if latest:
        shutil.copytree(output / "docs" / latest, output / "docs/latest")
        for path in (output / "docs/latest").rglob("*.html"):
            path.write_text(path.read_text().replace("Release " + latest, "Latest release " + latest))
    redirect(
        output / "docs/index.html",
        plan["baseUrl"] + ("docs/latest/" if latest else "docs/dev/"),
        plan["baseUrl"] + ("docs/" + latest + "/" if latest else "docs/dev/"),
    )
    redirect(output / "404.html", plan["baseUrl"])
    manifest = dict(plan)
    manifest.update(
        {
            "sourceDigest": source_digest(root),
            "builderCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
            "renderer": {"mavenSitePlugin": version.text, "skin": "io.github.mepcotterell:supa-maven-skin:0.2.0"},
        }
    )
    (output / "release-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    sitemap = ET.Element("urlset", xmlns="http://www.sitemaps.org/schemas/sitemap/0.9")
    for path in sorted(output.rglob("index.html")):
        sitemap_relative = path.relative_to(output).as_posix()
        if (
            sitemap_relative.startswith("docs/")
            and not sitemap_relative.startswith("docs/latest/")
            and sitemap_relative != "docs/index.html"
        ) or sitemap_relative in ("index.html", "all-versions/index.html"):
            ET.SubElement(ET.SubElement(sitemap, "url"), "loc").text = (
                plan["origin"] + plan["baseUrl"] + route(sitemap_relative)
            )
    ET.ElementTree(sitemap).write(output / "sitemap.xml", encoding="utf-8", xml_declaration=True)
