#!/usr/bin/env python3
"""Check executable documentation; --update-cli refreshes CLI help only."""

import argparse
import difflib
import json
import os
import re
import runpy
import shlex
import signal
import subprocess
import tempfile
import zipfile
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "target/code-tracer.jar"


def run(
    args: list[str], source: str | None = None, cwd: Path = ROOT, timeout: float = 40, expected_status: int = 0
) -> str:
    """Run a child command and reject failures."""
    process = subprocess.Popen(
        args,
        cwd=cwd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        start_new_session=True,
    )
    try:
        out, err = process.communicate(source, timeout=timeout)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.communicate()
        raise RuntimeError(f"Documentation command timed out: {args}") from None
    if process.returncode != expected_status:
        raise RuntimeError(f"{args}: exit {process.returncode}\n{err}")
    return out


def trace(*args: str, source: str | None = None) -> str:
    """Run the built tracer with the requested arguments."""
    return run(["java", "-jar", str(JAR), *args], source)


def cli_reference() -> str:
    """Render CLI reference text from executable help."""
    text = (
        "# CLI reference\n\nGenerated from the executable by "
        "`./scripts/uv run python scripts/check_docs.py --update-cli`.\n"
        "Do not edit help blocks by hand. `trace` is an explicit subcommand.\n\n"
    )
    for command in ["", "trace", "batch-trace", "list-breakpoints", "show-licenses"]:
        help_text = run(
            [
                "java",
                "-Dpicocli.ansi=false",
                "-Dpicocli.usage.width=100",
                "-jar",
                str(JAR),
                *([command] if command else []),
                "--help",
            ]
        )
        text += f"## {command or 'Root command'}\n\n```text\n{help_text.rstrip()}\n```\n\n"
    return text + (
        "## Effective budgets\n\nOrdinary tracing uses finite defaults; envelope and batch requests use unlimited\n"
        "omitted limits. Zero disables a limit. See [bounded tracing](../BOUNDED_TRACING.md)\n"
        "for default values, inspection policies, exit codes, and partial results.\n"
    )


def cli_sections(reference: str) -> tuple[str, str]:
    """Separate generated text from a marked manual suffix, preserving its separator and line endings."""
    boundary = re.search(r"\r?\n<!-- cli-reference:manual -->(?=\r?\n|$)", reference)
    if boundary is None:
        return reference, ""
    return reference[: boundary.start()], reference[boundary.start() :]


def equal(actual: str, expected: str, name: str) -> None:
    """Require matching text and report a readable difference."""
    if actual != expected:
        difference = "".join(
            difflib.unified_diff(expected.splitlines(True), actual.splitlines(True), fromfile=name, tofile="actual")
        )
        raise AssertionError(difference)


def block(text: str, marker: str) -> str:
    """Read a fenced example inside named markers."""
    _, start, remainder = text.partition(f"<!-- {marker}:start -->")
    section, end, _ = remainder.partition(f"<!-- {marker}:end -->")
    if not start or not end:
        raise ValueError(f"{marker}: missing start or end marker")
    match = re.search(r"```[^\n]*\n(.*?)\n```", section, re.S)
    if match is None:
        raise ValueError(f"{marker}: missing code block")
    return match.group(1)


def check_links() -> None:
    """Check repository-relative Markdown destinations, including root entry points."""
    paths = [
        ROOT / "README.md",
        ROOT / "HACKING.md",
        *sorted((ROOT / "docs").rglob("*.md")),
        *sorted((ROOT / "examples").rglob("README.md")),
    ]
    errors = []
    for path in paths:
        text = re.sub(r"```.*?```", "", path.read_text(), flags=re.S)
        for target in re.findall(r'\]\(([^\s)]+)(?:\s+"[^"]*")?\)', text):
            url = urlsplit(target.strip("<>"))
            if url.scheme or url.netloc or not url.path:
                continue
            dest = (path.parent / unquote(url.path)).resolve()
            if not dest.exists():
                errors.append(f"{path.relative_to(ROOT)}: missing {target}")
    if errors:
        raise AssertionError("\n".join(errors))


def check_examples() -> None:
    """Execute and verify documented tracing examples."""
    readme = (ROOT / "README.md").read_text()
    command = block(readme, "quickstart")
    # Run exactly the documented shell block in a fresh directory with only its inputs.
    with tempfile.TemporaryDirectory() as directory:
        work = Path(directory)
        (work / "target").mkdir()
        (work / "target" / JAR.name).symlink_to(JAR)
        (work / "examples").symlink_to(ROOT / "examples", target_is_directory=True)
        result = json.loads(run(["sh", "-eu", "-c", command], cwd=work))
    assert result["format"] == "modern" and result["steps"]
    assert result["entryFile"] in result["sources"]
    assert any("Person" in str(step["heap"]) for step in result["steps"])

    reference = (ROOT / "docs/reference/output.md").read_text()
    source = block(reference, "source") + "\n"
    module = runpy.run_path(str(ROOT / "examples/verify.py"))
    for fmt in ("modern", "pytutor"):
        result = json.loads(trace("trace", "--format", fmt, "--all-breakpoints", source=source))
        expected = json.loads(block(reference, fmt))
        equal(
            module["render"](module["normalize"](result)),
            module["render"](module["normalize"](expected)),
            f"{fmt} JSON example",
        )
    envelope = json.loads(
        trace(
            "trace",
            "--format",
            "modern",
            "--all-breakpoints",
            "--result-envelope",
            "--timeout-ms",
            "5000",
            source=source,
        )
    )
    assert envelope["schemaVersion"] == 1 and envelope["status"] == "completed"
    assert envelope["complete"] and envelope["trace"]["steps"]
    assert envelope["limits"]["timeoutMillis"] == 5000
    assert envelope["limits"]["snapshots"] == 0, "Envelope omitted limits must match docs"

    batch = (ROOT / "docs/reference/batch.md").read_text()
    match = re.search(r"```json\n(.*?)\n```", batch, re.S)
    if match is None:
        raise ValueError("batch: missing JSON code block")
    request = match.group(1)
    response = json.loads(trace("batch-trace", source=request + "\n"))
    assert response["id"] == "hello" and response["result"]["status"] == "completed"


def main_locals(step: dict[str, Any]) -> dict[str, Any]:
    """Find named main-frame locals without assuming stack order or object identities."""
    return {
        item["name"]: item for frame in step["callStack"] if frame["methodName"] == "main" for item in frame["locals"]
    }


def cumulative_output(steps: list[dict[str, Any]], expected: str) -> None:
    """Require the documented final output and monotonic captured stdout prefixes."""
    assert steps[-1]["stdout"] == expected, "Unexpected final guest output"
    assert all(after["stdout"].startswith(before["stdout"]) for before, after in zip(steps, steps[1:])), (
        "Captured stdout must accumulate"
    )


def check_reading(result: dict[str, Any], source: str) -> None:
    """Compare the reading guide's excerpts with assignment and reference evidence."""
    reading = (ROOT / "docs/tutorials/read-a-trace.md").read_text()
    steps = result["steps"]
    assignment = next(
        number for number, line in enumerate(source.splitlines(), 1) if line.strip() == "int answer = box.value;"
    )
    before_index = next(
        index for index, step in enumerate(steps) if step["line"] == assignment and "box" in main_locals(step)
    )
    before = steps[before_index]
    after = next(step for step in steps[before_index + 1 :] if "answer" in main_locals(step))
    printing = next(
        number for number, line in enumerate(source.splitlines(), 1) if line.strip() == "System.out.println(answer);"
    )
    main_exit = max(number for number, line in enumerate(source.splitlines(), 1) if line == "    }")
    assert before["file"] == after["file"] == steps[-1]["file"] == "Main.java"
    assert after["line"] == printing and steps[-1]["line"] == main_exit and steps[-1]["method"] == "main"
    assert "answer" not in main_locals(before), "Assignment-stop local must not be initialized yet"
    locals_by_name = main_locals(after)
    assert locals_by_name["answer"] == json.loads(block(reading, "reading-answer")), "Reading local excerpt differs"
    assert main_locals(before)["box"]["value"] == locals_by_name["box"]["value"], "Box reference changed"
    identity = str(locals_by_name["box"]["value"]["ref"])
    box = after["heap"][identity]
    assert box["kind"] == "object" and box["type"] == "Main$Box"
    field = next(item for item in box["fields"] if item["name"] == "value")
    assert field == json.loads(block(reading, "reading-field")), "Reading field excerpt differs"
    assert field["value"] == locals_by_name["answer"]["value"] == 7
    assert before["stdout"] == after["stdout"] == "", "Print must follow the copied assignment"
    cumulative_output(steps, "7\n")


def check_loop(work: Path) -> None:
    """Run all loop capture modes and verify repeated visits without pinning step indices."""
    tutorial = (ROOT / "docs/tutorials/trace-a-loop.md").read_text()
    source = block(tutorial, "loop-source") + "\n"
    (work / "Loop.java").write_text(source, encoding="utf-8")
    results = {}
    for name in ("locations", "latest", "accumulated", "chronological"):
        run(["sh", "-eu", "-c", block(tutorial, "loop-" + name)], cwd=work)
        results[name] = json.loads((work / f"{name}.json").read_text())
    line = next(
        number for number, text in enumerate(source.splitlines(), 1) if text.strip() == "System.out.println(i);"
    )
    location = next(item for item in results["locations"] if item["lineNumber"] == line)
    assert location["validBreakpoint"] and location["lineContent"] == source.splitlines()[line - 1]
    for name in ("latest", "accumulated", "chronological"):
        result = results[name]
        assert result["format"] == "modern" and result["entryFile"] == "Loop.java"
        assert result["sources"]["Loop.java"] == source
    latest = results["latest"]["breakpoints"][str(line)]
    accumulated = results["accumulated"]["breakpoints"][str(line)]
    chronological = results["chronological"]["steps"]
    assert isinstance(latest, dict) and isinstance(accumulated, list)
    assert main_locals(latest)["i"]["value"] == 2, "Latest breakpoint must retain the last loop visit"
    assert [main_locals(step)["i"]["value"] for step in accumulated] == [0, 1, 2], "Missing accumulated loop visit"
    visits = [step for step in chronological if step["line"] == line]
    assert [main_locals(step)["i"]["value"] for step in visits] == [0, 1, 2], "Missing chronological loop visit"
    assert [step["stdout"] for step in visits] == ["", "0\n", "0\n1\n"], "Loop stops precede their print"
    main_exit = max(number for number, text in enumerate(source.splitlines(), 1) if text == "    }")
    final = chronological[-1]
    assert final["line"] == main_exit and final["file"] == "Loop.java" and final["method"] == "main"
    assert "i" not in main_locals(final), "Loop variable must be out of scope at main exit"
    cumulative_output([latest], "0\n1\n2\n")
    cumulative_output(accumulated, "0\n1\n2\n")
    cumulative_output(chronological, "0\n1\n2\n")


def check_shared_references(work: Path) -> None:
    """Verify reference copying and shared mutation at the documented source checkpoints."""
    tutorial = (ROOT / "docs/tutorials/trace-shared-references.md").read_text()
    source = block(tutorial, "shared-source") + "\n"
    filename = "SharedReferences.java"
    (work / filename).write_text(source, encoding="utf-8")
    run(["sh", "-eu", "-c", block(tutorial, "shared-command")], cwd=work)
    result = json.loads((work / "shared-references.json").read_text())
    assert result["format"] == "modern" and result["entryFile"] == filename
    assert result["sources"][filename] == source, "Shared-reference source differs from the tutorial"
    steps = result["steps"]
    checkpoints = []
    for expected_line, statement in (
        (7, "Box alias = box;"),
        (9, "alias.value = 9;"),
        (10, "System.out.println(box.value);"),
    ):
        line = next(number for number, text in enumerate(source.splitlines(), 1) if text.strip() == statement)
        assert line == expected_line, "Shared-reference teaching line numbers differ from the source"
        index = next(
            index
            for index, step in enumerate(steps)
            if step["file"] == filename and step["line"] == line and step["method"] == "main"
        )
        checkpoints.append(index)
    before_index, copied_index, mutated_index = checkpoints
    assert before_index < copied_index < mutated_index, "Shared-reference checkpoints must follow source order"
    before = main_locals(steps[before_index])
    assert "alias" not in before and "copied" not in before, "Alias and copy must not be initialized yet"
    reference = before["box"]["value"]
    for index, expected in ((before_index, 7), (copied_index, 7), (mutated_index, 9)):
        step = steps[index]
        locals_by_name = main_locals(step)
        assert locals_by_name["box"]["value"] == reference, "The original Box reference must stay unchanged"
        box = step["heap"][str(reference["ref"])]
        assert box["kind"] == "object" and box["type"] == "SharedReferences$Box", "Expected the shared Box object"
        field = next(item for item in box["fields"] if item["name"] == "value")
        assert field["type"] == "int" and field["value"] == expected, "Shared field changed at the wrong checkpoint"
        if index != before_index:
            assert locals_by_name["alias"]["value"] == reference, "Box and alias must refer to the same object"
            copied = locals_by_name["copied"]
            assert copied["type"] == "int" and copied["value"] == 7, "The primitive copy must remain 7"
    assert all(step["stdout"] == "" for step in steps[: mutated_index + 1]), "Shared mutation must precede printing"
    main_exit = max(number for number, text in enumerate(source.splitlines(), 1) if text == "    }")
    assert main_exit == 13, "Shared-reference main-exit line differs from the tutorial"
    final = steps[-1]
    assert final["file"] == filename and final["line"] == main_exit and final["method"] == "main"
    # Guest println uses the guest platform's newline; the example describes the same three lines.
    output = [{"stdout": step["stdout"].replace("\r\n", "\n")} for step in steps]
    expected_output = block(tutorial, "shared-output") + "\n"
    assert expected_output == "9\n9\n7\n", "Shared-reference output example must show 9, 9, and 7"
    cumulative_output(output, expected_output)


def check_recipes(work: Path) -> None:
    """Execute source, input, dependency, presentation, and bounded-result recipes."""
    guide = (ROOT / "docs/guides/tracing.md").read_text()
    for marker, filename in (
        ("snapshots-source", "SnapshotDemo.java"),
        ("input-source", "Greeting.java"),
        ("input-text", "input.txt"),
        ("multifile-source", "submission.txt"),
        ("dependency-source", "Helper.java"),
        ("dependency-main", "UseHelper.java"),
    ):
        (work / filename).write_text(block(guide, "recipe-" + marker) + "\n", encoding="utf-8")
    run(["sh", "-eu", "-c", block(guide, "recipe-dependency-build")], cwd=work)
    outputs = {}
    for name, filename in (
        ("final", "final-trace.json"),
        ("snapshots", "snapshots.json"),
        ("input", "input-trace.json"),
        ("multifile", "multi-trace.json"),
        ("dependency", "dependency-trace.json"),
        ("presentation", "presentation-trace.json"),
        ("partial", "partial-result.json"),
    ):
        run(
            ["sh", "-eu", "-c", block(guide, "recipe-" + name + "-command")],
            cwd=work,
            expected_status=3 if name == "partial" else 0,
        )
        outputs[name] = json.loads((work / filename).read_text())
    for name, filename, output in (
        ("final", "SnapshotDemo.java", "8\n"),
        ("snapshots", "SnapshotDemo.java", "8\n"),
        ("input", "Greeting.java", "Hello, Ada!\n"),
        ("dependency", "UseHelper.java", "42\n"),
        ("presentation", "SnapshotDemo.java", "8\n"),
    ):
        result = outputs[name]
        assert result["format"] == "modern" and result["entryFile"] == filename
        assert result["sources"][filename] == (work / filename).read_text()
        cumulative_output(result["steps"], output)
    assert len(outputs["final"]["steps"]) == 1, "Default capture must retain only main exit"
    assert main_locals(outputs["final"]["steps"][0])["value"]["value"] == 8
    for name in ("snapshots", "presentation"):
        values = [main_locals(step)["value"] for step in outputs[name]["steps"] if "value" in main_locals(step)]
        assert {item["value"] for item in values} == {7, 8}, "Snapshot recipe must show the increment"
    presentation = outputs["presentation"]["steps"]
    assert all("args" not in main_locals(step) for step in presentation), "Main args were not removed"
    for step in presentation:
        for item in main_locals(step).values():
            assert item["binary"] == {"type": "int", "encoding": "twos-complement", "bits": f"{item['value']:032b}"}
    provided = (work / "input.txt").read_text()
    assert outputs["input"]["stdin"] == provided
    assert outputs["input"]["steps"][-1]["stdinConsumed"] == provided
    bundled = outputs["multifile"]
    assert bundled["format"] == "modern" and bundled["entryFile"] == "demo/Main.java"
    assert set(bundled["sources"]) == {"demo/Helper.java", "demo/Main.java"}
    submitted = dict(
        re.findall(
            r"^// --- ([^\n]+) ---\n(.*?)(?=^// --- |\Z)",
            (work / "submission.txt").read_text(),
            re.M | re.S,
        )
    )
    assert bundled["sources"] == submitted, "Bundled source differs from the documented submission"
    cumulative_output(bundled["steps"], "42\n")
    assert set(outputs["dependency"]["sources"]) == {"UseHelper.java"}
    assert all(step["file"] == "UseHelper.java" for step in outputs["dependency"]["steps"])
    partial = outputs["partial"]
    assert partial["schemaVersion"] == 1 and partial["status"] == "stopped" and not partial["complete"]
    assert partial["stopReason"] == "snapshot_limit" and len(partial["trace"]["steps"]) == 1
    assert partial["limits"]["snapshots"] == 1
    assert set(partial["limits"]) == {
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
    }
    assert all(value > 0 for value in partial["limits"].values()), "The partial-result recipe must set every budget"


def check_jdk(work: Path) -> None:
    """Exercise the Bash JDK setup in one session with spaces and conflicting prior tool selection."""
    guide = (ROOT / "docs/guides/installation.md").read_text()
    settings = run(["sh", "-eu", "-c", "java -XshowSettings:properties -version 2>&1"])
    match = re.search(r"^\s*java\.home\s*=\s*(.+)$", settings, re.M)
    if match is None:
        raise ValueError("Cannot discover the running JDK's java.home")
    home = Path(match.group(1)).resolve()
    assert all(os.access(home / "bin" / tool, os.X_OK) for tool in ("java", "javac")), (
        "A full executable JDK is required"
    )
    selected = work.parent / "selected JDK with spaces"
    selected.symlink_to(home, target_is_directory=True)
    previous = work.parent / "previous JAVA_HOME"
    conflicting = work.parent / "conflicting PATH"
    for directory in (previous / "bin", conflicting):
        directory.mkdir(parents=True)
        for tool in ("java", "javac"):
            path = directory / tool
            path.write_text(f"#!/bin/sh\n[ \"$1\" = --version ] || exit 19\nprintf '%s\\n' '{tool} 1.0 conflicting'\n")
            path.chmod(0o755)
    commands = {stage: block(guide, f"jdk-{stage}-bash") for stage in ("inspect", "select", "verify", "tracer")}
    placeholder = "'/path/to/your/jdk'"
    assert commands["select"].count(placeholder) == 1, "JDK selection must keep the documented placeholder"
    commands["select"] = commands["select"].replace(placeholder, shlex.quote(str(selected)))
    script = (
        f"export JAVA_HOME={shlex.quote(str(previous))}\n"
        f'export PATH={shlex.quote(str(conflicting))}:"$PATH"\nhash -r\nhash java javac\n'
    )
    for stage, command in commands.items():
        script += f"{{\n{command}\n}} > jdk-{stage}.out\n"
        if stage == "select":
            script += (
                f'[[ "$JAVA_HOME" == {shlex.quote(str(selected))} ]]\n'
                '[[ "$(command -v java)" == "$JAVA_HOME/bin/java" ]]\n'
                '[[ "$(command -v javac)" == "$JAVA_HOME/bin/javac" ]]\n'
                'bash -c \'[[ "$JAVA_HOME/bin/java" == "$(command -v java)" ]]\'\n'
            )
    for tool in ("java", "javac"):
        script += f'"$JAVA_HOME/bin/{tool}" --version > expected-{tool}.out\n'
    script += '"$JAVA_HOME/bin/java" -jar code-tracer.jar --version > expected-tracer.out\n'
    run(["bash", "--noprofile", "--norc", "-euo", "pipefail", "-c", script], cwd=work)
    inspected = (work / "jdk-inspect.out").read_text()
    assert str(previous) in inspected and all(str(conflicting / tool) in inspected for tool in ("java", "javac")), (
        "JDK inspection must show current selection"
    )
    verified = (work / "jdk-verify.out").read_text()
    for tool in ("java", "javac"):
        expected = (work / f"expected-{tool}.out").read_text().strip()
        assert expected and verified.count(expected) == 2, "Verify explicit and PATH Java tool versions"
        assert str(selected / "bin" / tool) in verified, "Verify selected Java executable paths"
    expected = (work / "expected-tracer.out").read_text()
    assert expected.startswith("code-tracer "), "Selected Java must run the packaged tracer"
    equal((work / "jdk-tracer.out").read_text(), expected * 2, "Explicit and PATH tracer installation checkpoints")


def check_tutorials() -> None:
    """Run reader workflows using only an extracted binary distribution and documented inputs."""
    first = (ROOT / "docs/tutorials/first-trace.md").read_text()
    java = (ROOT / "docs/tutorials/java-integration.md").read_text()
    batch = (ROOT / "docs/tutorials/batch-jobs.md").read_text()
    with tempfile.TemporaryDirectory(prefix="tracer docs ") as directory:
        with zipfile.ZipFile(ROOT / "target/code-tracer-bin.zip") as archive:
            archive.extractall(directory)
        work = Path(directory) / "code-tracer"
        check_jdk(work)
        (work / "Main.java").write_text(block(first, "first-source") + "\n", encoding="utf-8")
        run(["sh", "-eu", "-c", block(first, "first-command")], cwd=work)
        result = json.loads((work / "trace.json").read_text())
        assert result["format"] == "modern" and result["entryFile"] == "Main.java"
        assert result["sources"]["Main.java"] == (work / "Main.java").read_text()
        check_reading(result, (work / "Main.java").read_text())
        check_shared_references(work)
        check_loop(work)
        check_recipes(work)

        (work / "TraceExample.java").write_text(block(java, "java-source") + "\n", encoding="utf-8")
        run(["sh", "-eu", "-c", block(java, "java-compile")], cwd=work)
        outcome = json.loads(run(["sh", "-eu", "-c", block(java, "java-run")], cwd=work))
        assert outcome["status"] == "completed" and outcome["complete"]
        assert outcome["stdout"] == "hello\n" and outcome["trace"]

        (work / "jobs.ndjson").write_text(block(batch, "batch-jobs") + "\n", encoding="utf-8")
        run(["sh", "-eu", "-c", block(batch, "batch-command")], cwd=work)
        responses = [json.loads(line) for line in (work / "results.ndjson").read_text().splitlines()]
        assert [response["id"] for response in responses] == ["hello", "broken"]
        good, bad = (response["result"] for response in responses)
        assert good["status"] == "completed" and good["complete"] and good["stdout"] == "42\n"
        assert bad["status"] == "failed" and not bad["complete"] and bad["diagnostics"]


def main() -> None:
    """Run the command-line workflow."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--update-cli", action="store_true")
    args = parser.parse_args()
    if not JAR.is_file() or not (ROOT / "target/code-tracer-bin.zip").is_file():
        raise SystemExit("Build the JAR first (including the binary ZIP): ./mvnw -B -ntp package -DskipTests")
    generated = cli_reference()
    path = ROOT / "docs/reference/cli.md"
    reference = "" if args.update_cli and not path.exists() else path.read_text(encoding="utf-8", newline="")
    existing, manual = cli_sections(reference)
    if args.update_cli:
        path.write_text(generated + manual, encoding="utf-8", newline="")
    else:
        equal(generated, existing.replace("\r\n", "\n").replace("\r", "\n"), str(path.relative_to(ROOT)))
    check_links()
    check_examples()
    check_tutorials()
    print("Documentation links, CLI, quickstart, JSON examples, and batch request passed. Packaged tutorials passed.")


if __name__ == "__main__":
    main()
