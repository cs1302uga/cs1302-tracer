"""Exercise the version policy against valid POMs and realistic editing mistakes."""

import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from scripts.check_pom import DEFAULT_POM, NS, check


class PomPolicyTests(unittest.TestCase):
    """Check policy diagnostics without relying on Maven or remote repositories."""

    def test_repository_and_cli(self) -> None:
        """The actual POM passes through the library and default CLI."""
        self.assertEqual(check(DEFAULT_POM), [])
        result = subprocess.run(
            [sys.executable, str(DEFAULT_POM.parent / "scripts/check_pom.py")], capture_output=True, text=True
        )
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.assertIn("passed", result.stdout)

    def test_invalid_versions(self) -> None:
        """Reject inline, empty, unknown, and non-concrete dependency versions."""
        for value in ("1.0", "", "${missing.version}", "${project.version}", "${gson.version}"):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as directory:
                root = ET.parse(DEFAULT_POM).getroot()
                version = root.find("m:dependencies/m:dependency/m:version", NS)
                assert version is not None
                version.text = value
                if value == "${gson.version}":
                    prop = root.find("m:properties/m:gson.version", NS)
                    assert prop is not None
                    prop.text = "${another.property}"
                pom = Path(directory) / "pom.xml"
                ET.ElementTree(root).write(pom)
                self.assertTrue(check(pom))

    def test_plugin_ownership(self) -> None:
        """Reject unmanaged, duplicate, unversioned, and overriding plugins."""
        mutations = (
            "unmanaged",
            "wrong-group",
            "profile-management",
            "duplicate",
            "unversioned",
            "override",
            "engine-duplicate",
            "engine-moved",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = ET.parse(DEFAULT_POM).getroot()
                managed = root.find("m:build/m:pluginManagement/m:plugins", NS)
                active = root.find("m:build/m:plugins", NS)
                assert managed is not None and active is not None
                plugin = managed[0]
                if mutation == "unmanaged":
                    active.append(
                        ET.fromstring('<plugin xmlns="' + NS["m"] + '"><artifactId>new</artifactId></plugin>')
                    )
                elif mutation == "wrong-group":
                    group = active[0].find("m:groupId", NS)
                    assert group is not None
                    group.text = "unmanaged.group"
                elif mutation == "profile-management":
                    build = root.find("m:profiles/m:profile/m:build", NS)
                    assert build is not None
                    management = ET.SubElement(build, "{" + NS["m"] + "}pluginManagement")
                    ET.SubElement(management, "{" + NS["m"] + "}plugins").append(plugin)
                elif mutation == "duplicate":
                    managed.append(plugin)
                elif mutation == "unversioned":
                    version = plugin.find("m:version", NS)
                    assert version is not None
                    plugin.remove(version)
                elif mutation == "override":
                    ET.SubElement(active[0], "{" + NS["m"] + "}version").text = "${jacoco-maven-plugin.version}"
                else:
                    owner = managed.find("m:plugin[m:artifactId='maven-checkstyle-plugin']/m:dependencies", NS)
                    assert owner is not None
                    destination = ET.SubElement(active[0], "{" + NS["m"] + "}dependencies")
                    destination.append(owner[0])
                    if mutation == "engine-moved":
                        owner.remove(owner[0])
                pom = Path(directory) / "pom.xml"
                ET.ElementTree(root).write(pom)
                self.assertTrue(check(pom))

    def test_json_unit_alignment(self) -> None:
        """Both JsonUnit declarations must use the same property and test scope."""
        for mutation in ("missing", "duplicate", "groupId", "version", "scope"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = ET.parse(DEFAULT_POM).getroot()
                deps = root.find("m:dependencies", NS)
                assert deps is not None
                node = deps.find("m:dependency[m:artifactId='json-unit']", NS)
                assert node is not None
                if mutation == "missing":
                    deps.remove(node)
                elif mutation == "duplicate":
                    deps.append(node)
                else:
                    field = node.find("m:" + mutation, NS)
                    assert field is not None
                    field.text = "wrong"
                pom = Path(directory) / "pom.xml"
                ET.ElementTree(root).write(pom)
                self.assertTrue(check(pom))

    def test_cli_failures(self) -> None:
        """Malformed, absent, and policy-invalid files have distinct failure statuses."""
        with tempfile.TemporaryDirectory() as directory:
            pom = Path(directory) / "pom.xml"
            for content, status in ((None, 2), ("<broken", 2), ('<project xmlns="' + NS["m"] + '"/>', 1)):
                with self.subTest(content=content):
                    if content is not None:
                        pom.write_text(content)
                    result = subprocess.run(
                        [sys.executable, str(DEFAULT_POM.parent / "scripts/check_pom.py"), str(pom)],
                        capture_output=True,
                        text=True,
                    )
                    self.assertEqual(result.returncode, status, result.stdout + result.stderr)
