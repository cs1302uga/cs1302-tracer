"""Verify safe, repeatable Maven settings updates without real credentials."""

import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import patch

from scripts.configure_maven import MIRROR, configure


class MavenSettingsTests(unittest.TestCase):
    """Exercise merging, backup, preservation, and failures with real files."""

    def test_create_and_repeat(self) -> None:
        """Create absent settings, then leave an already configured file untouched."""
        with tempfile.TemporaryDirectory() as directory:
            settings = Path(directory) / "new/settings.xml"
            self.assertIsNone(configure(settings))
            original = settings.read_bytes()
            self.assertIsNone(configure(settings))
            self.assertEqual(settings.read_bytes(), original)
            self.assertEqual(ET.parse(settings).findtext("mirrors/mirror/mirrorOf"), "jvnet-nexus-releases")

    def test_preserve_and_replace(self) -> None:
        """Preserve credentials, profiles, comments, and unrelated repository mirrors."""
        for namespace in ("", ' xmlns="http://maven.apache.org/SETTINGS/1.3.0"'):
            with self.subTest(namespace=namespace), tempfile.TemporaryDirectory() as directory:
                settings = Path(directory) / "settings.xml"
                original = f"""<settings{namespace}>
                  <!-- retained comment -->
                  <servers><server><id>github</id><password>synthetic-secret</password></server></servers>
                  <profiles><profile><id>local</id></profile></profiles>
                  <mirrors>
                    <mirror><id>other</id><mirrorOf>other-repo</mirrorOf><url>https://example.org</url></mirror>
                    <mirror><id>central-for-jvnet-releases</id><url>https://old.example.org</url></mirror>
                    <mirror><id>duplicate</id><mirrorOf>jvnet-nexus-releases</mirrorOf></mirror>
                  </mirrors>
                </settings>""".encode()
                settings.write_bytes(original)
                backup = configure(settings)
                assert backup is not None
                self.assertEqual(backup.read_bytes(), original)
                self.assertEqual(backup.stat().st_mode & 0o777, 0o600)
                self.assertEqual(settings.stat().st_mode & 0o777, 0o600)
                root = ET.parse(settings).getroot()
                self.assertEqual(root.findtext("{*}servers/{*}server/{*}password"), "synthetic-secret")
                self.assertEqual(root.findtext("{*}profiles/{*}profile/{*}id"), "local")
                mirrors = root.findall("{*}mirrors/{*}mirror")
                self.assertEqual(len(mirrors), 2)
                self.assertEqual(mirrors[0].findtext("{*}url"), "https://example.org")
                self.assertIn("retained comment", settings.read_text())
                self.assertIsNone(configure(settings))

    def test_invalid_and_write_failure(self) -> None:
        """Invalid input and failed replacement leave the original settings intact."""
        with tempfile.TemporaryDirectory() as directory:
            settings = Path(directory) / "settings.xml"
            for content, error in (("<broken", ET.ParseError), ("<project/>", ValueError)):
                settings.write_text(content)
                with self.assertRaises(error):
                    configure(settings)
                self.assertEqual(settings.read_text(), content)
            settings.write_text("<settings/>")
            with patch.object(Path, "replace", side_effect=OSError("test replacement failure")):
                with self.assertRaises(OSError):
                    configure(settings)
            self.assertEqual(settings.read_text(), "<settings/>")
            self.assertEqual(list(settings.parent.glob(".settings.xml.*")), [])

    def test_cli(self) -> None:
        """The real CLI reports backup paths without exposing settings contents."""
        with tempfile.TemporaryDirectory() as directory:
            settings = Path(directory) / "settings.xml"
            settings.write_text("<settings><servers/></settings>")
            command = [sys.executable, str(MIRROR.parents[1] / "scripts/configure_maven.py"), str(settings)]
            for first in (True, False):
                result = subprocess.run(command, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("configured", result.stdout)
                self.assertEqual("Backup:" in result.stdout, first)
