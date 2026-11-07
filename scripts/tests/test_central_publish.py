"""Exercise frozen Central bundles and recoverable publication at external boundaries."""

import hashlib
import io
import json
import os
import unittest
import urllib.error
import zipfile
from email.message import Message
from unittest.mock import patch

from scripts import central_publish as central

from tests import test_javadoc_artifact
from tests.support import executable, invoke

DEPLOYMENT = "12345678-1234-1234-1234-123456789abc"


class CentralTests(unittest.TestCase):
    """Use real Git, archive bytes, and CLI stand-ins to exercise publication recovery."""

    def setUp(self) -> None:
        """Create a tagged-POM fixture and signed-artifact boundary substitutes."""
        self.fixture = test_javadoc_artifact.JavadocArtifactTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.root
        self.version = self.fixture.version
        self.pom = (
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            "<groupId>io.github.cs1302uga</groupId><artifactId>cs1302-tracer</artifactId>"
            f"<version>{self.version}</version><properties><release.central.schema>1</release.central.schema>"
            "</properties></project>"
        ).encode()
        (self.root / "pom.xml").write_bytes(self.pom)
        self.fixture.git("add", "pom.xml")
        self.fixture.git("commit", "-qm", "Central identity")
        self.commit = self.fixture.git("rev-parse", "HEAD")
        self.fixture.commit = self.commit
        self.fixture.archive()
        self.target = self.root / "target"
        self.target.mkdir()
        (self.target / "central-publishing").mkdir()
        self.remote = self.root / "remote"
        self.remote.mkdir()
        self.path = self.target / central.BUNDLE
        self.prefix = f"io/github/cs1302uga/cs1302-tracer/{self.version}/cs1302-tracer-{self.version}"
        library = io.BytesIO()
        with zipfile.ZipFile(library, "w") as jar:
            jar.writestr(
                "cs1302/tracer/version.properties",
                f"version={self.version}\ngit.commit={self.commit}\ngit.dirty=false\n",
            )
        self.files: dict[str, bytes] = {}
        for suffix, data in (
            (".pom", self.pom),
            (".jar", library.getvalue()),
            ("-sources.jar", b"source fixture"),
            ("-javadoc.jar", self.fixture.jar.read_bytes()),
        ):
            name = self.prefix + suffix
            self.files[name] = data
            self.files[name + ".asc"] = b"-----BEGIN PGP SIGNATURE-----\nexternal gpg fixture"
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                self.files[name + "." + algorithm] = hashlib.new(algorithm, data).hexdigest().encode()
        self.bundle()
        (self.target / "central-publishing" / central.BUNDLE).write_bytes(self.path.read_bytes())
        executable(self.root / "bin/gpg", "import sys; sys.exit(0)")
        executable(
            self.root / "bin/gh",
            """
import pathlib, shutil, sys
root = pathlib.Path.cwd()
a = sys.argv[1:]
remote = root / 'remote'
if a[0] == 'api':
    assert a[a.index('--hostname') + 1] == 'github.com'
    print('v1.2.3' if (root / 'created').exists() else '')
elif a[1] == 'create':
    assert a[a.index('--notes') + 1] == ''
    (root / 'created').touch()
elif a[1] == 'view':
    print('\\n'.join(p.name for p in remote.iterdir()))
elif a[1] == 'download':
    name = a[a.index('--pattern') + 1]
    shutil.copyfile(remote / name, pathlib.Path(a[a.index('--dir') + 1]) / name)
elif a[1] == 'upload':
    p = pathlib.Path(a[3])
    if (remote / p.name).exists() and '--clobber' not in a:
        sys.exit(1)
    shutil.copyfile(p, remote / p.name)
else:
    sys.exit(2)
""",
        )
        self.environment = dict(self.fixture.environment, MAVEN_CENTRAL_USERNAME="test", MAVEN_CENTRAL_TOKEN="test")
        environment = patch.dict(os.environ, self.environment)
        environment.start()
        self.addCleanup(environment.stop)

    def bundle(self) -> None:
        """Write the actual deployment ZIP and shared local files."""
        with zipfile.ZipFile(self.path, "w") as archive:
            archive.writestr("io/", b"")
            for name, data in self.files.items():
                archive.writestr(name, data)
                (self.target / name.rsplit("/", 1)[-1]).write_bytes(data)

    def response(self, request: object, **kwargs: object) -> io.BytesIO:
        """Serve exact public repository bytes or Portal responses."""
        if isinstance(request, str):
            return io.BytesIO(self.files[request.removeprefix(central.REPOSITORY)])
        url = getattr(request, "full_url")
        if "upload?" in url:
            self.assertIn(b'name="bundle"', getattr(request, "data"))
            self.assertEqual(getattr(request, "headers")["Authorization"], "Bearer dGVzdDp0ZXN0")
            return io.BytesIO(DEPLOYMENT.encode())
        return io.BytesIO(json.dumps({"deploymentId": DEPLOYMENT, "deploymentState": "PUBLISHED"}).encode())

    def missing(self) -> urllib.error.HTTPError:
        """Represent a Central propagation delay."""
        return urllib.error.HTTPError("https://example.invalid", 404, "missing", Message(), io.BytesIO())

    def test_required_and_inventory(self) -> None:
        """Require the historical marker and reject altered coordinates, inventory, and identity."""
        self.assertTrue(central.required(self.root, self.commit))
        self.assertEqual(central.contents(self.path, self.version, self.commit, self.pom), self.files)
        with self.assertRaisesRegex(ValueError, "tagged POM"):
            central.contents(self.path, self.version, self.commit, b"different")
        for original, replacement in ((b"io.github.cs1302uga", b"wrong"), (b"schema>1", b"schema>2")):
            changed = self.pom.replace(original, replacement)
            (self.root / "pom.xml").write_bytes(changed)
            self.fixture.git("add", "pom.xml")
            self.fixture.git("commit", "-qm", "changed")
            revision = self.fixture.git("rev-parse", "HEAD")
            if original == b"schema>1":
                with self.assertRaisesRegex(ValueError, "schema"):
                    central.required(self.root, revision)
            else:
                self.files[self.prefix + ".pom"] = changed
                self.bundle()
                with self.assertRaisesRegex(ValueError, "coordinates"):
                    central.contents(self.path, self.version, self.commit, changed)
        self.assertFalse(central.required(self.root, self.fixture.git("rev-list", "--max-parents=0", "HEAD")))

    def test_corrupt_bundle(self) -> None:
        """Fail before upload for invalid signature/checksum, oversized files, or library metadata."""
        original = self.files.copy()
        for suffix, value, message in ((".asc", b"bad", "signature"), (".sha256", b"bad", "checksum")):
            self.files = original.copy()
            self.files[self.prefix + ".pom" + suffix] = value
            self.bundle()
            with self.assertRaisesRegex(ValueError, message):
                central.contents(self.path, self.version, self.commit, self.pom)
        self.files = original.copy()
        self.bundle()
        with self.assertRaisesRegex(ValueError, "clean tagged"):
            central.contents(self.path, self.version, "a" * 40, self.pom)
        with patch.object(central, "MAX_SIZE", 1), self.assertRaisesRegex(ValueError, "size limit"):
            central.contents(self.path, self.version, self.commit, self.pom)
        self.files["unexpected"] = b"bad"
        self.bundle()
        with self.assertRaisesRegex(ValueError, "four signed"):
            central.contents(self.path, self.version, self.commit, self.pom)
        self.files = original.copy()
        self.bundle()
        # Compression makes the expanded size exceed the compressed archive size.
        self.files[self.prefix + "-sources.jar"] = b"source" * 100000
        with zipfile.ZipFile(self.path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in self.files.items():
                archive.writestr(name, data)
        with (
            patch.object(central, "MAX_SIZE", self.path.stat().st_size),
            self.assertRaisesRegex(ValueError, "expansion"),
        ):
            central.contents(self.path, self.version, self.commit, self.pom)

    def test_freeze_and_cli(self) -> None:
        """Freeze once and restore the retained bytes rather than a rebuilt candidate."""
        result = invoke(
            self.root,
            "central_publish.py",
            "freeze",
            "--version",
            self.version,
            "--commit",
            self.commit,
            "--repository",
            "example/test",
            env=self.environment,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        original = self.path.read_bytes()
        self.path.write_bytes(b"rebuilt")
        central.freeze(self.root, self.version, self.commit, "example/test")
        self.assertEqual(self.path.read_bytes(), original)

    def test_public_verification(self) -> None:
        """Compare Central and GitHub bytes and distinguish propagation from server errors."""
        with patch("urllib.request.urlopen", side_effect=self.response):
            self.assertTrue(central.verify(self.root, self.path, self.version, self.commit))
            (self.target / f"cs1302-tracer-{self.version}.jar").write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "shared"):
                central.verify(self.root, self.path, self.version, self.commit)
        self.bundle()
        with patch("urllib.request.urlopen", side_effect=self.missing()):
            self.assertFalse(central.verify(self.root, self.path, self.version, self.commit))
        with (
            patch("urllib.request.urlopen", return_value=io.BytesIO(b"changed")),
            self.assertRaisesRegex(ValueError, "bytes"),
        ):
            central.verify(self.root, self.path, self.version, self.commit)
        error = urllib.error.HTTPError("https://example.invalid", 503, "offline", Message(), io.BytesIO())
        with patch("urllib.request.urlopen", side_effect=error), self.assertRaises(urllib.error.HTTPError):
            central.verify(self.root, self.path, self.version, self.commit)

    def test_publish_and_resume(self) -> None:
        """Save intent before uploading, then recover the same deployment after interruption."""
        count = 0

        def response(request: object, **kwargs: object) -> io.BytesIO:
            nonlocal count
            count += 1
            if count == 1:
                raise self.missing()
            return self.response(request, **kwargs)

        with patch("urllib.request.urlopen", side_effect=response):
            central.publish(self.root, self.version, self.commit, "example/test")
        state = json.loads((self.remote / central.STATE).read_text())
        self.assertEqual(state["deployment"], DEPLOYMENT)
        count = 0
        with patch("urllib.request.urlopen", side_effect=response):
            central.publish(self.root, self.version, self.commit, "example/test")
        with patch("urllib.request.urlopen", side_effect=self.response):
            central.publish(self.root, self.version, self.commit, "example/test")
        for field, value, message in (("commit", "wrong", "record"), ("deployment", "", "unknown")):
            bad = dict(state, **{field: value})
            (self.remote / central.STATE).write_text(json.dumps(bad))
            with (
                patch("urllib.request.urlopen", side_effect=self.missing()),
                self.assertRaisesRegex(ValueError, message),
            ):
                central.publish(self.root, self.version, self.commit, "example/test")

    def test_status_failure_and_timeout(self) -> None:
        """Reject wrong deployment identities and terminal errors; bound pending-state polling."""
        state = {
            "commit": self.commit,
            "version": self.version,
            "sha256": hashlib.sha256(self.path.read_bytes()).hexdigest(),
            "deployment": DEPLOYMENT,
        }
        (self.remote / central.STATE).write_text(json.dumps(state))
        for deployment, phase, message in (
            ("wrong", "PUBLISHED", "different"),
            (DEPLOYMENT, "FAILED", "inspection"),
            (DEPLOYMENT, "PENDING", "pending"),
            (DEPLOYMENT, "VALIDATED", "pending"),
        ):

            def response(request: object, **kwargs: object) -> io.BytesIO:
                if isinstance(request, str):
                    raise self.missing()
                return io.BytesIO(json.dumps({"deploymentId": deployment, "deploymentState": phase}).encode())

            with (
                patch("urllib.request.urlopen", side_effect=response),
                patch("time.sleep"),
                self.assertRaisesRegex((ValueError, TimeoutError), message),
            ):
                central.publish(self.root, self.version, self.commit, "example/test")
