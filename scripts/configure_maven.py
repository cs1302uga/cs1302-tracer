"""Install the verified JVNet mirror while preserving other Maven settings."""

import argparse
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

MIRROR = Path(__file__).resolve().parents[1] / ".mvn/jvnet-mirror.xml"


def configure(settings: Path) -> Path | None:
    """Merge the shared mirror atomically, returning an owner-only backup if changed."""
    source = ET.parse(MIRROR).getroot()
    original = settings.read_bytes() if settings.exists() else None
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    root = ET.fromstring(original, parser=parser) if original is not None else ET.Element("settings")
    namespace = root.tag.removesuffix("settings")
    if root.tag.rsplit("}", 1)[-1] != "settings":
        raise ValueError("Expected a Maven settings document")
    if namespace:
        ET.register_namespace("", namespace[1:-1])
    mirrors = root.find(f"{namespace}mirrors")
    if mirrors is None:
        mirrors = ET.SubElement(root, f"{namespace}mirrors")
    wanted = {child.tag: child.text for child in source}
    matches = [
        mirror
        for mirror in mirrors
        if mirror.findtext(f"{namespace}id") == wanted["id"]
        or mirror.findtext(f"{namespace}mirrorOf") == wanted["mirrorOf"]
    ]
    if len(matches) == 1 and {child.tag.removeprefix(namespace): child.text for child in matches[0]} == wanted:
        return None
    for mirror in matches:
        mirrors.remove(mirror)
    added = ET.SubElement(mirrors, f"{namespace}mirror")
    for key, value in wanted.items():
        ET.SubElement(added, f"{namespace}{key}").text = value
    ET.indent(root)
    updated = ET.tostring(root, encoding="utf-8", xml_declaration=True)
    settings.parent.mkdir(parents=True, exist_ok=True)
    backup = None
    if original is not None:
        with tempfile.NamedTemporaryFile(
            prefix=f"{settings.name}.backup-", dir=settings.parent, delete=False
        ) as stream:
            stream.write(original)
            backup = Path(stream.name)
    with tempfile.NamedTemporaryFile(prefix=f".{settings.name}.", dir=settings.parent, delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(updated)
    try:
        temporary.replace(settings)
    finally:
        temporary.unlink(missing_ok=True)
    return backup


def main() -> None:
    """Configure the default user settings or an explicit settings file."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("settings", nargs="?", type=Path, default=Path.home() / ".m2/settings.xml")
    args = parser.parse_args()
    backup = configure(args.settings)
    print("Maven JVNet mirror configured.")
    if backup is not None:
        print(f"Backup: {backup}")


if __name__ == "__main__":
    main()
