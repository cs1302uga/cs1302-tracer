#!/usr/bin/env python3
"""Record documentation subprocess stages without replacing their validation gates."""

import argparse
import json
import subprocess
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path
from time import perf_counter
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from collections.abc import Generator


class BuildTimings:
    """Collect monotonic stage durations and their completion status for one invocation."""

    def __init__(self) -> None:
        """Start a fresh report; previous reports never contribute measurements."""
        self.started_at = datetime.now(UTC).isoformat()
        self.started = perf_counter()
        self.stages: list[dict[str, object]] = []

    @contextmanager
    def measure(self, name: str) -> Generator[dict[str, object]]:
        """Measure a stage, allowing callers to add non-sensitive result metadata."""
        started = perf_counter()
        record: dict[str, object] = {"name": name, "status": "failed"}
        try:
            yield record
            record["status"] = "passed"
        finally:
            record["seconds"] = round(perf_counter() - started, 6)
            self.stages.append(record)
            print(f"Documentation timing: {name}: {record['seconds']:.3f}s ({record['status']})", flush=True)

    def write(self, destination: Path, **metadata: object) -> None:
        """Write this invocation's report separately from the published site."""
        destination.parent.mkdir(parents=True, exist_ok=True)
        report = {
            "schemaVersion": 1,
            "startedAt": self.started_at,
            "elapsedSeconds": round(perf_counter() - self.started, 6),
            "stages": self.stages,
            **metadata,
        }
        destination.write_text(json.dumps(report, indent=2) + "\n")


def main() -> None:
    """Install the locked frontend dependencies with a separate timing report."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("npm-ci",))
    parser.parse_args()
    site = Path(__file__).resolve().parents[1] / "website"
    timings = BuildTimings()
    try:
        with timings.measure("npm-ci"):
            subprocess.run(["npm", "ci", "--no-audit", "--no-fund"], cwd=site, check=True)
    finally:
        timings.write(site / "npm-ci-timing.json", scope="npm dependency installation")


if __name__ == "__main__":
    main()
