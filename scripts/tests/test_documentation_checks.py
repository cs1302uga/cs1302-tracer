"""Check executable documentation contracts through public script interfaces."""

import copy
import json
import re
import shutil
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from typing import Any

from scripts import check_docs

from tests.support import SCRIPTS, copy_scripts, executable, invoke, tool_environment


def marked(name: str, content: str) -> str:
    """Wrap a known example in documentation markers."""
    return f"<!-- {name}:start -->\n```text\n{content}\n```\n<!-- {name}:end -->\n"


def tutorial_results() -> dict[str, Any]:
    """Model observable Java results while preserving independent external-tool boundaries."""
    answer = {"name": "answer", "type": "int", "value": 7, "final": False}
    box = {"name": "box", "type": "Main.Box", "value": {"ref": 59}, "final": False}
    field = {"name": "value", "type": "int", "value": 7, "final": False}
    steps: list[dict[str, Any]] = [
        {
            "line": line,
            "file": "Main.java",
            "method": "main",
            "stdout": output,
            "callStack": [{"methodName": "main", "locals": locals_}],
            "heap": {"59": {"kind": "object", "type": "Main$Box", "fields": [field]}},
        }
        for line, output, locals_ in ((6, "", []), (7, "", [box]), (8, "", [box, answer]), (9, "7\n", [box, answer]))
    ]
    steps[0]["callStack"].append({"methodName": "<init>", "locals": []})
    chronological: list[dict[str, Any]] = [
        {
            "line": 4,
            "file": "Loop.java",
            "stdout": output,
            "callStack": [{"methodName": "main", "locals": [{"name": "i", "value": value}]}],
        }
        for value, output in enumerate(("", "0\n", "0\n1\n"))
    ]
    accumulated = copy.deepcopy(chronological)
    accumulated[-1]["stdout"] = "0\n1\n2\n"
    chronological.append(
        {
            "line": 6,
            "file": "Loop.java",
            "method": "main",
            "stdout": "0\n1\n2\n",
            "callStack": [{"methodName": "main", "locals": []}],
        }
    )
    snapshot_steps: list[dict[str, Any]] = [
        {
            "file": "SnapshotDemo.java",
            "stdout": output,
            "callStack": [{"methodName": "main", "locals": locals_}],
        }
        for output, locals_ in (
            ("", [{"name": "args", "value": {"ref": 1}}]),
            ("", [{"name": "args", "value": {"ref": 1}}, {"name": "value", "value": 7}]),
            ("8\n", [{"name": "args", "value": {"ref": 1}}, {"name": "value", "value": 8}]),
        )
    ]
    presentation = copy.deepcopy(snapshot_steps)
    for step in presentation:
        locals_ = step["callStack"][0]["locals"]
        locals_.pop(0)
        for item in locals_:
            item["binary"] = {"type": "int", "encoding": "twos-complement", "bits": f"{item['value']:032b}"}
    limits = dict.fromkeys(
        (
            "timeoutMillis",
            "snapshots",
            "outputBytes",
            "heapObjects",
            "elements",
            "traceBytes",
            "sourceBytes",
            "sourceFiles",
            "threads",
            "frames",
            "snapshotBytes",
        ),
        1,
    )
    return {
        "first": {"format": "modern", "entryFile": "Main.java", "steps": steps},
        "latest": {"format": "modern", "entryFile": "Loop.java", "breakpoints": {"4": accumulated[-1]}},
        "accumulated": {"format": "modern", "entryFile": "Loop.java", "breakpoints": {"4": accumulated}},
        "chronological": {"format": "modern", "entryFile": "Loop.java", "steps": chronological},
        "locations": [{"lineNumber": 4, "validBreakpoint": True, "lineContent": "            System.out.println(i);"}],
        "final": {"format": "modern", "entryFile": "SnapshotDemo.java", "steps": [snapshot_steps[-1]]},
        "snapshots": {"format": "modern", "entryFile": "SnapshotDemo.java", "steps": snapshot_steps},
        "presentation": {"format": "modern", "entryFile": "SnapshotDemo.java", "steps": presentation},
        "input": {
            "format": "modern",
            "entryFile": "Greeting.java",
            "stdin": "Ada\n",
            "steps": [{"stdout": "Hello, Ada!\n", "stdinConsumed": "Ada\n"}],
        },
        "dependency": {
            "format": "modern",
            "entryFile": "UseHelper.java",
            "steps": [{"file": "UseHelper.java", "stdout": "42\n"}],
        },
        "multifile": {
            "format": "modern",
            "entryFile": "demo/Main.java",
            "steps": [{"stdout": "42\n"}],
            "sources": {"demo/Helper.java": "helper", "demo/Main.java": "main"},
        },
        "partial": {
            "schemaVersion": 1,
            "status": "stopped",
            "complete": False,
            "stopReason": "snapshot_limit",
            "limits": limits,
            "trace": {"steps": [snapshot_steps[0]]},
        },
    }


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
        (self.root / "docs/guides").mkdir()
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
        for name in ("first-trace", "read-a-trace", "trace-a-loop", "java-integration", "batch-jobs"):
            shutil.copy2(SCRIPTS.parent / f"docs/tutorials/{name}.md", self.root / f"docs/tutorials/{name}.md")
        for name in ("tracing", "installation"):
            shutil.copy2(SCRIPTS.parent / f"docs/guides/{name}.md", self.root / f"docs/guides/{name}.md")
        # Links are tested separately; keep the tutorial example blocks without their prose.
        for path in [*(self.root / "docs/tutorials").glob("*.md"), *(self.root / "docs/guides").glob("*.md")]:
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
        (self.root / "tutorial-results.json").write_text(json.dumps(tutorial_results()))
        executable(
            self.root / "bin/javac",
            """
import sys
from pathlib import Path
if '--version' in sys.argv:
    print('javac 25.0.1')
elif 'Helper.java' in sys.argv:
    assert sys.argv[1:] == ['-d', 'classes', 'Helper.java']
    assert 'package teaching;' in Path('Helper.java').read_text()
    Path('classes/teaching').mkdir()
    Path('classes/teaching/Helper.class').touch()
else:
    Path('TraceExample.class').touch()
""",
        )
        executable(
            self.root / "bin/jar",
            """
import sys
from pathlib import Path
assert sys.argv[1:] == ['--create', '--file', 'helper.jar', '-C', 'classes', '.']
assert Path('classes/teaching/Helper.class').is_file()
Path('helper.jar').touch()
""",
        )
        executable(
            self.root / "bin/java",
            """
import json, os, re, sys
from pathlib import Path
results = json.loads((Path(__file__).resolve().parents[1] / 'tutorial-results.json').read_text())
if '-XshowSettings:properties' in sys.argv:
    if not os.environ.get('MISSING_JAVA_HOME'):
        home = os.environ.get('DISCOVERED_JAVA_HOME', str(Path(__file__).resolve().parents[1]))
        print('    java.home = ' + home, file=sys.stderr)
elif '--version' in sys.argv:
    root = Path(__file__).resolve().parents[1]
    with (root / 'jdk-commands.jsonl').open('a') as stream:
        stream.write(json.dumps({'args': sys.argv, 'java_home': os.environ.get('JAVA_HOME'),
                                 'resolved_home': str(Path(os.environ['JAVA_HOME']).resolve()),
                                 'path': os.environ['PATH'], 'cwd': str(Path.cwd())}) + '\\n')
    if '-jar' in sys.argv:
        assert sys.argv[1:] == ['-jar', 'code-tracer.jar', '--version']
        assert Path('code-tracer.jar').is_file()
        print('unexpected output' if os.environ.get('BAD_TRACER_VERSION') else 'code-tracer 4.2.0-SNAPSHOT (abc123)')
    elif not os.environ.get('EMPTY_JAVA_VERSION'):
        print('openjdk 25.0.1')
elif '--help' in sys.argv:
    print('Usage: tracer')
elif 'TraceExample' in sys.argv:
    assert Path('TraceExample.class').is_file()
    print(json.dumps({'status': 'completed', 'complete': True, 'stdout': 'hello\\n', 'trace': {'steps': []}}))
elif 'Main.java' in sys.argv:
    trace = results['first']
    trace['sources'] = {'Main.java': Path('Main.java').read_text()}
    print(json.dumps(trace))
elif 'Loop.java' in sys.argv:
    mode = ('locations' if 'list-breakpoints' in sys.argv else
            'chronological' if '--all-breakpoints' in sys.argv else
            'accumulated' if '--accumulate-breakpoints' in sys.argv else 'latest')
    trace = results[mode]
    if mode != 'locations':
        trace['sources'] = {'Loop.java': Path('Loop.java').read_text()}
    print(json.dumps(trace))
elif 'SnapshotDemo.java' in sys.argv:
    mode = ('partial' if '--result-envelope' in sys.argv else
            'presentation' if '--include-primitive-bits' in sys.argv else
            'snapshots' if '--all-breakpoints' in sys.argv else 'final')
    trace = results[mode]
    if mode != 'partial':
        trace['sources'] = {'SnapshotDemo.java': Path('SnapshotDemo.java').read_text()}
    print(json.dumps(trace))
    if mode == 'partial':
        sys.exit(int(os.environ.get('PARTIAL_EXIT', '3')))
elif 'Greeting.java' in sys.argv:
    assert '--stdin-file' in sys.argv
    assert sys.argv[sys.argv.index('--stdin-file') + 1] == 'input.txt'
    assert Path('input.txt').read_text() == 'Ada\\n'
    trace = results['input']
    trace['sources'] = {'Greeting.java': Path('Greeting.java').read_text()}
    print(json.dumps(trace))
elif 'UseHelper.java' in sys.argv:
    assert Path('helper.jar').is_file()
    assert sys.argv[sys.argv.index('--class-path') + 1] == 'helper.jar'
    trace = results['dependency']
    trace['sources'] = {'UseHelper.java': Path('UseHelper.java').read_text()}
    print(json.dumps(trace))
elif 'trace' in sys.argv and 'code-tracer.jar' in sys.argv:
    source = sys.stdin.read()
    assert source == Path('submission.txt').read_text()
    trace = results['multifile']
    trace['sources'] = dict(re.findall(r'^// --- ([^\\n]+) ---\\n(.*?)(?=^// --- |\\Z)', source, re.M | re.S))
    print(json.dumps(trace))
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

    def test_jdk_setup_uses_reported_home_with_spaces_and_one_selected_environment(self) -> None:
        """Follow the JVM's reported home rather than assuming its launcher location owns the JDK."""
        home = self.root / "actual JDK home"
        (home / "bin").mkdir(parents=True)
        for tool in ("java", "javac"):
            (home / "bin" / tool).symlink_to(self.root / "bin" / tool)
        result = invoke(self.root, "check_docs.py", "--update-cli", env=dict(self.env, DISCOVERED_JAVA_HOME=str(home)))
        self.assertEqual(result.returncode, 0, result.stderr)
        records = [json.loads(line) for line in (self.root / "jdk-commands.jsonl").read_text().splitlines()]
        self.assertEqual(len(records), 6)
        self.assertEqual(sum("-jar" in record["args"] for record in records), 3)
        for record in records:
            self.assertEqual(record["resolved_home"], str(home.resolve()))
            self.assertIn("selected JDK with spaces", record["java_home"])
            self.assertEqual(record["args"][0], record["java_home"] + "/bin/java")
            self.assertEqual(record["path"].split(":")[0], record["java_home"] + "/bin")
            self.assertEqual(Path(record["cwd"]).name, "code-tracer")

    def test_jdk_setup_rejects_missing_discovery_incomplete_jdk_and_invalid_tool_evidence(self) -> None:
        """Fail when the current JVM cannot establish a full JDK or the checkpoint output is absent."""
        for variable, message in (
            ("MISSING_JAVA_HOME", "Cannot discover the running JDK"),
            ("EMPTY_JAVA_VERSION", "Verify explicit and PATH Java tool versions"),
            ("BAD_TRACER_VERSION", "Selected Java must run the packaged tracer"),
        ):
            with self.subTest(variable=variable):
                result = invoke(self.root, "check_docs.py", "--update-cli", env=dict(self.env, **{variable: "1"}))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)
        compiler = self.root / "bin/javac"
        compiler.chmod(0o644)
        result = invoke(self.root, "check_docs.py", "--update-cli", env=self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("A full executable JDK is required", result.stderr)

    def test_jdk_snippets_reject_lost_selection_quoting_or_checkpoint_evidence(self) -> None:
        """Execute altered documentation verbatim and detect wrong paths, missing output, and failed commands."""
        path = self.root / "docs/guides/installation.md"
        original = path.read_text()
        changes = [
            (original.replace("<!-- jdk-inspect-bash:end -->", ""), "jdk-inspect-bash: missing"),
            (original.replace("'/path/to/your/jdk'", "'/different/placeholder'"), "documented placeholder"),
            (original.replace('export PATH="$JAVA_HOME/bin:$PATH"', ""), "exit 1"),
            (
                original.replace('"$JAVA_HOME/bin/java" --version', "$JAVA_HOME/bin/java --version"),
                "No such file or directory",
            ),
            (
                original.replace(check_docs.block(original, "jdk-inspect-bash"), "printf inspected"),
                "JDK inspection must show current selection",
            ),
            (
                original.replace(
                    check_docs.block(original, "jdk-inspect-bash"),
                    check_docs.block(original, "jdk-inspect-bash").replace("type -a java javac", "type -a java"),
                ),
                "JDK inspection must show current selection",
            ),
            (original.replace('"$JAVA_HOME/bin/javac" --version\n', ""), "Verify explicit and PATH Java tool versions"),
            (
                original.replace(
                    check_docs.block(original, "jdk-verify-bash"), "java --version\njavac --version\n" * 2
                ),
                "Verify selected Java executable paths",
            ),
            (
                original.replace(check_docs.block(original, "jdk-tracer-bash"), "printf 'wrong checkpoint\\n'"),
                "Explicit and PATH tracer installation checkpoints",
            ),
            (
                original.replace(
                    check_docs.block(original, "jdk-verify-bash"),
                    check_docs.block(original, "jdk-verify-bash") + "\nfalse\ntrue",
                ),
                "exit 1",
            ),
        ]
        for content, message in changes:
            with self.subTest(message=message):
                path.write_text(content)
                result = invoke(self.root, "check_docs.py", "--update-cli", env=self.env)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)

    def test_jdk_markers_require_an_executable_fenced_block(self) -> None:
        """Reject a marker pair whose example was replaced with unfenced prose."""
        path = self.root / "docs/guides/installation.md"
        original = path.read_text()
        inspection = check_docs.block(original, "jdk-inspect-bash")
        path.write_text(original.replace("```sh\n" + inspection + "\n```", "Inspect the current Java selection."))
        result = invoke(self.root, "check_docs.py", "--update-cli", env=self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("jdk-inspect-bash: missing code block", result.stderr)

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
        path = self.root / "tutorial-results.json"
        original = path.read_text()
        data = json.loads(original)
        data["first"]["steps"][-1]["stdout"] = "8\n"
        path.write_text(json.dumps(data))
        result = invoke(self.root, "check_docs.py", "--update-cli", env=self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("AssertionError", result.stderr)
        path.write_text(original)
        executable(
            self.root / "bin/javac",
            """
import sys
if '--version' in sys.argv:
    print('javac 25.0.1')
else:
    sys.stderr.write('bad Java example')
    sys.exit(1)
""",
        )
        result = invoke(self.root, "check_docs.py", env=self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("bad Java example", result.stderr)

    def test_reading_excerpts_must_match_actual_local_and_field_objects(self) -> None:
        """Reject edited JSON teaching excerpts while allowing arbitrary captured identities."""
        path = self.root / "docs/tutorials/read-a-trace.md"
        original = path.read_text()
        for marker in ("reading-answer", "reading-field"):
            with self.subTest(marker=marker):
                excerpt = check_docs.block(original, marker)
                changed = json.loads(excerpt)
                changed["value"] = 8
                path.write_text(original.replace(excerpt, json.dumps(changed)))
                result = invoke(self.root, "check_docs.py", "--update-cli", env=self.env)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("excerpt differs", result.stderr)
        path.write_text(original)
        results = tutorial_results()
        for step in results["first"]["steps"]:
            step["heap"]["913"] = step["heap"].pop("59")
            for frame in step["callStack"]:
                for item in frame["locals"]:
                    if item["name"] == "box":
                        item["value"]["ref"] = 913
        results["first"]["steps"].insert(0, copy.deepcopy(results["first"]["steps"][0]))
        (self.root / "tutorial-results.json").write_text(json.dumps(results))
        result = invoke(self.root, "check_docs.py", env=self.env)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_learning_path_rejects_missing_or_changed_observation_evidence(self) -> None:
        """Fail on incorrect assignment timing, references, output, and loop retention."""
        original = json.dumps(tutorial_results())
        changes = []
        data = json.loads(original)
        data["first"]["steps"][1]["callStack"][0]["locals"].append({"name": "answer", "value": 7})
        changes.append(("early assignment", data, "must not be initialized"))
        data = json.loads(original)
        data["first"]["steps"][1]["callStack"][0]["locals"][0]["value"]["ref"] = 101
        changes.append(("reference changed", data, "Box reference changed"))
        data = json.loads(original)
        data["first"]["steps"][0]["stdout"] = "7\n"
        changes.append(("output reset", data, "stdout must accumulate"))
        data = json.loads(original)
        data["locations"][0]["validBreakpoint"] = False
        changes.append(("invalid location", data, "AssertionError"))
        data = json.loads(original)
        data["latest"]["breakpoints"]["4"]["callStack"][0]["locals"][0]["value"] = 1
        changes.append(("earlier latest visit", data, "last loop visit"))
        data = json.loads(original)
        data["accumulated"]["breakpoints"]["4"].pop(1)
        changes.append(("missing accumulated visit", data, "Missing accumulated loop visit"))
        data = json.loads(original)
        data["chronological"]["steps"].pop(1)
        changes.append(("missing chronological visit", data, "Missing chronological loop visit"))
        data = json.loads(original)
        data["chronological"]["steps"][1]["stdout"] = "0\n1\n"
        changes.append(("print timing", data, "stops precede their print"))
        data = json.loads(original)
        data["chronological"]["steps"][-1]["callStack"][0]["locals"].append({"name": "i", "value": 3})
        changes.append(("main exit scope", data, "out of scope at main exit"))
        data = json.loads(original)
        data["first"]["steps"][-1]["line"] = 8
        changes.append(("first main exit line", data, "AssertionError"))
        for name, data, message in changes:
            with self.subTest(name=name):
                (self.root / "tutorial-results.json").write_text(json.dumps(data))
                result = invoke(self.root, "check_docs.py", "--update-cli", env=self.env)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)

    def test_recipes_reject_misleading_input_presentation_and_limit_results(self) -> None:
        """Check actual documented outcomes instead of accepting valid JSON alone."""
        original = json.dumps(tutorial_results())
        changes = []
        data = json.loads(original)
        data["final"]["steps"].append(copy.deepcopy(data["final"]["steps"][0]))
        changes.append(("default capture", data, "only main exit"))
        data = json.loads(original)
        data["snapshots"]["steps"][1]["callStack"][0]["locals"][1]["value"] = 8
        changes.append(("missing increment", data, "show the increment"))
        data = json.loads(original)
        data["presentation"]["steps"][0]["callStack"][0]["locals"].append({"name": "args"})
        changes.append(("main args", data, "Main args were not removed"))
        data = json.loads(original)
        del data["presentation"]["steps"][1]["callStack"][0]["locals"][0]["binary"]
        changes.append(("binary metadata", data, "binary"))
        data = json.loads(original)
        data["input"]["steps"][0]["stdinConsumed"] = ""
        changes.append(("unconsumed input", data, "AssertionError"))
        data = json.loads(original)
        data["dependency"]["steps"][0]["file"] = "Helper.java"
        changes.append(("dependency breakpoint", data, "AssertionError"))
        data = json.loads(original)
        data["partial"]["status"] = "completed"
        changes.append(("wrong stop", data, "AssertionError"))
        data = json.loads(original)
        data["partial"]["limits"]["outputBytes"] = 0
        changes.append(("unlimited budget", data, "must set every budget"))
        for name, data, message in changes:
            with self.subTest(name=name):
                (self.root / "tutorial-results.json").write_text(json.dumps(data))
                result = invoke(self.root, "check_docs.py", "--update-cli", env=self.env)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)
        (self.root / "tutorial-results.json").write_text(original)
        result = invoke(self.root, "check_docs.py", env=dict(self.env, PARTIAL_EXIT="0"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("exit 0", result.stderr)

    def test_missing_learning_path_markers_and_output_files_fail(self) -> None:
        """Never accept an unterminated example or reuse absent saved trace evidence."""
        path = self.root / "docs/tutorials/trace-a-loop.md"
        original = path.read_text()
        for suffix in ("start", "end"):
            with self.subTest(suffix=suffix):
                path.write_text(original.replace(f"<!-- loop-latest:{suffix} -->", ""))
                result = invoke(self.root, "check_docs.py", "--update-cli", env=self.env)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("loop-latest: missing start or end marker", result.stderr)
        path.write_text(original.replace("> latest.json", "> renamed.json"))
        result = invoke(self.root, "check_docs.py", env=self.env)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("latest.json", result.stderr)

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
if 'dependency:tree' in sys.argv:
    prefix = '-Ddependency.docs.outputDirectory='
    output = Path(next(arg.removeprefix(prefix) for arg in sys.argv if arg.startswith(prefix)))
    (output / 'tree.json').write_text('{"artifactId": "cs1302-tracer", "children": []}')
    (output / 'metadata.json').write_text('{"dependencies": []}')
else:
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
        (self.root / "LICENSE").write_text("MIT License\n")
        authoring = self.root / "docs/contributing/documentation-authoring.md"
        authoring.parent.mkdir(exist_ok=True)
        authoring.write_text("# Authoring\n\n## Maintain license and dependency pages\n")
        result = invoke(self.root, "dependency_docs.py", "update", env=self.env)
        self.assertEqual(result.returncode, 0, result.stderr)
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
