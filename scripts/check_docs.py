#!/usr/bin/env python3
"""Check executable documentation; --update-cli refreshes CLI help only."""

import argparse
import difflib
import json
import os
import re
import runpy
import signal
import subprocess
import tempfile
import zipfile
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "target/code-tracer.jar"


def run(args: list[str], source: str | None = None, cwd: Path = ROOT, timeout: float = 40) -> str:
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
    if process.returncode:
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
    section = text.split(f"<!-- {marker}:start -->", 1)[1].split(f"<!-- {marker}:end -->", 1)[0]
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


def check_tutorials() -> None:
    """Run reader workflows using only an extracted binary distribution and documented inputs."""
    first = (ROOT / "docs/tutorials/first-trace.md").read_text()
    java = (ROOT / "docs/tutorials/java-integration.md").read_text()
    batch = (ROOT / "docs/tutorials/batch-jobs.md").read_text()
    with tempfile.TemporaryDirectory(prefix="tracer docs ") as directory:
        with zipfile.ZipFile(ROOT / "target/code-tracer-bin.zip") as archive:
            archive.extractall(directory)
        work = Path(directory) / "code-tracer"
        (work / "Main.java").write_text(block(first, "first-source") + "\n", encoding="utf-8")
        run(["sh", "-eu", "-c", block(first, "first-command")], cwd=work)
        result = json.loads((work / "trace.json").read_text())
        assert result["format"] == "modern" and result["entryFile"] == "Main.java"
        assert result["sources"]["Main.java"] == (work / "Main.java").read_text()
        step = result["steps"][-1]
        assert step["stdout"] == "7\n"
        locals_by_name = {item["name"]: item for item in step["callStack"][-1]["locals"]}
        assert locals_by_name["answer"]["value"] == 7
        identity = str(locals_by_name["box"]["value"]["ref"])
        assert step["heap"][identity]["fields"][0]["value"] == 7

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
