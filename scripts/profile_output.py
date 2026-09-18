#!/usr/bin/env python3
"""Measure output byte arrays retained by real chronological traces on JDK 21+.

Uses a startup instrumentation agent and identity-based array counting. This is
the shallow heap footprint of live output arrays, not total retained trace heap
or process RSS. Runs outside production code; never changes the tracer artifact.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import signal
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent


def positive(value):
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return number


def measure(command):
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               text=True, start_new_session=True)
    try:
        stdout, stderr = process.communicate(timeout=60)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.communicate()
        raise RuntimeError("profile exceeded outer deadline")
    if process.returncode:
        raise RuntimeError(stdout + stderr)
    return json.loads(stdout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path,
                        default=ROOT / "target/code-tracer-jar-with-dependencies.jar")
    parser.add_argument("--jdk", type=Path, required=True,
                        help="JDK home used for java, javac, and jar")
    parser.add_argument("--iterations", type=positive, nargs="+", default=[50, 100, 200])
    parser.add_argument("--chunk-bytes", type=positive, default=1024)
    parser.add_argument("--compact", action="store_true", help="measure internal shared output storage")
    args = parser.parse_args()
    artifact = args.jar.resolve(strict=True)
    java = str(args.jdk / "bin/java")
    harness = ROOT / "scripts/profile-output/OutputMemoryProfile.java"
    results = dict(platform=platform.platform(), java=subprocess.run(
        [java, "-version"], capture_output=True, text=True, check=True).stderr.strip(),
        artifactSha256=hashlib.sha256(artifact.read_bytes()).hexdigest(),
        harnessSha256=hashlib.sha256(harness.read_bytes()).hexdigest(),
        measurement="Identity-deduplicated output array capacity and shallow JVM size; "
                    "compact mode also counts capture and history bookkeeping objects",
        runs=[])
    with tempfile.TemporaryDirectory(prefix="tracer-output-profile-") as temp:
        classes = Path(temp) / "classes"
        classes.mkdir()
        manifest = Path(temp) / "MANIFEST.MF"
        manifest.write_text("Premain-Class: OutputMemoryProfile\n\n")
        agent = Path(temp) / "profile-agent.jar"
        subprocess.run([str(args.jdk / "bin/javac"), "--release", "21", "-cp", str(artifact),
                        "-d", str(classes), str(harness)], check=True)
        subprocess.run([str(args.jdk / "bin/jar"), "--create", "--file", str(agent),
                        "--manifest", str(manifest), "-C", str(classes), "."], check=True)
        for workload in ["burst", "continuous"]:
            for iterations in args.iterations:
                results["runs"].append(measure([java, "-Xmx512m", "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "-javaagent:" + str(agent),
                    "-cp", os.pathsep.join([str(agent), str(artifact)]), "OutputMemoryProfile",
                    workload, str(iterations), str(args.chunk_bytes),
                    "compact" if args.compact else "legacy"]))
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
