#!/usr/bin/env python3
"""Measure fresh tracer processes; preserve semantic trace hashes for comparisons.

Peak RSS is the OS-reported maximum for the child process tree, not Java heap use.
Requires a POSIX host. Each measurement runs in a fresh Python worker so RSS from
previous cases cannot leak into later measurements. No fixtures are modified.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import resource
import signal
import statistics
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "examples"))
from verify import normalize


def source_for(workload):
    if workload == "output":
        body = 'for (int i = 0; i < 100; i++) {\nSystem.out.println("x".repeat(1000));\n}'
    elif workload == "collections":
        body = 'var list = new java.util.ArrayList<Integer>();\nfor (int i=0;i<60;i++) {\nlist.add(i);\n}'
    else:
        body = 'int sum=0;\nfor (int i=0;i<100;i++) {\nsum += i;\n}'
    source = 'public class Main {\npublic static void main(String[] args) {\n' + body + '\n}\n}\n'
    if workload == "sources":
        source += '\n'.join(f'class Extra{i} {{ java.util.List<String> value; void f(final int p) {{ int n=p; }} }}'
                            for i in range(100))
    return source


def worker(args):
    with tempfile.TemporaryDirectory(prefix="tracer-benchmark-") as temp:
        output = Path(temp) / "output.json"
        errors = Path(temp) / "errors.txt"
        source = Path(temp) / "Main.java"
        source.write_text(source_for(args.worker))
        command = [args.java, "-Xmx512m", "-jar", str(args.jar.resolve()), "trace", "-a", "-f", "modern",
                   "--result-envelope", "--timeout-ms", "45000", "-i", str(source)]
        start = time.perf_counter()
        with output.open("w") as out, errors.open("w") as err:
            process = subprocess.Popen(command, stdout=out, stderr=err, start_new_session=True)
            try:
                code = process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
                raise RuntimeError("benchmark exceeded outer deadline")
        seconds = time.perf_counter() - start
        if code:
            raise RuntimeError(output.read_text()[:2000] + errors.read_text()[:1000])
        result = json.loads(output.read_text())
        digest = hashlib.sha256(json.dumps(normalize(result["trace"]), sort_keys=True).encode()).hexdigest()
        rss = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
        rss_bytes = rss if sys.platform == "darwin" else rss * 1024
        return dict(seconds=seconds, peakRssBytes=rss_bytes, outputBytes=output.stat().st_size,
                    counters=result["counters"], traceHash=digest)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "target/code-tracer-jar-with-dependencies.jar")
    parser.add_argument("--java", default="java")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--worker", choices=["loop", "output", "collections", "sources"])
    args = parser.parse_args()
    if args.worker:
        print(json.dumps(worker(args)))
        return
    results = {"platform": platform.platform(), "java": subprocess.run(
        [args.java, "-version"], capture_output=True, text=True, check=True).stderr.strip(), "workloads": {}}
    for workload in ["loop", "output", "collections", "sources"]:
        runs = []
        for _ in range(args.runs):
            run = subprocess.run([sys.executable, __file__, "--worker", workload, "--jar", str(args.jar),
                                  "--java", args.java], capture_output=True, text=True, check=True)
            runs.append(json.loads(run.stdout))
        assert len({r["traceHash"] for r in runs}) == 1, "semantic results vary across runs"
        results["workloads"][workload] = dict(runs=runs,
            medianSeconds=statistics.median(r["seconds"] for r in runs),
            medianPeakRssBytes=statistics.median(r["peakRssBytes"] for r in runs))
        print(workload, results["workloads"][workload]["medianSeconds"], file=sys.stderr, flush=True)
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
