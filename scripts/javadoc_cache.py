"""Cache complete historical Javadoc trees under hashed build inputs."""

import argparse
import hashlib
import json
import os
import platform
import re
import shlex
import shutil
import stat
import subprocess
import tempfile
import urllib.parse
import xml.etree.ElementTree as ET
from datetime import UTC, datetime
from pathlib import Path

REQUIRED = ("index.html", "search.html", "element-list")
ENVIRONMENT_REFERENCE = re.compile(rb"\$\{env\.([A-Za-z_][A-Za-z_0-9]*)\}")
ENVIRONMENT = (
    "JAVA_HOME",
    "JDK_HOME",
    "MAVEN_HOME",
    "M2_HOME",
    "MAVEN_USER_HOME",
    "MAVEN_ARGS",
    "MAVEN_OPTS",
    "JAVA_TOOL_OPTIONS",
    "JDK_JAVA_OPTIONS",
    "_JAVA_OPTIONS",
    "LANG",
    "LC_ALL",
    "LC_CTYPE",
    "TZ",
    "HTTP_PROXY",
    "HTTPS_PROXY",
    "ALL_PROXY",
    "NO_PROXY",
    "http_proxy",
    "https_proxy",
    "all_proxy",
    "no_proxy",
)


def settings_input(content: bytes) -> bytes:
    """Exclude rotating authentication fields while retaining artifact resolution configuration."""
    element = ET.fromstring(content)
    for owner in (*element.findall(".//{*}server"), *element.findall(".//{*}proxy")):
        for child in list(owner):
            if child.tag.rsplit("}", 1)[-1] in ("username", "password", "privateKey", "passphrase"):
                owner.remove(child)
    return ET.canonicalize(ET.tostring(element, encoding="unicode"), strip_text=True).encode()


def fingerprint(root: Path) -> str:
    """Hash the actual tool identity and relevant configuration without retaining secret values."""
    probe = subprocess.run(
        [str(root / "mvnw"), "--version", "-Dstyle.color=never"],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
        timeout=60,
    )
    identity = re.sub(r"\x1b\[[0-9;]*m", "", probe.stdout + probe.stderr)
    maven_home = re.search(r"^Maven home: (.+)$", identity, flags=re.MULTILINE)
    if not maven_home or "Apache Maven " not in identity or "Java version: " not in identity:
        raise ValueError("Incomplete Maven/JDK identity")
    inputs = {
        "identity": identity,
        "platform": platform.platform(),
        "year": str(datetime.now(UTC).year),
    }
    files = {
        root / "mvnw",
        root / ".mvn/wrapper/maven-wrapper.properties",
        root / ".mvn/wrapper/maven-wrapper.jar",
        root / ".mvn/maven.config",
        root / ".mvn/jvm.config",
        root / ".mvn/extensions.xml",
        root / "scripts/build_javadoc.py",
        root / "scripts/javadoc_cache.py",
    }
    for tool in ("java", "javac", "javadoc"):
        executable = shutil.which(tool)
        inputs["executable:" + tool] = str(Path(executable).resolve()) if executable else "missing"
        if executable:
            files.add(Path(executable).resolve())
    homes = {Path.home() / ".m2", Path(maven_home[1].strip()) / "conf"}
    if os.environ.get("MAVEN_USER_HOME"):
        homes.add(Path(os.environ["MAVEN_USER_HOME"]))
    argument_variables = ("MAVEN_ARGS", "MAVEN_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")
    arguments = " ".join(os.environ.get(name, "") for name in argument_variables)
    for name in ("maven.config", "jvm.config"):
        path = root / ".mvn" / name
        if path.is_file():
            arguments += " " + path.read_text()
    tokens = shlex.split(arguments, comments=True)
    parser = argparse.ArgumentParser(add_help=False, allow_abbrev=False, exit_on_error=False)
    for short, long in (
        ("-s", "--settings"),
        ("-gs", "--global-settings"),
        ("-t", "--toolchains"),
        ("-gt", "--global-toolchains"),
    ):
        parser.add_argument(short, long)
    options, _ = parser.parse_known_args(tokens)
    files.update(root / value for value in vars(options).values() if value)
    settings = {root / value for value in (options.settings, options.global_settings) if value}
    for token in tokens:
        if token.startswith("-Duser.home="):
            homes.add(Path(token.split("=", 1)[1]) / ".m2")
        if token.startswith("-Dmaven.user.home="):
            homes.add(Path(token.split("=", 1)[1]))
    files.update(home / name for home in homes for name in ("settings.xml", "toolchains.xml"))
    settings.update(home / "settings.xml" for home in homes)
    names = set(ENVIRONMENT)
    for path in sorted(files):
        try:
            content = path.read_bytes()
        except FileNotFoundError:
            inputs[str(path)] = "missing"
        else:
            if path in settings:
                content = settings_input(content)
            inputs[str(path)] = hashlib.sha256(content).hexdigest()
            names.update(match.decode() for match in ENVIRONMENT_REFERENCE.findall(content))
    for name in names:
        value = os.environ.get(name, "")
        if name.lower() in ("http_proxy", "https_proxy", "all_proxy"):
            url = urllib.parse.urlsplit(value)
            value = url._replace(netloc=url.netloc.rsplit("@", 1)[-1]).geturl()
        inputs["env:" + name] = value
    return hashlib.sha256(json.dumps(inputs, sort_keys=True).encode()).hexdigest()


def inventory(root: Path) -> dict[str, str | None]:
    """Describe every regular file and directory, rejecting incomplete or special output."""
    if not stat.S_ISDIR(root.lstat().st_mode):
        raise ValueError("Javadoc output must be a regular directory")
    result: dict[str, str | None] = {}
    for path in sorted(root.rglob("*")):
        mode = path.lstat().st_mode
        if stat.S_ISREG(mode):
            with path.open("rb") as stream:
                value = hashlib.file_digest(stream, "sha256").hexdigest()
        elif stat.S_ISDIR(mode):
            value = None
        else:
            raise ValueError("Javadoc output contains a symlink or special file")
        result[path.relative_to(root).as_posix()] = value
    if any(result.get(name) is None for name in REQUIRED):
        raise ValueError("Javadoc output is incomplete")
    return result


class JavadocCache:
    """Read only verified complete entries and replace them after successful generation."""

    def __init__(self, root: Path, mode: str) -> None:
        """Choose cache behavior without probing tools or reading cache entries yet."""
        if mode not in ("use", "refresh", "off"):
            raise ValueError("Unknown Javadoc cache mode")
        self.root = root
        self.mode = mode
        self.directory = root / "website/.cache/javadoc"
        self.context: str | None = None
        self.probed = False

    def key(self, commit: str) -> str | None:
        """Include the entire archived source/POM revision in the current build context."""
        if self.mode == "off":
            return None
        if not self.probed:
            self.probed = True
            try:
                self.context = fingerprint(self.root)
            except OSError, ValueError, ET.ParseError, argparse.ArgumentError, subprocess.SubprocessError:
                print("Javadoc cache unavailable: build inputs could not be verified; rebuilding.")
        if self.context is None:
            return None
        try:
            pom = subprocess.check_output(["git", "show", commit + ":pom.xml"], cwd=self.root, stderr=subprocess.PIPE)
        except OSError, subprocess.SubprocessError:
            print("Javadoc cache unavailable: archived build inputs could not be verified; rebuilding.")
            return None
        environment = {name.decode(): os.environ.get(name.decode(), "") for name in ENVIRONMENT_REFERENCE.findall(pom)}
        return hashlib.sha256(json.dumps([self.context, commit, environment], sort_keys=True).encode()).hexdigest()

    def directory_ready(self, *, create: bool) -> None:
        """Reject redirected cache paths before reading or creating an entry."""
        for path in (self.directory.parent.parent, self.directory.parent, self.directory):
            if create:
                path.mkdir(exist_ok=True)
            if not stat.S_ISDIR(path.lstat().st_mode):
                raise ValueError("Cache paths must be regular directories")

    def restore(self, key: str | None, destination: Path) -> bool:
        """Restore a complete matching entry; corrupt, unavailable, or partial data is a miss."""
        if self.mode != "use" or key is None:
            return False
        try:
            self.directory_ready(create=False)
            entry = self.directory / key
            if not stat.S_ISDIR(entry.lstat().st_mode):
                raise ValueError("Cache entry must be a regular directory")
            manifest = entry / "manifest.json"
            details = manifest.lstat()
            if not stat.S_ISREG(details.st_mode) or details.st_size > 16 * 1024 * 1024:
                raise ValueError("Cache manifest must be a regular file")
            expected = {"schemaVersion": 1, "inputKey": key, "inventory": inventory(entry / "output")}
            if json.loads(manifest.read_text()) != expected:
                return False
            destination.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.TemporaryDirectory(prefix=".javadoc-restore-", dir=destination.parent) as temporary:
                copied = Path(temporary) / "output"
                shutil.copytree(entry / "output", copied, symlinks=True)
                if inventory(copied) != expected["inventory"]:
                    return False
                copied.replace(destination)
            return True
        except OSError, ValueError, RecursionError:
            return False

    def save(self, key: str | None, source: Path) -> None:
        """Publish a complete entry atomically; cache write failures never discard fresh docs."""
        if self.mode == "off" or key is None:
            return
        try:
            self.directory_ready(create=True)
            contents = inventory(source)
            with tempfile.TemporaryDirectory(prefix=".javadoc-write-", dir=self.directory) as temporary:
                staging = Path(temporary)
                entry = staging / "entry"
                entry.mkdir()
                shutil.copytree(source, entry / "output", symlinks=True)
                if inventory(entry / "output") != contents:
                    raise ValueError("Javadoc changed while caching")
                (entry / "manifest.json").write_text(
                    json.dumps(
                        {
                            "schemaVersion": 1,
                            "inputKey": key,
                            "inventory": contents,
                        },
                        sort_keys=True,
                    )
                    + "\n"
                )
                destination = self.directory / key
                backup = staging / "previous"
                if destination.exists() or destination.is_symlink():
                    destination.replace(backup)
                try:
                    entry.replace(destination)
                except OSError:
                    if backup.exists():
                        backup.replace(destination)
                    raise
        except OSError, ValueError:
            print("Javadoc cache write failed; freshly generated documentation remains available.")
