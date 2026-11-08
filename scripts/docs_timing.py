#!/usr/bin/env python3
"""Record documentation subprocess stages without replacing their validation gates."""

import json
from contextlib import contextmanager
from datetime import UTC, datetime
from time import perf_counter
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from collections.abc import Generator
    from pathlib import Path


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
