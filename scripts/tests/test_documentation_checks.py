"""Check executable documentation contracts through public script interfaces."""

import json
import re
import shutil
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

from scripts import check_docs

from tests.support import SCRIPTS, copy_scripts, executable, invoke, tool_environment


def marked(name: str, content: str) -> str:
    """Wrap a known example in documentation markers."""
    return f"<!-- {name}:start -->\n```text\n{content}\n```\n<!-- {name}:end -->\n"


class DocumentationChecksTests(unittest.TestCase):
    """Test documentation success, drift detection, and child-process failure."""

    def setUp(self) -> None:
        """Create documentation and a deterministic external Java stand-in."""
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        copy_scripts(self.root)
        (self.root / "target").mkdir()
        (self.root / "target/code-tracer.jar").touch()
        (self.root / "examples").mkdir()
        shutil.copy2(SCRIPTS.parent / "examples/verify.py", self.root / "examples/verify.py")
        (self.root / "docs/reference").mkdir(parents=True)
        (self.root / "docs/tutorials").mkdir()
        trace = {
            "format": "modern",
            "steps": [{"heap": {"1": {"type": "Person"}}}],
            "entryFile": "Main.java",
            "sources": {"Main.java": "source"},
        }
        data = json.dumps(trace)
        (self.root / "trace.json").write_text(data)
        quickstart = marked("quickstart", f"printf '%s' '{data}'")
        (self.root / "README.md").write_text(
            quickstart + "[Local](docs/reference/output.md) [Web](https://example.test)\n"
        )
        (self.root / "HACKING.md").write_text("[Fragment](#local) [Mail](mailto:docs@example.test)\n")
        for name in ("first-trace", "java-integration", "batch-jobs"):
            shutil.copy2(SCRIPTS.parent / f"docs/tutorials/{name}.md", self.root / f"docs/tutorials/{name}.md")
        # Links are tested separately; keep the tutorial example blocks without their prose.
        for path in (self.root / "docs/tutorials").glob("*.md"):
            text = path.read_text()
            path.write_text("\n".join(re.findall(r"<!-- .*?:start -->.*?<!-- .*?:end -->", text, re.S)))
        with zipfile.ZipFile(self.root / "target/code-tracer-bin.zip", "w") as archive:
            archive.writestr("code-tracer/code-tracer.jar", "stand-in JAR")
        (self.root / "docs/reference/output.md").write_text(
            marked("source", "public class Main {}") + marked("modern", data) + marked("pytutor", data)
        )
        (self.root / "docs/reference/batch.md").write_text('```json\n{"id":"hello"}\n```\n')
        (self.root / "docs/BOUNDED_TRACING.md").write_text("Budgets")
        self.env = tool_environment(self.root)
        executable(self.root / "bin/javac", "from pathlib import Path\nPath('TraceExample.class').touch()\n")
        executable(
            self.root / "bin/java",
            """
import json, sys
from pathlib import Path
if '--help' in sys.argv:
    print('Usage: tracer')
elif 'TraceExample' in sys.argv:
    assert Path('TraceExample.class').is_file()
    print(json.dumps({'status': 'completed', 'complete': True, 'stdout': 'hello\\n', 'trace': {'steps': []}}))
elif 'Main.java' in sys.argv:
    print(json.dumps({'format': 'modern', 'entryFile': 'Main.java',
        'sources': {'Main.java': Path('Main.java').read_text()},
        'steps': [{'stdout': '7\\n', 'callStack': [{'locals': [
            {'name': 'answer', 'value': 7}, {'name': 'box', 'value': {'ref': 5}}]}],
            'heap': {'5': {'fields': [{'value': 7}]}}}]}))
elif 'batch-trace' in sys.argv and Path('jobs.ndjson').exists():
    print(json.dumps({'id': 'hello', 'result': {'status': 'completed', 'complete': True, 'stdout': '42\\n'}}))
    print(json.dumps({'id': 'broken', 'result': {'status': 'failed', 'complete': False, 'diagnostics': ['error']}}))
elif 'batch-trace' in sys.argv:
    print(json.dumps({'id': 'hello', 'result': {'status': 'completed'}}))
else:
    trace = json.loads(Path('trace.json').read_text())
    if '--result-envelope' in sys.argv:
        trace = {'schemaVersion': 1, 'status': 'completed', 'complete': True, 'trace': trace,
                 'limits': {'timeoutMillis': 5000, 'snapshots': 0}}
    print(json.dumps(trace))
""",
        )

    def test_update_then_validate_and_detect_help_drift(self) -> None:
        """Run the documented examples and reject executable/reference disagreement."""
        result = invoke(self.root, "check_docs.py", "--update-cli", env=self.env)
        self.assertEqual(result.returncode, 0, result.stderr)
        result = invoke(self.root, "check_docs.py", env=self.env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("JSON examples, and batch request passed", result.stdout)
        path = self.root / "docs/reference/cli.md"
        path.write_text(path.read_text().replace("Usage: tracer", "Usage: obsolete"))
        result = invoke(self.root, "check_docs.py", env=self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("-Usage: obsolete", result.stderr)
        self.assertIn("+Usage: tracer", result.stderr)

    def test_manual_cli_section_survives_help_updates_and_stays_link_checked(self) -> None:
        """Preserve authored bytes through regeneration while checking help drift and manual links."""
        result = invoke(self.root, "check_docs.py", "--update-cli", env=self.env)
        self.assertEqual(result.returncode, 0, result.stderr)
        path = self.root / "docs/reference/cli.md"
        generated = path.read_bytes()
        manual = (
            b"\r\n<!-- cli-reference:manual -->\r\n\r\n## Binary dependencies\r\n"
            b"[Budgets](../BOUNDED_TRACING.md)\nKeep this spacing.  \r\n"
            b"\n<!-- cli-reference:manual -->\nThis second marker remains authored text.\n"
        )
        path.write_bytes(generated.replace(b"\n", b"\r\n") + manual)
        result = invoke(self.root, "check_docs.py", env=self.env)
        self.assertEqual(result.returncode, 0, result.stderr)

        java = self.root / "bin/java"
        java.write_text(java.read_text().replace("Usage: tracer", "Usage: changed"))
        result = invoke(self.root, "check_docs.py", env=self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("-Usage: tracer", result.stderr)
        self.assertIn("+Usage: changed", result.stderr)

        result = invoke(self.root, "check_docs.py", "--update-cli", env=self.env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(path.read_bytes(), generated.replace(b"Usage: tracer", b"Usage: changed") + manual)
        result = invoke(self.root, "check_docs.py", env=self.env)
        self.assertEqual(result.returncode, 0, result.stderr)

        path.write_bytes(path.read_bytes() + b"[Missing](absent.md)\n")
        result = invoke(self.root, "check_docs.py", env=self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("docs/reference/cli.md: missing absent.md", result.stderr)

    def test_only_standalone_manual_markers_separate_the_generated_reference(self) -> None:
        """Reject unmarked prose and misleading inline markers instead of hiding generated drift."""
        result = invoke(self.root, "check_docs.py", "--update-cli", env=self.env)
        self.assertEqual(result.returncode, 0, result.stderr)
        path = self.root / "docs/reference/cli.md"
        generated = path.read_text()
        for suffix in (
            "\nUnmarked authored text\n",
            "\ninline <!-- cli-reference:manual -->\n",
            "\n<!-- cli-reference:manual --> trailing text\n",
            "\n <!-- cli-reference:manual -->\n",
        ):
            with self.subTest(suffix=suffix):
                path.write_text(generated + suffix)
                result = invoke(self.root, "check_docs.py", env=self.env)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("--- docs/reference/cli.md", result.stderr)

        # A misplaced exact boundary must not hide generated help behind the manual section.
        path.write_text("# CLI reference\n\n<!-- cli-reference:manual -->\n" + generated)
        result = invoke(self.root, "check_docs.py", env=self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("+Usage: tracer", result.stderr)

        # A final standalone marker with no authored content or final newline is also valid.
        path.write_text(generated + "\n<!-- cli-reference:manual -->")
        result = invoke(self.root, "check_docs.py", env=self.env)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_missing_links_and_build_are_reported(self) -> None:
        """Identify broken local destinations and explain how to obtain the executable."""
        (self.root / "HACKING.md").write_text("[Missing](absent.md)\n")
        result = invoke(self.root, "check_docs.py", "--update-cli", env=self.env)
        self.assertIn("HACKING.md: missing absent.md", result.stderr)
        (self.root / "target/code-tracer-bin.zip").unlink()
        result = invoke(self.root, "check_docs.py", env=self.env)
        self.assertIn("including the binary ZIP", result.stderr)
        (self.root / "target/code-tracer.jar").unlink()
        result = invoke(self.root, "check_docs.py", env=self.env)
        self.assertIn("Build the JAR first", result.stderr)

    def test_missing_batch_json_has_an_actionable_diagnostic(self) -> None:
        """Require a JSON block rather than executing arbitrary fenced prose as a request."""
        (self.root / "docs/reference/batch.md").write_text("```text\nnot JSON\n```\n")
        result = invoke(self.root, "check_docs.py", "--update-cli", env=self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("batch: missing JSON code block", result.stderr)

    def test_packaged_tutorial_detects_incorrect_output_and_compile_failure(self) -> None:
        """Reject changed guest output and failures compiling the documented Java consumer."""
        java = self.root / "bin/java"
        original = java.read_text()
        java.write_text(original.replace("'stdout': '7\\n'", "'stdout': '8\\n'"))
        result = invoke(self.root, "check_docs.py", "--update-cli", env=self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("AssertionError", result.stderr)
        java.write_text(original)
        executable(self.root / "bin/javac", "import sys; sys.stderr.write('bad Java example'); sys.exit(1)")
        result = invoke(self.root, "check_docs.py", env=self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("bad Java example", result.stderr)

    def test_maven_verification_checks_examples_build_and_rendered_links(self) -> None:
        """Run real checkers around external tools and reject invalid examples and rendered output."""
        site = self.root / "website"
        site.mkdir()
        (site / "versions.json").write_text("[]")
        (self.root / "pom.xml").write_text("<project><version>1.0.0-SNAPSHOT</version></project>")
        executable(
            self.root / "mvnw",
            """
import sys
from pathlib import Path
assert '-P!docsite' in sys.argv
output = Path('target/reports/apidocs')
output.mkdir(parents=True, exist_ok=True)
for name in ('index.html', 'search.html', 'element-list'):
    (output / name).write_text('API')
""",
        )
        executable(
            self.root / "bin/npm",
            """
import os, shutil, sys
from pathlib import Path
assert sys.argv[1:] == ['run', 'docusaurus', '--', 'build']
output = Path('build')
output.mkdir(exist_ok=True)
shutil.copytree('generated-api/api', output / 'api', dirs_exist_ok=True)
text = '<a href="/cs1302-tracer/api/next/index.html">API</a>'
if os.environ.get('BAD_SITE'):
    text += '<a href="missing.html">Broken</a>'
(output / 'index.html').write_text(text)
""",
        )
        result = invoke(self.root, "check_docs.py", "--update-cli", env=self.env)
        self.assertEqual(result.returncode, 0, result.stderr)
        result = invoke(self.root, "docs_site.py", "verify", env=self.env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Packaged tutorials passed", result.stdout)
        self.assertIn("Validated links", result.stdout)
        manifest = site / "build/release-manifest.json"
        self.assertEqual(json.loads(manifest.read_text())["mode"], "preview")
        records = self.root / "published releases.json"
        records.write_text("[]")
        published = dict(self.env, DOCSITE_MODE="published", DOCSITE_RELEASES=str(records))
        result = invoke(self.root, "docs_site.py", "verify", env=published)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(manifest.read_text())["mode"], "published")
        result = invoke(self.root, "docs_site.py", "verify", env=dict(self.env, BAD_SITE="1"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing missing.html", result.stderr)
        shutil.rmtree(site / "generated-api")
        (self.root / "README.md").write_text("[Missing](absent.md)")
        result = invoke(self.root, "docs_site.py", "verify", env=self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing absent.md", result.stderr)
        self.assertFalse((site / "generated-api").exists())

    def test_command_failure_preserves_exit_status_and_stderr(self) -> None:
        """Explain a failed documentation child process."""
        with self.assertRaisesRegex(RuntimeError, "exit 7\\nfailed"):
            check_docs.run([sys.executable, "-c", "import sys; sys.stderr.write('failed'); sys.exit(7)"])

    def test_command_timeout_terminates_the_child(self) -> None:
        """Bound a stalled documentation command through its process interface."""
        with self.assertRaisesRegex(RuntimeError, "Documentation command timed out"):
            check_docs.run([sys.executable, "-c", "import time; time.sleep(60)"], timeout=0.1)
