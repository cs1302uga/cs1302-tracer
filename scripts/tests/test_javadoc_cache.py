"""Verify historical Javadoc cache identity and complete-tree publication at filesystem boundaries."""

import contextlib
import io
import os
import shutil
import subprocess
import tempfile
import unittest
from datetime import UTC, datetime
from pathlib import Path
from unittest import mock

from javadoc_cache import JavadocCache, fingerprint, inventory
from tests.support import executable


class JavadocCacheTests(unittest.TestCase):
    """Exercise real cached files, Git objects, and a controlled Maven version probe."""

    def setUp(self) -> None:
        """Create an isolated project, user settings, Java executables, and complete generated docs."""
        temporary = tempfile.TemporaryDirectory(prefix="javadoc cache ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        for name in ("website", "scripts", ".mvn/wrapper", "home/.m2", "maven/conf", "tools", "generated/assets"):
            (self.root / name).mkdir(parents=True)
        self.home = self.root / "home"
        self.tools = self.root / "tools"
        for tool in ("java", "javac", "javadoc"):
            executable(self.tools / tool, "pass\n")
        self.environment = mock.patch.dict(
            os.environ,
            {
                "HOME": str(self.home),
                "PATH": str(self.tools) + os.pathsep + os.environ["PATH"],
                "GIT_CONFIG_NOSYSTEM": "1",
                "GIT_CONFIG_GLOBAL": os.devnull,
            },
            clear=True,
        )
        self.environment.start()
        self.addCleanup(self.environment.stop)
        for name in ("build_javadoc.py", "javadoc_cache.py"):
            (self.root / "scripts" / name).write_text("# generator code\n")
        (self.root / ".mvn/wrapper/maven-wrapper.properties").write_text("distributionUrl=immutable-maven.zip\n")
        self.wrapper = """
import os
from pathlib import Path
root = Path.cwd()
with (root / 'probes').open('a') as stream:
    stream.write('probe\\n')
print('Apache Maven 3.test\\nMaven home: ' + str(root / 'maven'))
print('Java version: ' + os.environ.get('CACHE_TEST_JAVA_VERSION', '25'))
"""
        executable(self.root / "mvnw", self.wrapper)
        (self.root / "pom.xml").write_text("<project><version>1.0.0</version></project>")
        self.git("init", "-q")
        self.git("add", "pom.xml")
        self.git("commit", "-qm", "Release sources")
        self.commit = self.git("rev-parse", "HEAD")
        self.source = self.root / "generated"
        for name in ("index.html", "search.html", "element-list", "assets/style.css"):
            (self.source / name).write_text("Generated " + name)
        self.cache = JavadocCache(self.root, "use")
        self.key = "a" * 64
        self.entry = self.cache.directory / self.key
        self.destination = self.root / "restored"

    def git(self, *arguments: str) -> str:
        """Create and read isolated committed build inputs without host signing settings."""
        return subprocess.run(
            [
                "git",
                "-c",
                "user.name=Cache Test",
                "-c",
                "user.email=cache@example.invalid",
                "-c",
                "commit.gpgSign=false",
                "-c",
                "core.hooksPath=/dev/null",
                *arguments,
            ],
            cwd=self.root,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    def current_key(self) -> str | None:
        """Compute the key for this revision with a fresh invocation context."""
        return JavadocCache(self.root, "use").key(self.commit)

    def test_modes_are_lazy_and_probe_once_per_build(self) -> None:
        """Off performs no cache IO or tool probe; use shares its tool identity across releases."""
        with self.assertRaisesRegex(ValueError, "Unknown Javadoc cache mode"):
            JavadocCache(self.root, "invalid")
        disabled = JavadocCache(self.root, "off")
        self.assertIsNone(disabled.key(self.commit))
        self.assertFalse(disabled.restore(self.key, self.destination))
        disabled.save(self.key, self.source)
        self.assertFalse((self.root / "probes").exists())
        self.assertFalse(self.cache.directory.exists())
        key = self.cache.key(self.commit)
        self.assertIsNotNone(key)
        self.assertEqual(self.cache.key(self.commit), key)
        self.assertEqual((self.root / "probes").read_text().splitlines(), ["probe"])
        self.assertFalse(self.cache.restore(None, self.destination))
        self.cache.save(None, self.source)
        self.assertFalse(self.cache.restore(self.key, self.destination))
        self.assertFalse(JavadocCache(self.root, "refresh").restore(self.key, self.destination))

    def test_identity_changes_with_sources_tools_configuration_platform_and_year(self) -> None:
        """Invalidate all stated generation inputs without treating unrelated PATH additions as changes."""
        original = self.current_key()
        unrelated = self.root / "unrelated-bin"
        unrelated.mkdir()
        with mock.patch.dict(os.environ, PATH=str(unrelated) + os.pathsep + os.environ["PATH"]):
            self.assertEqual(self.current_key(), original)
        executable(unrelated / "java", "# different selected Java\n")
        with mock.patch.dict(os.environ, PATH=str(unrelated) + os.pathsep + os.environ["PATH"]):
            self.assertNotEqual(self.current_key(), original)
        for name in (
            "scripts/build_javadoc.py",
            "scripts/javadoc_cache.py",
            ".mvn/wrapper/maven-wrapper.properties",
            "mvnw",
        ):
            path = self.root / name
            before = path.read_text()
            path.write_text(before + "\n# changed input\n")
            self.assertNotEqual(self.current_key(), original)
            path.write_text(before)
        with mock.patch.dict(os.environ, CACHE_TEST_JAVA_VERSION="26"):
            self.assertNotEqual(self.current_key(), original)
        with mock.patch("javadoc_cache.platform.platform", return_value="another-platform"):
            self.assertNotEqual(self.current_key(), original)
        with mock.patch("javadoc_cache.datetime") as clock:
            clock.now.return_value = datetime(2030, 1, 1, tzinfo=UTC)
            first_year = self.current_key()
            clock.now.return_value = datetime(2031, 1, 1, tzinfo=UTC)
            self.assertNotEqual(self.current_key(), first_year)
        tool_identity = fingerprint(self.root)
        with mock.patch.dict(os.environ, PATH=""):
            self.assertNotEqual(fingerprint(self.root), tool_identity)
        (self.root / "pom.xml").write_text("<project><version>1.1.0</version></project>")
        self.git("add", "pom.xml")
        self.git("commit", "-qm", "Next immutable source")
        self.assertNotEqual(self.cache.key(self.git("rev-parse", "HEAD")), original)

    def test_authentication_rotation_is_ignored_but_resolution_configuration_is_not(self) -> None:
        """Keep rotating credentials out of identity while honoring repositories and profile environment."""
        settings = self.home / ".m2/settings.xml"
        template = """<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
<servers><server><id>github</id><username>${env.GITHUB_ACTOR}</username><password>${env.GITHUB_TOKEN}</password>
<privateKey>secret-key</privateKey><passphrase>secret-passphrase</passphrase></server></servers>
<proxies><proxy><id>proxy</id><username>proxy-user</username><password>proxy-secret</password>
<host>proxy.example.invalid</host></proxy></proxies>
<mirrors><mirror><id>central</id><url>https://central.example.invalid</url></mirror></mirrors>
<profiles><profile><id>${env.DOC_PROFILE}</id></profile></profiles></settings>"""
        settings.write_text(template)
        with mock.patch.dict(
            os.environ,
            GITHUB_ACTOR="first",
            GITHUB_TOKEN="secret-one",
            DOC_PROFILE="docs",
            HTTPS_PROXY="http://first:secret-one@proxy.example.invalid:8080",
        ):
            original = self.current_key()
            os.environ.update(
                GITHUB_ACTOR="second",
                GITHUB_TOKEN="secret-two",
                HTTPS_PROXY="http://second:secret-two@proxy.example.invalid:8080",
            )
            settings.write_text(template.replace("secret-passphrase", "new-secret").replace("proxy-user", "new-user"))
            self.assertEqual(self.current_key(), original)
            os.environ["DOC_PROFILE"] = "other-profile"
            self.assertNotEqual(self.current_key(), original)
            os.environ["DOC_PROFILE"] = "docs"
            settings.write_text(template.replace("https://central.example.invalid", "https://mirror.example.invalid"))
            self.assertNotEqual(self.current_key(), original)
        self.cache.save(self.key, self.source)
        manifest = (self.entry / "manifest.json").read_text()
        for secret in ("secret-one", "secret-two", "secret-passphrase", "proxy-secret", "new-secret"):
            self.assertNotIn(secret, manifest)

    def test_custom_settings_toolchains_and_archived_pom_environment_are_inputs(self) -> None:
        """Track explicit Maven configuration paths and dynamic properties in immutable source POMs."""
        custom = self.root / "custom-home"
        (custom / ".m2").mkdir(parents=True)
        (custom / ".m2/settings.xml").write_text("<settings/>")
        for name in ("custom-settings.xml", "global-settings.xml", "toolchains.xml", "global-tools.xml"):
            (self.root / name).write_text("<settings/>" if "settings" in name else "<toolchains/>")
        (self.root / ".mvn/maven.config").write_text(
            "-s custom-settings.xml --global-settings=global-settings.xml -t toolchains.xml "
            "--global-toolchains global-tools.xml"
        )
        (self.root / ".mvn/jvm.config").write_text(f'-Duser.home="{custom}" -Dmaven.user.home="{custom}"')
        with mock.patch.dict(os.environ, MAVEN_USER_HOME=str(custom)):
            original = self.current_key()
            for name in ("custom-settings.xml", "global-settings.xml", "toolchains.xml", "global-tools.xml"):
                path = self.root / name
                prior = path.read_text()
                path.write_text(
                    prior.replace("/>", "><changed/></settings>" if "settings" in name else "><changed/></toolchains>")
                )
                self.assertNotEqual(self.current_key(), original)
                path.write_text(prior)
        (self.root / "pom.xml").write_text("<project><version>1.0.0</version><name>${env.DOC_LABEL}</name></project>")
        self.git("add", "pom.xml")
        self.git("commit", "-qm", "Archived dynamic property")
        self.commit = self.git("rev-parse", "HEAD")
        with mock.patch.dict(os.environ, DOC_LABEL="first"):
            first = self.current_key()
            os.environ["DOC_LABEL"] = "second"
            self.assertNotEqual(self.current_key(), first)

    def test_probe_or_input_failures_disable_cache_without_revealing_configuration(self) -> None:
        """Unavailable identity is a miss, with one generic warning and no captured subprocess output."""
        with contextlib.redirect_stdout(io.StringIO()) as output:
            executable(self.root / "mvnw", "import sys; print('sensitive-value'); sys.exit(1)\n")
            self.assertIsNone(self.cache.key(self.commit))
            self.assertIsNone(self.cache.key(self.commit))
            self.assertEqual(output.getvalue().count("cache unavailable"), 1)
            self.assertNotIn("sensitive-value", output.getvalue())
            executable(self.root / "mvnw", "print('incomplete identity')\n")
            self.assertIsNone(self.current_key())
            executable(self.root / "mvnw", self.wrapper)
            with mock.patch.dict(os.environ, MAVEN_ARGS="--settings"):
                self.assertIsNone(self.current_key())
            (self.home / ".m2/settings.xml").write_text("not XML")
            self.assertIsNone(self.current_key())
            (self.home / ".m2/settings.xml").unlink()
            self.assertIsNone(JavadocCache(self.root, "use").key("0" * 40))
        self.assertFalse(self.cache.directory.exists())

    def test_complete_tree_round_trip_and_refresh(self) -> None:
        """Restore exactly the saved files and replace an old entry only with a complete new tree."""
        expected = inventory(self.source)
        self.assertIsNone(expected["assets"])
        self.cache.save(self.key, self.source)
        self.assertTrue(self.cache.restore(self.key, self.destination))
        self.assertEqual(inventory(self.destination), expected)
        self.assertFalse(self.cache.restore(self.key, self.destination))
        (self.source / "index.html").write_text("refreshed output")
        JavadocCache(self.root, "refresh").save(self.key, self.source)
        another = self.root / "another"
        self.assertTrue(self.cache.restore(self.key, another))
        self.assertEqual((another / "index.html").read_text(), "refreshed output")
        self.assertEqual(sorted(path.name for path in self.cache.directory.iterdir()), [self.key])

    def test_corrupt_manifests_and_inventories_are_misses(self) -> None:
        """Treat invalid JSON, unknown schemas, altered inventory, and incomplete output as misses."""
        self.cache.save(self.key, self.source)
        manifest = self.entry / "manifest.json"
        original = manifest.read_text()
        for value in (
            "not JSON",
            "[]",
            "[" * 1500 + "]" * 1500,
            original.replace('"schemaVersion": 1', '"schemaVersion": 2'),
            original.replace(self.key, "b" * 64),
        ):
            manifest.write_text(value)
            self.assertFalse(self.cache.restore(self.key, self.destination))
        with manifest.open("wb") as stream:
            stream.truncate(16 * 1024 * 1024 + 1)
        self.assertFalse(self.cache.restore(self.key, self.destination))
        manifest.write_text(original)
        output = self.entry / "output"
        (output / "assets/unexpected.css").write_text("unexpected")
        self.assertFalse(self.cache.restore(self.key, self.destination))
        (output / "assets/unexpected.css").unlink()
        (output / "index.html").write_text("corrupt content")
        self.assertFalse(self.cache.restore(self.key, self.destination))
        (output / "search.html").unlink()
        self.assertFalse(self.cache.restore(self.key, self.destination))
        self.assertFalse(self.destination.exists())

    def test_symlinks_and_special_paths_are_never_followed(self) -> None:
        """Reject redirected roots, entries, manifests, and tree members including FIFOs."""
        regular = self.root / "regular-file"
        regular.write_text("not a directory")
        with self.assertRaises(ValueError):
            inventory(regular)
        link = self.source / "assets/linked"
        link.symlink_to(regular)
        with self.assertRaises(ValueError):
            inventory(self.source)
        link.unlink()
        os.mkfifo(link)
        with self.assertRaises(ValueError):
            inventory(self.source)
        link.unlink()
        self.cache.save(self.key, self.source)
        manifest = self.entry / "manifest.json"
        manifest.rename(self.entry / "saved.json")
        manifest.symlink_to(self.entry / "saved.json")
        self.assertFalse(self.cache.restore(self.key, self.destination))
        self.cache.save(self.key, self.source)
        shutil.rmtree(self.entry)
        self.entry.symlink_to(self.source, target_is_directory=True)
        self.assertFalse(self.cache.restore(self.key, self.destination))
        self.cache.save(self.key, self.source)
        self.assertTrue(self.cache.restore(self.key, self.destination))
        shutil.rmtree(self.cache.directory)
        self.cache.directory.symlink_to(self.source, target_is_directory=True)
        self.assertFalse(self.cache.restore(self.key, self.root / "untrusted"))
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.cache.save(self.key, self.source)
        self.assertIn("cache write failed", output.getvalue())
        self.assertFalse((self.source / self.key).exists())

    def test_io_failures_and_copy_time_changes_never_install_partial_results(self) -> None:
        """Fail cache operations safely at external filesystem boundaries without discarding fresh docs."""
        self.cache.save(self.key, self.source)
        original_manifest = (self.entry / "manifest.json").read_bytes()
        copy_tree = shutil.copytree

        def changed_copy(source: Path, destination: Path, *args, **kwargs) -> str:
            copied = copy_tree(source, destination, *args, **kwargs)
            target = Path(destination) / "index.html"
            if target.is_file():
                target.write_text("changed during copy")
            return str(copied)

        with mock.patch("shutil.copytree", side_effect=changed_copy):
            self.assertFalse(self.cache.restore(self.key, self.destination))
            with contextlib.redirect_stdout(io.StringIO()) as output:
                self.cache.save(self.key, self.source)
            self.assertIn("cache write failed", output.getvalue())
        self.assertEqual((self.entry / "manifest.json").read_bytes(), original_manifest)
        with mock.patch("pathlib.Path.read_text", side_effect=PermissionError("unreadable cache")):
            self.assertFalse(self.cache.restore(self.key, self.destination))
        with mock.patch("shutil.copytree", side_effect=PermissionError("cannot copy")):
            self.assertFalse(self.cache.restore(self.key, self.destination))
            with contextlib.redirect_stdout(io.StringIO()):
                self.cache.save(self.key, self.source)
        self.assertFalse(self.destination.exists())
        self.assertEqual((self.entry / "manifest.json").read_bytes(), original_manifest)

    def test_atomic_install_failure_restores_previous_entry_and_keeps_generated_output(self) -> None:
        """Retain the previous complete entry if atomic replacement fails, with or without an old entry."""
        self.cache.save(self.key, self.source)
        old_contents = inventory(self.entry / "output")
        (self.source / "index.html").write_text("fresh usable docs")
        original_replace = Path.replace

        def fail_install(path: Path, destination: Path) -> Path:
            if path.name == "entry":
                raise PermissionError("cannot install entry")
            return original_replace(path, destination)

        with mock.patch("pathlib.Path.replace", new=fail_install), contextlib.redirect_stdout(io.StringIO()):
            self.cache.save(self.key, self.source)
            self.cache.save("b" * 64, self.source)
        self.assertEqual(inventory(self.entry / "output"), old_contents)
        self.assertEqual((self.source / "index.html").read_text(), "fresh usable docs")
        self.assertEqual([path.name for path in self.cache.directory.iterdir()], [self.key])


if __name__ == "__main__":
    unittest.main()
