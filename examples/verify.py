#!/usr/bin/env python3
"""Verify committed trace fixtures. Regeneration is explicit via --update."""
import argparse
import difflib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "examples" / "regression"


def normalize(document):
    """Rename only heap identities; preserve values, aliases, ordering, and metadata."""
    identities = {}

    def identity(value):
        return identities.setdefault(str(value), len(identities) + 1)

    def reference_free(value):
        if isinstance(value, list):
            return not (len(value) == 2 and value[0] == "REF") and all(
                reference_free(item) for item in value)
        if isinstance(value, dict):
            return "ref" not in value and all(reference_free(item) for item in value.values())
        return True

    def visit(value):
        if isinstance(value, list):
            if len(value) == 2 and value[0] == "REF":
                return ["REF", identity(value[1])]
            return [visit(item) for item in value]
        if not isinstance(value, dict):
            return value
        if set(value) == {"ref"}:
            return {"ref": identity(value["ref"])}
        result = {key: visit(value[key]) for key in sorted(value)
                  if key not in ("heap", "heap_attrs")}
        if "heap" in value:
            pending = dict(value["heap"])
            heap = {}
            while pending:
                reachable = [key for key in pending if str(key) in identities]
                if reachable:
                    key = min(reachable, key=lambda item: identities[str(item)])
                else:
                    # Legacy display options can leave isolated strings/empty args on the heap.
                    isolated = [key for key in pending if reference_free(pending[key])]
                    if not isolated:
                        raise ValueError("Unreachable object graph cannot be normalized safely")
                    key = min(isolated, key=lambda item: json.dumps(pending[item], sort_keys=True))
                heap[str(identity(key))] = visit(pending.pop(key))
            result["heap"] = heap
        if "heap_attrs" in value:
            result["heap_attrs"] = {str(identity(key)): visit(item)
                                    for key, item in value["heap_attrs"].items()}
        return result

    return visit(document)


def render(value):
    return json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def execute(case, jar, java):
    command = [java, "-jar", str(jar), "trace"] + case.get("options", [])
    source = None
    if "stream" in case:
        source = "".join("// --- " + path + " ---\n" +
                         (ROOT / case["directory"] / path).read_text() + "\n"
                         for path in case["stream"])
    else:
        command += ["-i", str(ROOT / case["input"])]
    process = subprocess.Popen(command, cwd=ROOT, stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               text=True, start_new_session=(os.name == "posix"))
    try:
        stdout, stderr = process.communicate(source, timeout=20)
    except subprocess.TimeoutExpired:
        if os.name == "posix":
            os.killpg(process.pid, signal.SIGKILL)
        else:
            process.kill()
        process.communicate()
        raise RuntimeError(f"{case['name']}: exceeded 20-second deadline") from None
    if process.returncode:
        raise RuntimeError(f"{case['name']}: exit {process.returncode}: {stderr}")
    return normalize(json.loads(stdout))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path,
                        default=ROOT / "target/code-tracer-jar-with-dependencies.jar")
    parser.add_argument("--java", default="java")
    parser.add_argument("--update", action="store_true",
                        help="explicitly replace expected fixtures; review the resulting diff")
    args = parser.parse_args()
    failures = []
    for case in json.loads((FIXTURES / "cases.json").read_text()):
        try:
            actual = render(execute(case, args.jar.resolve(), args.java))
            fixture = FIXTURES / (case["name"] + ".json")
            if args.update:
                fixture.write_text(actual)
            elif actual != fixture.read_text():
                diff = difflib.unified_diff(fixture.read_text().splitlines(), actual.splitlines(),
                                            fromfile=str(fixture), tofile="actual", lineterm="")
                print("\n".join(list(diff)[:100]))
                raise RuntimeError("trace differs from committed baseline")
            print("PASS", case["name"], flush=True)
        except (RuntimeError, ValueError, OSError) as error:
            failures.append(case["name"])
            print("FAIL", case["name"], str(error), flush=True)
    return bool(failures)


if __name__ == "__main__":
    sys.exit(main())
