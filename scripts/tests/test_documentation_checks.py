"""Check executable documentation contracts through public script interfaces."""

import json
import shutil
import sys
import tempfile
import unittest
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
        (self.root / "target/code-tracer-jar-with-dependencies.jar").touch()
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
        (self.root / "docs/tutorials/first-trace.md").write_text(quickstart)
        (self.root / "docs/reference/output.md").write_text(
            marked("source", "public class Main {}") + marked("modern", data) + marked("pytutor", data)
        )
        (self.root / "docs/reference/batch.md").write_text('```json\n{"id":"hello"}\n```\n')
        (self.root / "docs/BOUNDED_TRACING.md").write_text("Budgets")
        self.env = tool_environment(self.root)
        executable(
            self.root / "bin/java",
            """
import json, sys
from pathlib import Path
if '--help' in sys.argv:
    print('Usage: tracer')
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
        (self.root / "target/code-tracer-jar-with-dependencies.jar").unlink()
        result = invoke(self.root, "check_docs.py", env=self.env)
        self.assertIn("Build the JAR first", result.stderr)

    def test_missing_batch_json_has_an_actionable_diagnostic(self) -> None:
        """Require a JSON block rather than executing arbitrary fenced prose as a request."""
        (self.root / "docs/reference/batch.md").write_text("```text\nnot JSON\n```\n")
        result = invoke(self.root, "check_docs.py", "--update-cli", env=self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("batch: missing JSON code block", result.stderr)

    def test_command_failure_preserves_exit_status_and_stderr(self) -> None:
        """Explain a failed documentation child process."""
        with self.assertRaisesRegex(RuntimeError, "exit 7\\nfailed"):
            check_docs.run([sys.executable, "-c", "import sys; sys.stderr.write('failed'); sys.exit(7)"])

    def test_command_timeout_terminates_the_child(self) -> None:
        """Bound a stalled documentation command through its process interface."""
        with self.assertRaisesRegex(RuntimeError, "Documentation command timed out"):
            check_docs.run([sys.executable, "-c", "import time; time.sleep(60)"], timeout=0.1)
