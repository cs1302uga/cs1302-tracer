"""Check the repository's Maven version ownership convention without network access."""

import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
DEFAULT_POM = Path(__file__).resolve().parents[1] / "pom.xml"


def plugin_id(node: ET.Element) -> tuple[str, str]:
    """Identify a plugin, including Maven's default plugin group."""
    return (
        node.findtext("m:groupId", default="org.apache.maven.plugins", namespaces=NS),
        node.findtext("m:artifactId", default="", namespaces=NS),
    )


def check(pom: Path) -> list[str]:
    """Return actionable policy violations for a Maven POM."""
    root = ET.parse(pom).getroot()
    properties = {node.tag.rsplit("}", 1)[-1]: node.text for node in root.findall("m:properties/*", NS)}
    errors = []
    for kind in ("dependency", "plugin", "path"):
        for node in root.findall(f".//m:{kind}", NS):
            artifact = node.findtext("m:artifactId", namespaces=NS)
            for version in node.findall("m:version", NS):
                reference = re.fullmatch(r"\$\{([\w.-]+\.version)\}", version.text or "")
                if reference is None:
                    errors.append(f"{artifact}: version must reference a *.version property")
                elif not re.fullmatch(r"[0-9][\w.-]*", properties.get(reference[1]) or ""):
                    errors.append(f"{artifact}: {reference[1]} must define a concrete version")

    managed = root.findall("m:build/m:pluginManagement/m:plugins/m:plugin", NS)
    managed_ids = [plugin_id(node) for node in managed]
    if len(managed_ids) != len(set(managed_ids)):
        errors.append("pluginManagement: duplicate plugin declaration")
    for plugin in managed:
        if plugin.find("m:version", NS) is None:
            errors.append(f"{plugin.findtext('m:artifactId', namespaces=NS)}: managed plugin needs a version")
    active = root.findall("m:build/m:plugins/m:plugin", NS)
    active += root.findall("m:profiles/m:profile/m:build/m:plugins/m:plugin", NS)
    if root.findall("m:profiles/m:profile/m:build/m:pluginManagement/m:plugins/m:plugin", NS):
        errors.append("profiles: inherit plugin management from the top-level build")
    for plugin in active:
        artifact = plugin.findtext("m:artifactId", namespaces=NS)
        if plugin_id(plugin) not in managed_ids:
            errors.append(f"{artifact}: active plugin must have a pluginManagement declaration")
        if plugin.find("m:version", NS) is not None:
            errors.append(f"{artifact}: active plugin must inherit its version")

    engines = root.findall(".//m:plugin/m:dependencies/m:dependency[m:artifactId='checkstyle']", NS)
    managed_engines = root.findall(
        "m:build/m:pluginManagement/m:plugins/m:plugin[m:artifactId='maven-checkstyle-plugin']"
        "/m:dependencies/m:dependency[m:artifactId='checkstyle']",
        NS,
    )
    if len(engines) != 1 or len(managed_engines) != 1:
        errors.append("checkstyle: declare the engine once, in the managed Checkstyle plugin")

    for artifact in ("json-unit", "json-unit-assertj"):
        nodes = root.findall(f"m:dependencies/m:dependency[m:artifactId='{artifact}']", NS)
        if len(nodes) != 1:
            errors.append(f"{artifact}: declare exactly one direct test dependency")
        else:
            node = nodes[0]
            if (
                node.findtext("m:groupId", namespaces=NS) != "net.javacrumbs.json-unit"
                or node.findtext("m:version", namespaces=NS) != "${json-unit.version}"
                or node.findtext("m:scope", namespaces=NS) != "test"
            ):
                errors.append(f"{artifact}: use net.javacrumbs.json-unit, test scope, and ${{json-unit.version}}")
    return errors


def main() -> int:
    """Check the default repository POM or an explicitly supplied XML file."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pom", nargs="?", type=Path, default=DEFAULT_POM)
    args = parser.parse_args()
    try:
        errors = check(args.pom)
    except (OSError, ET.ParseError) as error:
        parser.exit(2, f"Cannot read POM: {error}\n")
    if errors:
        print("\n".join(errors))
        return 1
    print("Maven version policy passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
