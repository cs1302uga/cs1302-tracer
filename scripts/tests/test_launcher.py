"""Exercise the Bash launcher with real subprocesses and controlled Java executables."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from tests.support import SCRIPTS


class LauncherTests(unittest.TestCase):
    """Verify relocation, argument boundaries, Java selection, and failure behavior."""

    def setUp(self) -> None:
        """Create an installation and caller directory containing spaces."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.install = self.root / "installation with spaces"
        self.install.mkdir()
        self.launcher = self.install / "code-tracer.sh"
        shutil.copy2(SCRIPTS.parent / "src/main/scripts/code-tracer.sh", self.launcher)
        self.launcher.chmod(0o755)
        (self.install / "code-tracer").symlink_to("code-tracer.sh")
        (self.install / "code-tracer.jar").touch()
        self.cwd = self.root / "caller directory"
        self.cwd.mkdir()
        self.jdk = self.root / "selected JDK"
        (self.jdk / "bin").mkdir(parents=True)
        self.java = self.jdk / "bin/java"
        self.java.write_text('#!/bin/bash\nprintf "%s\\0" "$$" "$PWD" "$@"\nprintf "java stderr" >&2\nexit 23\n')
        self.java.chmod(0o755)
        self.javac = self.jdk / "bin/javac"
        self.javac.write_text("#!/bin/bash\nexit 0\n")
        self.javac.chmod(0o755)
        self.env = {**os.environ, "JAVA_HOME": str(self.jdk)}
        self.env.pop("CODE_TRACER_JAR_DIR", None)

    def test_arguments_cwd_and_exec(self) -> None:
        """Preserve empty and special arguments, working directory, streams, and process identity."""
        args = ["trace", "", "file with spaces.java", "*.java", "$(false)", "a'b", 'a"b', "line\nbreak"]
        with subprocess.Popen(
            [str(self.launcher), *args], cwd=self.cwd, env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE
        ) as process:
            stdout, stderr = process.communicate(timeout=10)
            self.assertEqual(process.returncode, 23)
            fields = stdout.decode().split("\0")
            self.assertEqual(
                fields, [str(process.pid), str(self.cwd), "-jar", str(self.install / "code-tracer.jar"), *args, ""]
            )
            self.assertEqual(stderr, b"java stderr")

    def test_path_and_symlinks(self) -> None:
        """Find Java on PATH and follow relative and absolute launcher links from PATH."""
        links = self.root / "links"
        links.mkdir()
        (links / "relative").symlink_to("../installation with spaces/code-tracer")
        (links / "code-tracer").symlink_to(links / "relative")
        env = {**self.env, "PATH": f"{links}:{self.jdk / 'bin'}:{os.environ['PATH']}"}
        env.pop("JAVA_HOME")
        result = subprocess.run(["code-tracer", "--help"], cwd=self.cwd, env=env, capture_output=True)
        self.assertEqual(result.returncode, 23)
        self.assertIn(str(self.install / "code-tracer.jar").encode(), result.stdout)
        self.assertIn(str(self.cwd).encode(), result.stdout)

    def test_relative_launcher_ignores_cdpath(self) -> None:
        """Resolve direct and symlinked relative launchers independently of shell CDPATH."""
        alternate = self.root / "alternate"
        (alternate / self.install.name).mkdir(parents=True)
        for name in ("code-tracer.sh", "code-tracer"):
            with self.subTest(name=name):
                result = subprocess.run(
                    [str(Path(self.install.name) / name)],
                    cwd=self.root,
                    env={**self.env, "CDPATH": str(alternate)},
                    capture_output=True,
                    timeout=10,
                )
                self.assertEqual(result.returncode, 23, result.stderr)
                fields = result.stdout.decode().split("\0")
                self.assertEqual(fields[1], str(self.root))
                self.assertEqual(fields[3], str(self.install / "code-tracer.jar"))

    def test_java_home_precedence_and_invalid_home(self) -> None:
        """Honor JAVA_HOME even with a different Java on PATH, and reject an invalid explicit home."""
        tools = self.root / "tools"
        tools.mkdir()
        fallback = tools / "java"
        fallback.write_text("#!/bin/bash\nexit 99\n")
        fallback.chmod(0o755)
        env = {**self.env, "PATH": f"{tools}:{os.environ['PATH']}"}
        result = subprocess.run([str(self.launcher)], env=env, capture_output=True)
        self.assertEqual(result.returncode, 23)
        env["JAVA_HOME"] = str(self.root / "missing JDK")
        result = subprocess.run([str(self.launcher)], env=env, capture_output=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn(b"JAVA_HOME", result.stderr)
        self.assertEqual(result.stdout, b"")

    def test_missing_jar_and_java(self) -> None:
        """Give actionable stderr diagnostics for absent JAR and Java prerequisites."""
        jar = self.install / "code-tracer.jar"
        jar.unlink()
        result = subprocess.run([str(self.launcher)], env=self.env, capture_output=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn(b"keep the launcher beside code-tracer.jar", result.stderr)
        jar.touch()
        tools = self.root / "utilities"
        tools.mkdir()
        dirname = shutil.which("dirname")
        assert dirname is not None
        (tools / "dirname").symlink_to(dirname)
        env = {**self.env, "JAVA_HOME": "", "PATH": str(tools)}
        result = subprocess.run([str(self.launcher)], env=env, capture_output=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn(b"Java was not found", result.stderr)
        self.assertEqual(result.stdout, b"")

    def test_jar_directory_override(self) -> None:
        """Select literal absolute and caller-relative directories without the adjacent JAR."""
        directory = self.cwd / "jar files"
        directory.mkdir()
        (self.install / "code-tracer.jar").rename(directory / "code-tracer.jar")
        for literal in ("~", "$HOME"):
            (self.cwd / literal).mkdir()
            (self.cwd / literal / "code-tracer.jar").touch()
        for value in (str(directory), str(directory) + "/", "jar files", "jar files/", "~", "$HOME"):
            with self.subTest(value=value):
                result = subprocess.run(
                    [str(self.install / "code-tracer"), "a b", ""],
                    cwd=self.cwd,
                    env={**self.env, "CODE_TRACER_JAR_DIR": value},
                    capture_output=True,
                    timeout=10,
                )
                self.assertEqual(result.returncode, 23, result.stderr)
                fields = result.stdout.decode().split("\0")
                self.assertEqual(fields[1], str(self.cwd))
                self.assertEqual((self.cwd / fields[3]).resolve(), (self.cwd / value / "code-tracer.jar").resolve())
                self.assertEqual(fields[4:], ["a b", "", ""])

    def test_empty_and_invalid_jar_directories(self) -> None:
        """Empty values default; invalid values never fall back to a valid adjacent JAR."""
        directory = self.cwd / "not a jar"
        directory.mkdir()
        (directory / "code-tracer.jar").mkdir()
        for value in ("", "missing", "~", "$HOME", "not a jar"):
            with self.subTest(value=value):
                result = subprocess.run(
                    [str(self.launcher)],
                    cwd=self.cwd,
                    env={**self.env, "CODE_TRACER_JAR_DIR": value},
                    capture_output=True,
                    timeout=10,
                )
                self.assertEqual(result.returncode, 23 if value == "" else 1)
                if value:
                    self.assertEqual(result.stdout, b"")
                    self.assertIn(b"CODE_TRACER_JAR_DIR", result.stderr)
                    self.assertIn(value.encode(), result.stderr)

    def test_missing_and_nonexecutable_compiler(self) -> None:
        """Reject a missing or nonexecutable compiler even when Java itself is executable."""
        self.javac.chmod(0o644)
        for use_home in (True, False):
            env = {**self.env, "PATH": f"{self.jdk / 'bin'}:/usr/bin:/bin"}
            if not use_home:
                env.pop("JAVA_HOME")
                # macOS has a system javac shim, so use a utilities-only PATH.
                utilities = self.root / "compiler-check-tools"
                utilities.mkdir(exist_ok=True)
                for tool in ("dirname", "readlink"):
                    location = shutil.which(tool)
                    assert location is not None
                    (utilities / tool).symlink_to(location)
                (utilities / "java").symlink_to(self.java)
                env["PATH"] = str(utilities)
            result = subprocess.run([str(self.launcher), "--help"], env=env, capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 1)
            self.assertEqual(result.stdout, b"")
            self.assertIn(b"javac", result.stderr)
        self.javac.unlink()
        result = subprocess.run([str(self.launcher), "--version"], env=self.env, capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 1)
        self.assertIn(b"javac", result.stderr)
