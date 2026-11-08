"""Exercise invocation-specific stage reports and timing failure boundaries."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.docs_timing import BuildTimings


class DocumentationTimingTests(unittest.TestCase):
    """Keep measurements invocation-specific and preserve external failures."""

    def test_monotonic_stages_record_failure_and_replace_old_reports(self) -> None:
        """Use a controlled clock to distinguish stage duration from total elapsed time."""
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "reports/timings.json"
            with patch("scripts.docs_timing.perf_counter", side_effect=[100, 101, 103.5, 104, 105, 108]):
                timings = BuildTimings()
                with timings.measure("cached-release") as record:
                    record["cache"] = "hit"
                with self.assertRaisesRegex(ValueError, "failed tool"):
                    with timings.measure("broken-tool"):
                        raise ValueError("failed tool")
                timings.write(report, scope="test invocation")
            actual = json.loads(report.read_text())
            self.assertEqual(actual["elapsedSeconds"], 8)
            self.assertEqual(actual["stages"][0]["seconds"], 2.5)
            self.assertEqual(actual["stages"][0]["cache"], "hit")
            self.assertEqual(actual["stages"][1]["status"], "failed")
            self.assertTrue(actual["startedAt"].endswith("+00:00"))
            BuildTimings().write(report)
            self.assertEqual(json.loads(report.read_text())["stages"], [])


if __name__ == "__main__":
    unittest.main()
