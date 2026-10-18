"""Exercise generated dependency documentation through files and the external Maven boundary."""

import copy
import json
import re
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from subprocess import CompletedProcess

from scripts import dependency_docs

from tests.support import copy_scripts, executable, invoke


def node(artifact: str, scope: str = "compile", **extra: object) -> dict[str, Any]:
    """Describe a resolved Maven node independently of POM packaging."""
    return {"groupId": "example", "artifactId": artifact, "version": "1.0", "type": "jar", "scope": scope, **extra}


def metadata(artifact: str, **extra: object) -> dict[str, Any]:
    """Provide complete effective metadata, including independent packaging."""
    return {
        "groupId": "example",
        "artifactId": artifact,
        "version": "1.0",
        "name": artifact.title(),
        "description": f"The {artifact} library.",
        "url": f"https://example.org/{artifact}",
        "packaging": "bundle",
        "licenses": [{"name": "MIT", "url": "https://example.org/licenses/MIT"}],
        **extra,
    }


def override(licenses: list[dict[str, str]], **extra: object) -> dict[str, Any]:
    """Identify reviewed missing metadata without inventing a license relationship."""
    return {
        "licenses": licenses,
        "source": "https://example.org/source/1.0/LICENSE",
        "reason": "The upstream POM omits this declaration.",
        **extra,
    }


def element(root: ET.Element, path: str) -> ET.Element:
    """Require an expected semantic element before inspecting its attributes or text."""
    result = root.find(path)
    assert result is not None, path
    return result


class DependencyDocsTest(unittest.TestCase):
    """Generate real pages around a deterministic Maven executable stand-in."""

    def setUp(self) -> None:
        """Create an isolated project and an external resolver with observable invocations."""
        self.temporary = tempfile.TemporaryDirectory(prefix="dependency-docs-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        copy_scripts(self.root)
        self.license = "MIT License\r\n\r\nExact copyright & <text> {content}\r\n```inside```"
        (self.root / "LICENSE").write_text(self.license, encoding="utf-8", newline="")
        self.tree: dict[str, Any] = {
            "artifactId": "cs1302-tracer",
            "version": "4.2.0-SNAPSHOT",
            "children": [node("library")],
        }
        self.metadata: dict[str, Any] = {"dependencies": [metadata("library")]}
        executable(
            self.root / "mvnw",
            """
import json, sys
from pathlib import Path
root = Path.cwd()
args = sys.argv[1:]
assert args[:5] == ['-B', '-ntp', 'dependency:tree', 'license:add-third-party@dependency-docs', '-DoutputType=json']
assert len(args) == 7
tree = Path(args[5].removeprefix('-DoutputFile='))
output = Path(args[6].removeprefix('-Ddependency.docs.outputDirectory='))
assert tree.is_absolute() and output.is_absolute()
assert tree == output / 'tree.json'
assert not tree.exists() and not (output / 'metadata.json').exists()
with (root / 'maven-invocations.jsonl').open('a') as stream:
    stream.write(json.dumps({'args': args, 'cwd': str(root), 'output': str(output)}) + '\\n')
if (root / 'fail-maven').exists():
    sys.exit(7)
tree.write_text((root / 'tree-input.json').read_text())
if not (root / 'omit-metadata').exists():
    (output / 'metadata.json').write_text((root / 'metadata-input.json').read_text())
""",
        )

    def save(self) -> None:
        """Write resolver inputs as real JSON files."""
        (self.root / "tree-input.json").write_text(json.dumps(self.tree))
        (self.root / "metadata-input.json").write_text(json.dumps(self.metadata))

    def run_cli(self, action: str = "update") -> CompletedProcess[str]:
        """Resolve current inputs through the production command-line entry point."""
        self.save()
        return invoke(self.root, "dependency_docs.py", action)

    def rendered(self) -> tuple[str, ...]:
        """Read both generated files without normalizing the license line endings."""
        return tuple(
            (self.root / "docs/reference" / name).read_text(encoding="utf-8", newline="")
            for name in ("licenses.md", "dependencies.md")
        )

    def test_cli_fresh_resolution_determinism_and_project_version_invariance(self) -> None:
        """Resolve exactly once per call and preserve source text across release-only transitions."""
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        before = self.rendered()
        self.assertIn("first-party cs1302-tracer code and documentation", before[0])
        self.assertIn("````text\n" + self.license + "\n````", before[0])
        self.assertIn("[dependency reference](dependencies.md)", before[0])
        self.assertIn("example:library:jar:1.0", before[1])
        self.assertNotIn("bundle", before[1])
        self.assertIn('<a href="https://example.org/library">library</a>', before[1])
        for project_version in ("4.2.0", "4.2.1-SNAPSHOT"):
            self.tree["version"] = project_version
            result = self.run_cli("check")
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(before, self.rendered())
        records = [json.loads(line) for line in (self.root / "maven-invocations.jsonl").read_text().splitlines()]
        self.assertEqual(len(records), 3)
        self.assertEqual(len({record["output"] for record in records}), 3)
        self.assertTrue(all(record["cwd"] == str(self.root.resolve()) for record in records))
        self.assertTrue(all(not Path(record["output"]).exists() for record in records))

    def test_resolved_scopes_classifiers_directness_and_sorted_mediated_paths(self) -> None:
        """Respect each node's scope, retain classifier variants, and sort metadata-independent output."""
        self.tree["children"] = [
            node("tests", "test", children=[node("annotations"), node("library"), node("helper", "test")]),
            node("library", children=[node("shared")]),
            node("aaa", children=[node("library")]),
            node("library", classifier="tests"),
            node("runtime", "runtime"),
            node("provided", "provided"),
            node("system", "system"),
        ]
        artifacts = ("aaa", "annotations", "helper", "library", "provided", "runtime", "shared", "system", "tests")
        self.metadata["dependencies"] = [metadata(artifact) for artifact in reversed(artifacts)]
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        before = self.rendered()
        runtime, test = before[1].split('<h2 id="test-dependencies">', 1)
        self.assertIn("Details for example:annotations:jar:1.0", runtime)
        self.assertNotIn("example:helper:jar:1.0", runtime)
        self.assertIn("Details for example:helper:jar:1.0", test)
        self.assertIn("example:library:jar:tests:1.0", runtime)
        self.assertIn('class="dependency-relationship">Direct</span>', runtime)
        self.assertIn('class="dependency-relationship">Transitive</span>', runtime)
        self.assertIn('<h2 id="provided-dependencies">', before[1])
        self.assertIn('<h2 id="system-dependencies">', before[1])
        self.assertIn("  - example:tests:jar:1.0 [test]\n    - example:annotations:jar:1.0 [compile]", before[1])
        self.metadata["dependencies"].reverse()
        self.tree["children"].reverse()
        self.tree["children"][-1]["children"].reverse()
        result = self.run_cli("check")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(before, self.rendered())
        rows, _ = dependency_docs.graph(self.tree)
        self.assertTrue(rows["example:library:jar:1.0"].direct)
        self.assertFalse(rows["example:shared:jar:1.0"].direct)

    def test_empty_graph_and_missing_optional_descriptive_fields(self) -> None:
        """Keep missing descriptive metadata explicit and retain empty Runtime/Test sections."""
        self.metadata["dependencies"] = [metadata("library", name=None, description=" ", url="")]
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.rendered()[1].count(dependency_docs.MISSING), 3)
        self.tree = {"artifactId": "cs1302-tracer"}
        self.metadata = {"dependencies": []}
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        page = self.rendered()[1]
        self.assertEqual(page.count("No resolved dependencies in this group."), 2)
        self.assertNotIn('<h2 id="provided-dependencies">', page)
        self.assertNotIn('<h2 id="system-dependencies">', page)

    def test_one_semantic_inventory_has_visible_licenses_and_accessible_native_disclosures(self) -> None:
        """Associate all cells and disclosures without duplicate desktop/mobile dependency content."""
        self.tree["children"] = [node("library"), node("library", classifier="tests"), node("tests", "test")]
        self.metadata["dependencies"] = [metadata("library"), metadata("tests")]
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        page = self.rendered()[1]
        self.assertTrue(page.startswith("---\nhide_table_of_contents: true\n---\n"))
        self.assertIn("3 resolved project dependencies", page)
        self.assertIn("[Runtime (2)](#runtime-dependencies)", page)
        self.assertIn("[Test (1)](#test-dependencies)", page)
        self.assertIn("[Dependency tree](#resolved-dependency-tree)", page)
        self.assertLess(page.index("## Resolved dependency tree"), page.index("## About this inventory"))
        sections = [ET.fromstring(value) for value in re.findall(r"<section\b.*?</section>", page, re.DOTALL)]
        self.assertEqual(len(sections), 2)
        ids = [item.attrib["id"] for section in sections for item in section.iter() if "id" in item.attrib]
        self.assertEqual(len(ids), len(set(ids)))
        entries = []
        for section, group, count in zip(sections, ("runtime", "test"), (2, 1), strict=True):
            self.assertEqual(section.attrib["class"], "dependency-inventory")
            self.assertEqual(section.attrib["aria-labelledby"], group + "-dependencies")
            heading = element(section, "h2")
            self.assertIsNotNone(heading)
            self.assertEqual(heading.attrib["id"], group + "-dependencies")
            self.assertIn(f"({count})", "".join(heading.itertext()))
            table = element(section, "table")
            self.assertIsNotNone(table)
            self.assertEqual(table.attrib["role"], "table")
            self.assertEqual(table.attrib["aria-labelledby"], heading.attrib["id"])
            headers = table.findall("./thead/tr/th")
            self.assertEqual([item.text for item in headers], ["Dependency / version", "Use", "Declared licenses"])
            self.assertTrue(all(item.attrib["scope"] == "col" for item in headers))
            bodies = table.findall("tbody[@class='dependency-entry']")
            self.assertEqual(len(bodies), count)
            entries.extend(bodies)
            for body in bodies:
                self.assertEqual(
                    body.attrib, {"class": "dependency-entry", "data-dependency-entry": "true", "role": "rowgroup"}
                )
                main, disclosure = list(body)
                self.assertEqual(main.tag, "tr")
                self.assertEqual(disclosure.tag, "tr")
                self.assertEqual(len(main), 3)
                name, use, declared = list(main)
                self.assertEqual(name.tag, "th")
                self.assertEqual(name.attrib["scope"], "row")
                self.assertEqual(name.attrib["role"], "rowheader")
                self.assertEqual(name.attrib["class"], "dependency-name")
                self.assertEqual(element(name, "span[@class='dependency-version']").text, "Version 1.0")
                for cell, column in ((use, "use"), (declared, "licenses")):
                    self.assertEqual(cell.attrib["headers"], f"{name.attrib['id']} {group}-{column}")
                    label = element(cell, "span[@class='dependency-mobile-label']")
                    self.assertEqual(label.attrib["aria-hidden"], "true")
                self.assertIn("Scope: ", use.attrib["aria-label"])
                self.assertIn("relationship: Direct", use.attrib["aria-label"])
                self.assertIn(" · Direct", "".join(use.itertext()))
                self.assertEqual(len(declared.findall("ul/li/a")), 1)
                self.assertEqual(element(declared, "ul/li/a").text, "MIT")
                self.assertEqual(len(disclosure), 1)
                self.assertEqual(disclosure[0].attrib["colspan"], "3")
                details = element(disclosure, "td/details")
                self.assertEqual(details.attrib["class"], "dependency-details")
                self.assertNotIn("open", details.attrib)
                summary = element(details, "summary")
                self.assertEqual(summary.attrib["class"], "dependency-summary")
                self.assertEqual(summary.text, "Details")
                detail = element(details, "div[@class='dependency-metadata']")
                coordinate = element(detail, ".//code[@class='dependency-coordinate']").text
                self.assertEqual(summary.attrib["aria-label"], f"Details for {coordinate}")
                self.assertEqual(element(detail, "p").text, f"The {'tests' if group == 'test' else 'library'} library.")
                self.assertIn("Artifact type", "".join(detail.itertext()))
                if coordinate == "example:library:jar:tests:1.0":
                    self.assertIn("Classifier", "".join(detail.itertext()))
                self.assertEqual(len(body.findall(".//a")), 2)
        self.assertEqual(len(entries), 3)
        self.assertNotIn("<script", page)
        self.assertNotIn("onclick=", page)

    def test_compact_subgroups_sort_artifact_ids_and_disambiguate_global_collisions(self) -> None:
        """Count real entries, order by artifact identity, and retain names and all variant coordinates."""
        self.tree["children"] = [
            node(
                "zulu",
                groupId="a",
                children=[
                    node("middle", groupId="a"),
                    node("same", groupId="trans", type="zip"),
                    node("same", groupId="trans", classifier="sources"),
                    node("same", groupId="trans"),
                    node("aardvark", groupId="z"),
                ],
            ),
            node("same", groupId="direct"),
            node("beta", groupId="z"),
            node("Alpha", groupId="q"),
            node("same", "test", groupId="testing"),
            node("only-test", "test", children=[node("test-child", "test")]),
        ]
        self.metadata["dependencies"] = [
            metadata(artifact, groupId=group, name=f"The <{artifact}> upstream {{project}}")
            for group, artifact in (
                ("a", "zulu"),
                ("direct", "same"),
                ("z", "beta"),
                ("q", "Alpha"),
                ("testing", "same"),
                ("example", "only-test"),
                ("example", "test-child"),
                ("a", "middle"),
                ("trans", "same"),
                ("z", "aardvark"),
            )
        ]
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        page = self.rendered()[1]
        sections = [ET.fromstring(value) for value in re.findall(r"<section\b.*?</section>", page, re.DOTALL)]
        expected = [
            [
                (
                    "Direct dependencies (4)",
                    ["q:Alpha:jar:1.0", "z:beta:jar:1.0", "direct:same:jar:1.0", "a:zulu:jar:1.0"],
                ),
                (
                    "Transitive dependencies (5)",
                    [
                        "z:aardvark:jar:1.0",
                        "a:middle:jar:1.0",
                        "trans:same:jar:1.0",
                        "trans:same:jar:sources:1.0",
                        "trans:same:zip:1.0",
                    ],
                ),
            ],
            [
                ("Direct dependencies (2)", ["example:only-test:jar:1.0", "testing:same:jar:1.0"]),
                ("Transitive dependencies (1)", ["example:test-child:jar:1.0"]),
            ],
        ]
        self.assertIn("[Runtime (9)](#runtime-dependencies)", page)
        self.assertIn("[Test (3)](#test-dependencies)", page)
        qualifiers = []
        for section, expected_groups in zip(sections, expected, strict=True):
            actual: list[tuple[str, list[str | None]]] = []
            table = element(section, "table")
            for body in table.findall("tbody"):
                if body.attrib["class"] == "dependency-subgroup":
                    self.assertNotIn("data-dependency-entry", body.attrib)
                    self.assertEqual(len(body), 1)
                    cell = element(body, "tr/td")
                    self.assertEqual(cell.attrib, {"colspan": "3", "role": "cell"})
                    heading = element(cell, "h3")
                    relationship = "direct" if not actual else "transitive"
                    group = section.attrib["aria-labelledby"].removesuffix("-dependencies")
                    self.assertEqual(heading.attrib["id"], f"{group}-{relationship}-dependencies")
                    actual.append(("".join(heading.itertext()), []))
                    continue
                coordinate = element(body, ".//code[@class='dependency-coordinate']").text
                assert coordinate is not None
                actual[-1][1].append(coordinate)
                name = element(body, "tr/th[@class='dependency-name']")
                artifact = coordinate.split(":")[1]
                self.assertEqual(element(name, "a").text, artifact)
                self.assertEqual(element(name, "span[@class='dependency-version']").text, "Version 1.0")
                qualified = name.find("span[@class='dependency-group-id']")
                if artifact == "same":
                    assert qualified is not None
                    self.assertEqual(qualified.text, coordinate.split(":")[0])
                    qualifiers.append(qualified.text)
                else:
                    self.assertIsNone(qualified)
                detail = element(body, ".//div[@class='dependency-metadata']")
                self.assertEqual(element(detail, "dl/dt").text, "Upstream project name")
                self.assertEqual(element(detail, "dl/dd").text, f"The <{artifact}> upstream {{project}}")
            self.assertEqual(actual, expected_groups)
        self.assertEqual(qualifiers, ["direct", "trans", "trans", "trans", "testing"])
        self.metadata["dependencies"].reverse()
        self.tree["children"].reverse()
        self.assertEqual(self.run_cli("check").returncode, 0)

    def test_html_link_attributes_preserve_safe_destinations_without_injection(self) -> None:
        """Encode URL punctuation and attributes while keeping metadata literal inside native HTML."""
        record = self.metadata["dependencies"][0]
        record["url"] = "https://example.org/?a='value'&b=\"quoted\""
        record["description"] = '<script>alert("x")</script> <img src=x onerror=run()> {value}'
        record["name"] = '<a href="javascript:run()">Label</a>'
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        page = self.rendered()[1]
        section = ET.fromstring(re.findall(r"<section\b.*?</section>", page, re.DOTALL)[0])
        self.assertEqual(section.findall(".//script"), [])
        self.assertEqual(section.findall(".//img"), [])
        links = section.findall(".//a")
        self.assertEqual(len(links), 2)
        self.assertEqual(links[0].text, "library")
        self.assertEqual(element(section, ".//div[@class='dependency-metadata']/dl/dd").text, record["name"])
        self.assertEqual(links[0].attrib, {"href": "https://example.org/?a='value'&b=%22quoted%22"})
        self.assertIn("&amp;b=%22quoted%22", page)
        self.assertEqual(element(section, ".//div[@class='dependency-metadata']/p").text, record["description"])

    def test_markdown_metadata_and_links_are_literal_and_url_safe(self) -> None:
        """Escape MDX and table punctuation without turning arbitrary metadata into executable markup."""
        self.metadata["dependencies"] = [
            metadata(
                "library",
                name='A | <Component>{x} [`click`](bad) *bold* \\ "quote"',
                description="first\nsecond & last",
                url="https://example.org/(project)/{name}?x=[yes]",
                licenses=[
                    {"name": "License `two` | {x}", "url": "https://example.org/(two)/[license]"},
                    {"name": "License one", "url": "http://example.org/one"},
                ],
            )
        ]
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        page = self.rendered()[1]
        self.assertIn("A &#124; &lt;Component&gt;&#123;x&#125;", page)
        self.assertIn("&#91;&#96;click&#96;&#93;(bad) &#42;bold&#42; &#92; &quot;quote&quot;", page)
        self.assertIn("first second &amp; last", page)
        self.assertIn('<a href="https://example.org/%28project%29/%7Bname%7D?x=%5Byes%5D">', page)
        self.assertIn(
            '<a href="https://example.org/%28two%29/%5Blicense%5D">License &#96;two&#96; &#124; &#123;x&#125;</a>', page
        )
        self.assertIn('<a href="http://example.org/one">License one</a>', page)
        for project_url in ("javascript:alert(1)", "not a URL", "https://[broken"):
            with self.subTest(project_url=project_url):
                self.metadata["dependencies"][0]["url"] = project_url
                result = self.run_cli()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn(dependency_docs.escaped(project_url) + " (not an HTTP(S) link)", self.rendered()[1])
        self.assertEqual(dependency_docs.fenced("body\n"), "```text\nbody\n```\n")

    def test_check_rejects_missing_and_changed_pages_without_writing(self) -> None:
        """Surface drift for both files and require an explicit update."""
        result = self.run_cli("check")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("docs/reference/licenses.md", result.stderr)
        self.assertIn("docs/reference/dependencies.md", result.stderr)
        self.assertFalse((self.root / "docs").exists())
        self.assertEqual(self.run_cli().returncode, 0)
        path = self.root / "docs/reference/dependencies.md"
        path.write_text("Changed page\n")
        result = self.run_cli("check")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("-Changed page", result.stderr)
        self.assertIn("scripts/dependency_docs.py update", result.stderr)
        self.assertEqual(path.read_text(), "Changed page\n")

    def test_failed_resolution_never_reuses_previous_outputs_or_changes_pages(self) -> None:
        """Maven failures and missing fresh exports preserve complete tracked documentation."""
        self.assertEqual(self.run_cli().returncode, 0)
        before = self.rendered()
        for marker, message in (("fail-maven", "exit status 7"), ("omit-metadata", "metadata.json")):
            with self.subTest(marker=marker):
                (self.root / marker).touch()
                result = self.run_cli()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)
                self.assertEqual(before, self.rendered())
                (self.root / marker).unlink()
        (self.root / "scripts/dependency-license-overrides.json").write_text("{broken")
        self.assertNotEqual(self.run_cli().returncode, 0)
        self.assertEqual(before, self.rendered())

    def test_invalid_cli_action_fails_before_resolution(self) -> None:
        """Keep unsupported modes outside the resolver."""
        result = self.run_cli("repair")
        self.assertEqual(result.returncode, 2)
        self.assertIn("invalid choice", result.stderr)
        self.assertFalse((self.root / "maven-invocations.jsonl").exists())

    def test_malformed_graphs_are_rejected(self) -> None:
        """Reject ambiguous identities, unsupported scopes, and malformed graph structure."""
        cases: list[tuple[Any, str]] = [
            ([], "Expected the cs1302-tracer"),
            ({"artifactId": "other"}, "Expected the cs1302-tracer"),
            ({"artifactId": "cs1302-tracer", "children": {}}, "children must be"),
            ({"artifactId": "cs1302-tracer", "children": [None]}, "children must be"),
        ]
        for field, value, message in (
            ("groupId", "", "groupId"),
            ("artifactId", 3, "artifactId"),
            ("version", "1:2", "Invalid Maven coordinate"),
            ("version", "1 2", "Invalid Maven coordinate"),
            ("type", None, "resolved type"),
            ("scope", "import", "Unsupported effective scope"),
            ("type", "j ar", "Invalid resolved type"),
            ("classifier", "a:b", "Invalid resolved type"),
            ("children", [False], "children must be"),
        ):
            cases.append(({"artifactId": "cs1302-tracer", "children": [node("library") | {field: value}]}, message))
        cases.append(
            ({"artifactId": "cs1302-tracer", "children": [node("library"), node("library", "test")]}, "Conflicting")
        )
        for tree, message in cases:
            with self.subTest(tree=tree), self.assertRaisesRegex(ValueError, message):
                dependency_docs.graph(tree)

    def test_metadata_alignment_and_override_mapping_are_required(self) -> None:
        """Fail closed for missing, extra, duplicate, malformed, or stale records."""
        metadata_cases: list[tuple[Any, str]] = [
            ([], "dependency list"),
            ({}, "dependency list"),
            ({"dependencies": {}}, "dependency list"),
            ({"dependencies": [None]}, "must be an object"),
            ({"dependencies": [metadata("library"), metadata("library")]}, "Duplicate"),
            ({"dependencies": []}, "missing=.*example:library:1.0"),
            ({"dependencies": [metadata("library"), metadata("extra")]}, "extra=.*example:extra:1.0"),
        ]
        for value, message in metadata_cases:
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, message):
                dependency_docs.pages(self.root, self.tree, value, {})
        override_cases: list[tuple[Any, str]] = [
            ([], "Unmatched"),
            ({"example:stale:1.0": {}}, "Unmatched"),
            ({"example:library:1.0": None}, "must be objects"),
            ({"example:library:1.0": []}, "must be objects"),
        ]
        for value, message in override_cases:
            with self.subTest(overrides=value), self.assertRaisesRegex(ValueError, message):
                dependency_docs.pages(self.root, self.tree, self.metadata, value)

    def test_missing_invalid_licenses_and_override_shapes_are_rejected(self) -> None:
        """Reject incomplete declarations and unsafe destinations instead of fabricating licensing."""
        for declarations, message in (
            (None, "Licenses must be"),
            ({}, "Licenses must be"),
            ([None], "Licenses must be"),
            ([], "Missing license"),
            ([{"name": "MIT"}], "Missing license"),
            ([{"url": "https://example.org/license"}], "Missing license"),
            ([{"name": 4, "url": "https://example.org/license"}], "invalid license name"),
            ([{"name": "MIT", "url": "ftp://example.org/license"}], "Invalid HTTP"),
            ([{"name": "MIT", "url": "https://example.org/line\nbreak"}], "Invalid HTTP"),
        ):
            with self.subTest(declarations=declarations), self.assertRaisesRegex(ValueError, message):
                dependency_docs.licenses(metadata("library", licenses=declarations), None)
        self.assertRaisesRegex(
            ValueError,
            "Missing license",
            dependency_docs.licenses,
            {"groupId": "g", "artifactId": "a", "version": "1"},
            None,
        )
        for value, message in (
            ([], "require licenses, source, and reason"),
            ({}, "require licenses, source, and reason"),
            (override([], source="http://example.org/source"), "Invalid HTTPS"),
            (override([], reason=" "), "override reason"),
            (override([], source=" "), "override source"),
            (override([]), "Missing license"),
        ):
            with self.subTest(override=value), self.assertRaisesRegex(ValueError, message):
                dependency_docs.licenses(metadata("library", licenses=[]), value)
        complete = metadata("library")
        with self.assertRaisesRegex(ValueError, "Stale license override"):
            dependency_docs.licenses(complete, override(complete["licenses"]))

    def test_reviewed_overrides_preserve_every_existing_declaration(self) -> None:
        """Fill missing fields, retain duplicate and complete licenses, and expose reviewed provenance."""
        mit = {"name": "MIT", "url": "https://example.org/licenses/MIT"}
        apache = {"name": "Apache 2.0", "url": "https://example.org/licenses/Apache"}
        incomplete = [mit, {"name": "Apache 2.0"}, {"url": apache["url"]}, {}]
        self.metadata["dependencies"][0]["licenses"] = incomplete
        overrides = {
            "example:library:1.0": override([mit, apache, apache, mit], reason="Verified <source> | {release}.")
        }
        path = self.root / "scripts/dependency-license-overrides.json"
        path.write_text(json.dumps(overrides))
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        page = self.rendered()[1]
        self.assertEqual(page.count('<a href="https://example.org/licenses/MIT">MIT</a>'), 2)
        self.assertEqual(page.count('<a href="https://example.org/licenses/Apache">Apache 2.0</a>'), 2)
        self.assertIn(
            'License metadata override:</strong> <a href="https://example.org/source/1.0/LICENSE">Reviewed source</a>',
            page,
        )
        self.assertIn("Verified &lt;source&gt; &#124; &#123;release&#125;.", page)
        before = self.rendered()
        for replacement in ([apache, apache, apache, apache], [mit, mit, mit, mit], [mit, apache]):
            with self.subTest(replacement=replacement):
                overrides["example:library:1.0"]["licenses"] = replacement
                path.write_text(json.dumps(overrides))
                result = self.run_cli()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("preserve every upstream declaration", result.stderr)
                self.assertEqual(before, self.rendered())
        record = metadata("library", licenses=[])
        self.assertEqual(dependency_docs.licenses(record, override([mit]))[0].count(">MIT</a>"), 1)
        alternate = {"name": "MIT", "url": "https://example.org/licenses/other"}
        ambiguous = metadata("library", licenses=[{"name": "MIT"}, {"url": mit["url"]}])
        for replacement in ([mit, alternate], [alternate, mit]):
            with self.subTest(ambiguous=replacement):
                result = dependency_docs.licenses(ambiguous, override(replacement))[0]
                self.assertIn('<a href="https://example.org/licenses/MIT">MIT</a>', result)
                self.assertIn('<a href="https://example.org/licenses/other">MIT</a>', result)

    def test_validation_finishes_before_either_page_is_written(self) -> None:
        """A bad license leaves both existing generated documents intact."""
        self.assertEqual(self.run_cli().returncode, 0)
        before = self.rendered()
        self.metadata["dependencies"][0]["licenses"] = []
        (self.root / "LICENSE").write_text("changed source license")
        result = self.run_cli()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing license", result.stderr)
        self.assertEqual(before, self.rendered())

    def test_non_string_optional_metadata_is_invalid(self) -> None:
        """Do not silently stringify malformed upstream fields into the inventory."""
        for field in ("name", "description", "url"):
            with (
                self.subTest(field=field),
                self.assertRaisesRegex(ValueError, f"invalid {'project URL' if field == 'url' else field}"),
            ):
                broken = copy.deepcopy(self.metadata)
                broken["dependencies"][0][field] = []
                dependency_docs.pages(self.root, self.tree, broken, {})
