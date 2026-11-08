"""Exercise the documentation CLI with real projects and external tool stand-ins."""

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

from tests.support import FAKE_SITE

SCRIPTS = Path(__file__).resolve().parents[1]


class DocumentationSiteTests(unittest.TestCase):
    """Verify documentation behavior."""

    def setUp(self) -> None:
        """Create an isolated documentation project."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.site = self.root / "src/site"
        self.site.mkdir(parents=True)
        scripts = self.root / "scripts"
        scripts.mkdir()
        for name in (
            "docs_site.py",
            "docs_release.py",
            "build_javadoc.py",
            "javadoc_cache.py",
            "javadoc_artifact.py",
            "docs_timing.py",
            "maven_site.py",
        ):
            shutil.copy2(SCRIPTS / name, scripts / name)
        (self.root / "pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            "<version>1.0.0</version><properties><maven-site-plugin.version>3.22.0</maven-site-plugin.version>"
            "</properties></project>"
        )
        (self.site / "resources").mkdir()
        (self.site / "resources/dependencies.js").write_text("")
        (self.site / "resources/dependencies.css").write_text("")
        (self.site / "landing").mkdir()
        (self.site / "landing/index.md").write_text("# Project home")
        (self.site / "site.xml").write_text(
            '<site xmlns="http://maven.apache.org/SITE/2.1.0"><skin>'
            "<groupId>example</groupId><artifactId>test-skin</artifactId>"
            "<version>9.0.0-SNAPSHOT</version></skin><body/></site>"
        )
        (self.site / "navigation.json").write_text("{}")
        (self.root / "source.txt").write_text("released source")
        (self.site / "versions.json").write_text('["1.0.0"]')
        snapshot = self.site / "../../docs/releases/1.0.0"
        snapshot.mkdir(parents=True)
        (snapshot / "index.md").write_text("# Release")
        (self.root / "docs/index.md").write_text("# Release")
        sidebars = self.site / "releases"
        sidebars.mkdir()
        (sidebars / "1.0.0-navigation.json").write_text("{}")
        self.git("init", "-q")
        self.git("add", ".")
        self.git("commit", "-qm", "Release")
        self.git("tag", "v1.0.0")
        (self.root / "source.txt").write_text("working source")
        self.log = self.root / "tools.jsonl"
        self.environment = dict(os.environ, DOCS_TEST_LOG=str(self.log), DOCS_TEST_RENDERER=FAKE_SITE)
        self.executable(
            self.root / "mvnw",
            """
import json, os
from pathlib import Path
import sys
if 'license:add-third-party@dependency-docs' in sys.argv:
    with open(os.environ['DOCS_TEST_LOG'], 'a') as log:
        log.write(json.dumps(['dependency-inventory', *sys.argv[1:]]) + '\\n')
    artifacts = [
        {'groupId': 'example', 'artifactId': name, 'version': '1.2.3',
         'type': 'jar', 'classifier': '', 'scope': scope}
        for name, scope in (('library', 'compile'), ('assertions', 'test'))
    ]
    graph = {'groupId': 'cs1302uga', 'artifactId': 'cs1302-tracer',
             'version': '1.0.0', 'type': 'jar', 'scope': '', 'children': artifacts}
    metadata = {'dependencies': [
        {key: artifact[key] for key in ('groupId', 'artifactId', 'version')}
        | {'name': artifact['artifactId'], 'description': 'Fixture dependency.',
           'url': 'https://example.invalid/' + artifact['artifactId'],
           'licenses': [{'name': 'MIT', 'url': 'https://opensource.org/license/mit'}]}
        for artifact in artifacts
    ]}
    tree = Path(next(arg.split('=', 1)[1] for arg in sys.argv if arg.startswith('-DoutputFile=')))
    output = Path(next(arg.split('=', 1)[1] for arg in sys.argv
                       if arg.startswith('-Ddependency.docs.outputDirectory=')))
    tree.write_text(json.dumps(graph))
    (output / 'metadata.json').write_text(json.dumps(metadata))
    sys.exit(0)
if '--version' in sys.argv:
    with open(os.environ['DOCS_TEST_LOG'], 'a') as log:
        log.write(json.dumps(['maven-version']) + '\\n')
    print('Apache Maven test\\nMaven home: ' + str(Path.cwd() / 'maven') + '\\nJava version: 25')
    sys.exit(0)
if 'site:site' in sys.argv:
    exec(os.environ["DOCS_TEST_RENDERER"])
assert "-P!docsite" in sys.argv
source = Path.cwd()
with open(os.environ['DOCS_TEST_LOG'], 'a') as log:
    log.write(json.dumps(['maven', source.joinpath('source.txt').read_text()]) + '\\n')
if os.environ.get('DOCS_TEST_FAIL_RELEASE') and source.joinpath('source.txt').read_text() == 'released source':
    sys.exit(1)
output = source / 'target/reports/apidocs'
output.mkdir(parents=True, exist_ok=True)
output.joinpath('index.html').write_text(source.joinpath('source.txt').read_text())
output.joinpath('search.html').write_text('Search')
output.joinpath('element-list').write_text('cs1302.tracer')
""",
        )
        binaries = self.root / "bin"
        binaries.mkdir()
        self.environment["PATH"] = str(binaries) + os.pathsep + os.environ["PATH"]
        self.executable(
            binaries / "gh",
            """
import json, os, sys
with open(os.environ['DOCS_TEST_LOG'], 'a') as log:
    log.write(json.dumps(['gh', *sys.argv[1:]]) + '\\n')
sys.exit(1 if os.environ.get('DOCS_TEST_FAIL_ATTESTATION') else 0)
""",
        )

    def executable(self, path: Path, body: str) -> None:
        """Write an executable external-tool stand-in."""
        path.write_text("#!" + sys.executable + "\n" + body)
        path.chmod(0o755)

    def git(self, *args: str) -> str:
        """Run Git against the isolated project."""
        return subprocess.check_output(
            [
                "git",
                "-c",
                "user.name=Docs Test",
                "-c",
                "user.email=docs@example.invalid",
                "-c",
                "commit.gpgsign=false",
                "-c",
                "tag.gpgSign=false",
                "-c",
                "core.hooksPath=/dev/null",
                *args,
            ],
            cwd=self.root,
            text=True,
        ).strip()

    def run_site(self, *args: str) -> subprocess.CompletedProcess[str]:
        """Invoke the documentation CLI."""
        return subprocess.run(
            [
                sys.executable,
                "-c",
                """
import json, os, runpy, sys, urllib.parse, urllib.request
from pathlib import Path
if os.environ.get('DOCS_TEST_ASSETS'):
    def download(request, timeout):
        url = request.full_url if isinstance(request, urllib.request.Request) else request
        with open(os.environ['DOCS_TEST_LOG'], 'a') as log:
            log.write(json.dumps(['download', url]) + '\\n')
        return (Path(os.environ['DOCS_TEST_ASSETS']) / urllib.parse.urlparse(url).path.rsplit('/', 1)[-1]).open('rb')
    urllib.request.urlopen = download
sys.argv.pop(0)
sys.path.insert(0, str(Path(sys.argv[0]).parent))
runpy.run_path(sys.argv[0], run_name='__main__')
""",
                str(self.root / "scripts/docs_site.py"),
                *args,
            ],
            cwd=self.root,
            env=self.environment,
            text=True,
            capture_output=True,
        )

    def prepare_dependency_inventory(self) -> None:
        """Generate the verification prerequisite with the real CLI and fake Maven boundary."""
        shutil.copy2(SCRIPTS / "dependency_docs.py", self.root / "scripts/dependency_docs.py")
        shutil.copy2(SCRIPTS.parent / "LICENSE", self.root / "LICENSE")
        (self.root / "scripts/dependency-license-overrides.json").write_text("{}\n")
        result = subprocess.run(
            [sys.executable, str(self.root / "scripts/dependency_docs.py"), "update"],
            cwd=self.root,
            env=self.environment,
            text=True,
            capture_output=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.log.write_text("")

    def release_assets(self, *, commit: str | None = None) -> Path:
        """Advertise real Javadoc files behind the external download boundary."""
        directory = self.root / "release-assets"
        directory.mkdir(exist_ok=True)
        artifact = directory / "cs1302-tracer-1.0.0-javadoc.jar"
        manifest = {
            "Manifest-Version": "1.0",
            "Code-Tracer-Javadoc-Schema": "1",
            "Code-Tracer-Version": "1.0.0",
            "Code-Tracer-Commit": commit or self.git("rev-parse", "v1.0.0^{commit}"),
            "Code-Tracer-Dirty": "false",
            "Code-Tracer-Javadoc-Plugin": "3.12.0",
            "Code-Tracer-Build-Jdk": "21.0.10",
            "Code-Tracer-Build-Jdk-Vendor": "Eclipse Adoptium",
            "Code-Tracer-Build-Maven": "3.9.16",
        }
        with zipfile.ZipFile(artifact, "w") as archive:
            archive.writestr(
                "META-INF/MANIFEST.MF", "".join(f"{key}: {value}\r\n" for key, value in manifest.items()) + "\r\n"
            )
            archive.writestr("index.html", "published release API")
            archive.writestr("search.html", "Published search")
            archive.writestr("element-list", "cs1302.tracer")
            archive.writestr("script-files/search.js", "Published search script")
        (directory / (artifact.name + ".sha256")).write_text(
            hashlib.sha256(artifact.read_bytes()).hexdigest() + "  " + artifact.name + "\n"
        )
        (directory / (artifact.name + ".sigstore.json")).write_text('{"verification":"external gh boundary"}')
        self.environment["DOCS_TEST_ASSETS"] = str(directory)
        return self.advertise_assets(directory)

    def advertise_assets(self, directory: Path) -> Path:
        """Write release records containing the current files' actual size and digest."""
        records = self.root / "releases.json"
        records.write_text(
            json.dumps(
                [
                    {
                        "tag_name": "v1.0.0",
                        "draft": False,
                        "prerelease": False,
                        "assets": [
                            {
                                "name": asset.name,
                                "browser_download_url": "https://github.com/cs1302uga/cs1302-tracer/releases/download/v1.0.0/"
                                + asset.name,
                                "size": asset.stat().st_size,
                                "digest": "sha256:" + hashlib.sha256(asset.read_bytes()).hexdigest(),
                            }
                            for asset in sorted(directory.iterdir())
                        ],
                    }
                ]
            )
        )
        return records

    def test_rejects_output_relocation_before_generating_or_changing_a_build(self) -> None:
        """Rejects output relocation before generating or changing a build."""
        completed = self.root / "target/pages"
        completed.mkdir(parents=True)
        manifest = completed / "release-manifest.json"
        manifest.write_text('{"previous": "published"}')
        for options in (("--out-dir", "custom"), ("--out-dir=custom",), ("other-site",)):
            with self.subTest(options=options):
                result = self.run_site("build", *options)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("unrecognized arguments", result.stderr)
                self.assertFalse(self.log.exists(), "Invalid options must fail before invoking tools")
                self.assertEqual(manifest.read_text(), '{"previous": "published"}')
                self.assertFalse((self.root / "target/docsite/release-manifest.json").exists())

    def test_development_server_accepts_host_and_port_options(self) -> None:
        """Build the full site then forward bind options to the static server."""
        (self.root / "scripts/serve_docs.py").write_text(
            "import sys; from pathlib import Path; Path('server-args').write_text(' '.join(sys.argv[1:]))"
        )
        result = self.run_site("start", "--host", "127.0.0.1", "--port=3100")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "server-args").read_text(), "--host 127.0.0.1 --port 3100")

    def test_retired_guide_mode_is_rejected(self) -> None:
        """The complete renderer has no active/archive composition mode."""
        result = self.run_site("build", "--guides", "active")
        self.assertEqual(result.returncode, 2)
        self.assertIn("unrecognized arguments", result.stderr)
        self.assertFalse(self.log.exists())

    def test_deployment_freshness_rechecks_source_builder_selection_and_asset_records(self) -> None:
        """Reject a stale complete artifact even when its own rendered links remain valid."""
        records = self.root / "releases.json"
        records.write_text('[{"tag_name":"v1.0.0"}]')
        options = ("--mode", "published", "--releases", str(records))
        result = self.run_site("build", *options)
        self.assertEqual(result.returncode, 0, result.stderr)
        result = self.run_site("check-inputs", *options)
        self.assertEqual(result.returncode, 0, result.stderr)
        path = self.root / "target/pages/release-manifest.json"
        original = path.read_text()
        for field, value in (("latestVersion", None), ("builderCommit", "b" * 40)):
            manifest = json.loads(original)
            manifest[field] = value
            path.write_text(json.dumps(manifest))
            result = self.run_site("check-inputs", *options)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Stale deployment", result.stderr)
        path.write_text(original)
        records.write_text('[{"tag_name":"v1.0.0","assets":[]}]')
        result = self.run_site("check-inputs", *options)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("asset records", result.stderr)
        records.write_text('[{"tag_name":"v1.0.0"}]')
        (self.root / "docs/index.md").write_text("# Changed current prose")
        result = self.run_site("check-inputs", *options)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("source inputs", result.stderr)

    def test_renderer_stages_authoritative_content_resources_and_legacy_asset_integrity(self) -> None:
        """Keep frozen sources untouched while transforming only disposable rendering inputs."""
        (self.root / "README.md").write_text("# Repository")
        document = self.root / "docs/index.md"
        document.write_text(
            "---\nslug: /\n---\n# Current\n"
            "[Source](../README.md) [Missing](typo.md) [Local](index.md) "
            "[Web](https://example.test/) [Protocol](//example.test/) [Fragment](#current) [Root](/api/next/)\n"
        )
        (self.root / "docs/plain.md").write_text("Plain content without a heading")
        (self.root / "docs/releases/1.0.0/index.md").write_text("# Release\n[Repo](../README.md#repository)")
        asset = self.root / "docs/evidence.json"
        asset.write_text('{"evidence":true}')
        legacy = self.site / "legacy-assets.json"
        legacy.write_text(
            json.dumps(
                {
                    "assets/files/evidence.json": {
                        "source": "docs/evidence.json",
                        "sha256": hashlib.sha256(asset.read_bytes()).hexdigest(),
                    }
                }
            )
        )
        result = self.run_site("build")
        self.assertEqual(result.returncode, 0, result.stderr)
        staged = self.root / "target/docsite/renderer/src/site/markdown/docs/dev/index.md"
        rendered_source = staged.read_text()
        self.assertNotIn("slug:", rendered_source)
        self.assertIn("https://github.com/cs1302uga/cs1302-tracer/blob/main/README.md", rendered_source)
        self.assertIn("[Missing](typo.md)", rendered_source)
        self.assertIn("[Local](index.md)", rendered_source)
        output = self.root / "target/pages"
        self.assertEqual((output / "docs/dev/evidence.json").read_bytes(), asset.read_bytes())
        self.assertEqual((output / "assets/files/evidence.json").read_bytes(), asset.read_bytes())
        self.assertTrue((output / "docs/latest/index.html").is_file())
        self.assertTrue((output / "1.0.0/404.html").is_file())
        self.assertIn("slug:", document.read_text())
        asset.write_text("changed")
        result = self.run_site("build")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Legacy asset changed", result.stderr)

    def test_in_flight_source_changes_fail_generation(self) -> None:
        """Do not bind a completed artifact to inputs read only after source mutation."""
        self.environment["DOCS_TEST_MUTATE_SOURCE"] = "1"
        result = self.run_site("build")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("source inputs changed during generation", result.stderr)

    def test_search_sidebar_candidates_load_existing_cross_page_content_and_fragments(self) -> None:
        """Give the shipped skin real .html candidates for guide-page and section fetching."""
        (self.site / "navigation.json").write_text('{"docs":["index","extra"]}')
        (self.root / "docs/extra.md").write_text("# Extra page")
        result = self.run_site("build")
        self.assertEqual(result.returncode, 0, result.stderr)
        output = self.root / "target/pages"
        page = (output / "docs/dev/index.html").read_text()
        sidebar = re.search(r'<nav class="supa-sidebar-tree"[^>]*>(.*?)</nav>', page, flags=re.S)
        self.assertIsNotNone(sidebar)
        assert sidebar is not None
        candidates = re.findall(r'<a class="supa-nav-link[^"]*" href="([^"]+)"', sidebar[1])
        self.assertEqual(len(candidates), 2)
        for candidate in candidates:
            self.assertTrue(candidate.endswith(".html"))
            path = output / candidate.removeprefix("/cs1302-tracer/")
            self.assertTrue(path.is_file())
        target = (output / "docs/dev/extra/index.html").read_text()
        self.assertIn("<h1>Extra page</h1>", target)
        self.assertIn('id="extra-page"', target)
        self.assertIn('href="https://cs1302uga.github.io/cs1302-tracer/docs/dev/extra/"', target)

    def test_build_keeps_pinned_release_api_and_its_manifest_together(self) -> None:
        """Build keeps pinned release api and its manifest together."""
        result = self.run_site("build")
        self.assertEqual(result.returncode, 0, result.stderr)
        completed = self.root / "target/pages"
        self.assertEqual((completed / "api/next/index.html").read_text(), "working source")
        self.assertEqual((completed / "api/1.0.0/index.html").read_text(), "released source")
        manifest = json.loads((completed / "release-manifest.json").read_text())
        self.assertEqual(manifest["versions"][1]["sourceCommit"], self.git("rev-parse", "v1.0.0^{commit}"))
        self.assertEqual(manifest["latestVersion"], "1.0.0")
        self.assertEqual(manifest["renderer"]["skin"], "example:test-skin:9.0.0-SNAPSHOT")
        staged_pom = (self.root / "target/docsite/renderer/pom.xml").read_text()
        self.assertIn("https://central.sonatype.com/repository/maven-snapshots/", staged_pom)
        self.assertIn("<releases><enabled>false</enabled></releases>", staged_pom)
        self.assertIn("<checksumPolicy>fail</checksumPolicy>", staged_pom)

    def test_release_generation_failure_retains_previous_complete_api(self) -> None:
        """Release generation failure retains previous complete api."""
        result = self.run_site("api")
        self.assertEqual(result.returncode, 0, result.stderr)
        previous_manifest = (self.root / "target/docsite/release-manifest.json").read_bytes()
        (self.root / "source.txt").write_text("new working source")
        self.environment["DOCS_TEST_FAIL_RELEASE"] = "1"
        result = self.run_site("api", "--javadoc-cache", "refresh")
        self.assertNotEqual(result.returncode, 0)
        api = self.root / "target/generated-api/api"
        self.assertEqual((api / "next/index.html").read_text(), "working source")
        self.assertEqual((api / "1.0.0/index.html").read_text(), "released source")
        self.assertEqual((self.root / "target/docsite/release-manifest.json").read_bytes(), previous_manifest)
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertEqual(calls[-3:], [["maven", "new working source"], ["maven-version"], ["maven", "released source"]])
        report = json.loads((self.root / "target/docsite/build-timings.json").read_text())
        self.assertEqual(report["stages"][-1]["name"], "javadoc/1.0.0")
        self.assertEqual(report["stages"][-1]["status"], "failed")

    def test_failed_site_command_does_not_publish_a_new_build_manifest(self) -> None:
        """Failed site command does not publish a new build manifest."""
        records = self.root / "releases.json"
        records.write_text("[]")
        result = self.run_site("build", "--mode", "published", "--releases", str(records))
        self.assertEqual(result.returncode, 0, result.stderr)
        completed_manifest = self.root / "target/pages/release-manifest.json"
        previous = completed_manifest.read_bytes()
        self.assertEqual(json.loads(previous)["mode"], "published")
        self.environment["DOCS_TEST_FAIL_SITE"] = "1"
        result = self.run_site("build")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(completed_manifest.read_bytes(), previous)
        self.assertEqual(
            json.loads((self.root / "target/docsite/release-manifest.json").read_text())["mode"], "preview"
        )
        report = json.loads((self.root / "target/docsite/build-timings.json").read_text())
        self.assertEqual(report["stages"][-1]["name"], "maven-site")
        self.assertEqual(report["stages"][-1]["status"], "failed")

    def test_api_generation_requires_an_index(self) -> None:
        """Reject an external tool that reports success without producing Javadoc."""
        self.executable(self.root / "mvnw", "pass\n")
        result = self.run_site("api")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Javadoc index missing", result.stderr)
        self.assertFalse((self.root / "target/generated-api").exists())
        self.executable(
            self.root / "mvnw",
            "from pathlib import Path\np = Path('target/reports/apidocs'); p.mkdir(parents=True, exist_ok=True)\n"
            "(p / 'index.html').write_text('Incomplete API')\n",
        )
        result = self.run_site("api")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Javadoc assets missing", result.stderr)

    def test_pending_snapshot_reuses_development_api(self) -> None:
        """Build an untagged current snapshot from the current sources."""
        self.git("tag", "-d", "v1.0.0")
        result = self.run_site("api")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "target/generated-api/api/1.0.0/index.html").read_text(), "working source")
        self.assertNotIn(["maven-version"], [json.loads(line) for line in self.log.read_text().splitlines()])
        self.assertFalse((self.root / ".cache/docsite").exists())

    def test_pending_commit_option_and_environment_pin_preview_api_without_publishing(self) -> None:
        """Render an unpublished stable ancestor after the development version has advanced."""
        self.git("tag", "-d", "v1.0.0")
        (self.root / "docs").mkdir(exist_ok=True)
        (self.root / "docs/index.md").write_text("# Release")
        (self.site / "navigation.json").write_text("{}")
        (self.root / "source.txt").write_text("prepared release source")
        self.git("add", ".")
        self.git("commit", "-qm", "Prepared release")
        commit = self.git("rev-parse", "HEAD")
        (self.root / "pom.xml").write_text((self.root / "pom.xml").read_text().replace("1.0.0", "1.0.1-SNAPSHOT"))
        (self.root / "source.txt").write_text("next development source")
        self.git("add", ".")
        self.git("commit", "-qm", "Next development")
        result = self.run_site("build")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing release tag", result.stderr)
        self.environment["DOCSITE_PENDING_COMMIT"] = commit
        result = self.run_site("build")
        self.assertEqual(result.returncode, 0, result.stderr)
        completed = self.root / "target/pages"
        self.assertEqual((completed / "api/next/index.html").read_text(), "next development source")
        self.assertEqual((completed / "api/1.0.0/index.html").read_text(), "prepared release source")
        manifest = json.loads((completed / "release-manifest.json").read_text())
        self.assertEqual(manifest["versions"][1]["sourceCommit"], commit)
        self.assertEqual(manifest["versions"][1]["banner"], "unreleased")
        self.assertFalse((self.root / ".cache/docsite").exists())
        self.assertNotIn(["maven-version"], [json.loads(line) for line in self.log.read_text().splitlines()])

        self.environment["DOCSITE_PENDING_COMMIT"] = "invalid"
        result = self.run_site("api", "--pending-commit", commit)
        self.assertEqual(result.returncode, 0, result.stderr)
        records = self.root / "releases.json"
        records.write_text('[{"tag_name":"v1.0.0"}]')
        result = self.run_site("build", "--mode", "published", "--releases", str(records))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Pending release commits require preview mode", result.stderr)

    def test_warm_cache_rebuilds_development_and_skips_only_pinned_historical_generation(self) -> None:
        """Reuse complete historical output while preserving fresh working-source documentation."""
        result = self.run_site("api")
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads((self.root / "target/docsite/build-timings.json").read_text())
        self.assertEqual(report["stages"][-1]["cache"], "miss")
        manifests = list((self.root / ".cache/docsite/javadoc").glob("*/manifest.json"))
        self.assertEqual(len(manifests), 1)
        self.log.write_text("")
        (self.root / "source.txt").write_text("changed working source")
        result = self.run_site("api")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            [json.loads(line) for line in self.log.read_text().splitlines()],
            [["maven", "changed working source"], ["maven-version"]],
        )
        self.assertEqual((self.root / "target/generated-api/api/next/index.html").read_text(), "changed working source")
        self.assertEqual((self.root / "target/generated-api/api/1.0.0/index.html").read_text(), "released source")
        report = json.loads((self.root / "target/docsite/build-timings.json").read_text())
        self.assertEqual(report["stages"][-1]["cache"], "hit")
        self.assertEqual(report["stages"][-2]["cache"], "development")
        self.log.write_text("")
        result = self.run_site("api", "--javadoc-cache", "refresh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(["maven", "released source"], [json.loads(line) for line in self.log.read_text().splitlines()])
        self.assertEqual(
            json.loads((self.root / "target/docsite/build-timings.json").read_text())["stages"][-1]["cache"], "refresh"
        )
        modified = manifests[0].stat().st_mtime_ns
        self.log.write_text("")
        self.environment["DOCSITE_JAVADOC_CACHE"] = "off"
        result = self.run_site("api")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            [json.loads(line) for line in self.log.read_text().splitlines()],
            [["maven", "changed working source"], ["maven", "released source"]],
        )
        self.assertEqual(manifests[0].stat().st_mtime_ns, modified)
        self.assertEqual(
            json.loads((self.root / "target/docsite/build-timings.json").read_text())["stages"][-1]["cache"], "off"
        )

    def test_cache_never_substitutes_for_release_provenance_validation(self) -> None:
        """A warm cache cannot hide a removed or incorrectly moved source tag."""
        result = self.run_site("api")
        self.assertEqual(result.returncode, 0, result.stderr)
        old_manifest = (self.root / "target/docsite/release-manifest.json").read_bytes()
        self.git("tag", "-d", "v1.0.0")
        (self.root / "pom.xml").write_text((self.root / "pom.xml").read_text().replace("1.0.0", "1.0.1-SNAPSHOT"))
        self.log.write_text("")
        result = self.run_site("api")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing release tag", result.stderr)
        self.assertEqual(self.log.read_text(), "")
        self.assertEqual((self.root / "target/docsite/release-manifest.json").read_bytes(), old_manifest)
        report = json.loads((self.root / "target/docsite/build-timings.json").read_text())
        self.assertEqual(len(report["stages"]), 1)
        self.assertEqual(report["stages"][0]["name"], "release-selection")
        self.assertEqual(report["stages"][0]["status"], "failed")
        self.git("add", "pom.xml")
        self.git("commit", "-qm", "New development")
        self.git("tag", "v1.0.0")
        result = self.run_site("api")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Maven version", result.stderr)
        self.assertEqual(self.log.read_text(), "")

    def test_invalid_cache_environment_fails_early_but_explicit_mode_overrides_it(self) -> None:
        """Validate environment defaults while preserving the command line's explicit choice."""
        self.environment["DOCSITE_JAVADOC_CACHE"] = "invalid"
        result = self.run_site("api")
        self.assertEqual(result.returncode, 2)
        self.assertIn("DOCSITE_JAVADOC_CACHE", result.stderr)
        self.assertFalse(self.log.exists())
        result = self.run_site("api", "--javadoc-cache", "off")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.root / ".cache/docsite").exists())

    def test_published_artifact_replaces_historical_generation_and_survives_local_toolchain_changes(self) -> None:
        """Use signed release output before probing source caches, even in refresh/off cache modes."""
        records = self.release_assets()
        options = ("api", "--mode", "published", "--releases", str(records))
        result = self.run_site(*options, "--javadoc-cache", "refresh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "target/generated-api/api/1.0.0/index.html").read_text(), "published release API")
        self.assertEqual(
            (self.root / "target/generated-api/api/1.0.0/script-files/search.js").read_text(), "Published search script"
        )
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertEqual([call for call in calls if call[0] == "maven"], [["maven", "working source"]])
        self.assertNotIn(["maven-version"], calls)
        self.assertEqual(len([call for call in calls if call[0] == "download"]), 3)
        self.assertTrue(any(call[0] == "gh" for call in calls))
        self.assertFalse((self.root / ".cache/docsite/javadoc").exists())
        report = json.loads((self.root / "target/docsite/build-timings.json").read_text())
        self.assertEqual(report["stages"][-1]["cache"], "release-asset")
        self.assertEqual(report["javadocSource"], "auto")
        self.assertNotIn(
            "assets", json.loads((self.root / "target/docsite/release-manifest.json").read_text())["versions"][1]
        )

        wrapper = self.root / "mvnw"
        wrapper.write_text(wrapper.read_text().replace("Java version: 25", "Java version: 30"))
        (self.root / "source.txt").write_text("later development source")
        self.log.write_text("")
        result = self.run_site(*options, "--javadoc-cache", "off")
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertEqual([call for call in calls if call[0] == "maven"], [["maven", "later development source"]])
        self.assertFalse(any(call[0] in ("maven-version", "download") for call in calls))
        self.assertTrue(any(call[0] == "gh" for call in calls), "Warm artifacts still require attestation verification")
        self.assertEqual(
            json.loads((self.root / "target/docsite/build-timings.json").read_text())["stages"][-1]["cache"],
            "release-asset-cache",
        )

    def test_legacy_published_release_falls_back_to_the_existing_source_cache(self) -> None:
        """A release with no advertised artifact and no producer marker remains buildable."""
        records = self.root / "releases.json"
        records.write_text('[{"tag_name":"v1.0.0","assets":[]}]')
        result = self.run_site("api", "--mode", "published", "--releases", str(records))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "target/generated-api/api/1.0.0/index.html").read_text(), "released source")
        self.assertEqual(
            json.loads((self.root / "target/docsite/build-timings.json").read_text())["stages"][-1]["cache"], "miss"
        )

    def test_invalid_advertised_artifact_fails_without_replacing_previous_api_or_using_source_cache(self) -> None:
        """Wrong producer provenance cannot silently become regenerated publication output."""
        result = self.run_site("api")
        self.assertEqual(result.returncode, 0, result.stderr)
        previous_manifest = (self.root / "target/docsite/release-manifest.json").read_bytes()
        records = self.release_assets(commit="0" * 40)
        self.log.write_text("")
        result = self.run_site("api", "--mode", "published", "--releases", str(records))
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.root / "target/generated-api/api/1.0.0/index.html").read_text(), "released source")
        self.assertEqual((self.root / "target/docsite/release-manifest.json").read_bytes(), previous_manifest)
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertNotIn(["maven-version"], calls)
        self.assertNotIn(["maven", "released source"], calls)
        report = json.loads((self.root / "target/docsite/build-timings.json").read_text())
        self.assertEqual(report["stages"][-1]["name"], "javadoc/1.0.0")
        self.assertEqual(report["stages"][-1]["status"], "failed")

    def test_source_override_and_preview_do_not_consume_published_artifacts(self) -> None:
        """Source mode provides an explicit local path; preview never performs artifact IO."""
        records = self.release_assets(commit="0" * 40)
        self.environment["DOCSITE_JAVADOC_SOURCE"] = "source"
        result = self.run_site("api", "--mode", "published", "--releases", str(records), "--javadoc-cache", "off")
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertEqual(calls, [["maven", "working source"], ["maven", "released source"]])
        self.assertFalse((self.root / ".cache/docsite").exists())
        self.assertEqual(
            json.loads((self.root / "target/docsite/build-timings.json").read_text())["javadocSource"], "source"
        )
        self.log.write_text("")
        result = self.run_site("api", "--javadoc-source", "auto", "--javadoc-cache", "off")
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertEqual(calls, [["maven", "working source"], ["maven", "released source"]])

    def test_invalid_source_environment_fails_early_and_explicit_option_overrides_it(self) -> None:
        """Reject invalid environment modes before invoking external tools."""
        self.environment["DOCSITE_JAVADOC_SOURCE"] = "invalid"
        result = self.run_site("api")
        self.assertEqual(result.returncode, 2)
        self.assertIn("DOCSITE_JAVADOC_SOURCE", result.stderr)
        self.assertFalse(self.log.exists())
        result = self.run_site("api", "--javadoc-source", "source")
        self.assertEqual(result.returncode, 0, result.stderr)
        result = subprocess.run(
            [
                sys.executable,
                "-c",
                "import sys; sys.path.insert(0, 'scripts'); from pathlib import Path; "
                "from build_javadoc import build; from docs_release import Project; root=Path.cwd(); "
                "build(root, Project(root).plan('preview'), artifact_mode='invalid')",
            ],
            cwd=self.root,
            env=self.environment,
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Unknown Javadoc source mode", result.stderr)

    def test_failed_executable_documentation_is_recorded_before_api_generation(self) -> None:
        """A failing prerequisite stops generation and leaves an invocation-specific timing report."""
        self.prepare_dependency_inventory()
        result = self.run_site("verify")
        self.assertNotEqual(result.returncode, 0)
        report = json.loads((self.root / "target/docsite/build-timings.json").read_text())
        self.assertEqual(report["stages"][-2]["name"], "dependency-inventory")
        self.assertEqual(report["stages"][-2]["status"], "passed")
        self.assertEqual(report["stages"][-1]["name"], "executable-docs")
        self.assertEqual(report["stages"][-1]["status"], "failed")
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertEqual([call[0] for call in calls], ["dependency-inventory"])

    def test_stale_dependency_inventory_fails_before_executable_docs_api_and_frontend(self) -> None:
        """Verification rejects edited generated pages without repairing them or starting later work."""
        self.prepare_dependency_inventory()
        inventory = self.root / "docs/reference/dependencies.md"
        edited = inventory.read_text() + "\nUnexpected manual edit.\n"
        inventory.write_text(edited)
        result = self.run_site("verify")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("dependencies.md", result.stderr + result.stdout)
        self.assertEqual(inventory.read_text(), edited)
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertEqual([call[0] for call in calls], ["dependency-inventory"])
        report = json.loads((self.root / "target/docsite/build-timings.json").read_text())
        self.assertEqual(
            [(stage["name"], stage["status"]) for stage in report["stages"]],
            [("release-selection", "passed"), ("dependency-inventory", "failed")],
        )
        self.assertFalse((self.root / "target/generated-api").exists())
        self.assertFalse((self.root / "target/docsite/release-manifest.json").exists())
        self.assertFalse((self.root / "target/pages").exists())

    def test_callable_builder_retains_default_cache_and_timing_behavior(self) -> None:
        """Support the existing build(root, plan) interface without mandatory timing plumbing."""
        result = subprocess.run(
            [
                sys.executable,
                "-c",
                "import sys; sys.path.insert(0, 'scripts'); from pathlib import Path; "
                "from build_javadoc import build; from docs_release import Project; root=Path.cwd(); "
                "build(root, Project(root).plan('preview'))",
            ],
            cwd=self.root,
            env=self.environment,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / "target/generated-api/api/1.0.0/search.html").is_file())

    def test_importing_the_site_entry_point_does_not_execute_a_build(self) -> None:
        """Keep the callable entry point usable without parsing arguments or starting tools at import time."""
        result = subprocess.run(
            [sys.executable, "-c", "import sys; sys.path.insert(0, 'scripts'); import docs_site"],
            cwd=self.root,
            env=self.environment,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.log.exists())
        self.assertFalse((self.site / "guide-build.json").exists())
        self.assertFalse((self.root / "target/docsite/build-timings.json").exists())


if __name__ == "__main__":
    unittest.main()
