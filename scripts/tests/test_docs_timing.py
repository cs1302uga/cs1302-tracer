"""Exercise stage reports and the real timed npm subprocess boundary."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.docs_timing import BuildTimings

from tests.support import copy_scripts, executable, invoke, tool_environment


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

    def test_npm_install_reports_success_and_failure_without_stale_measurements(self) -> None:
        """Time dependency installation separately and retain a failed child exit status."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            copy_scripts(root)
            site = root / "website"
            site.mkdir()
            environment = tool_environment(root)
            executable(
                root / "bin/npm",
                """
import os, sys
from pathlib import Path
assert sys.argv[1:] == ['ci', '--no-audit', '--no-fund']
Path('invoked').write_text('npm ci')
sys.exit(int(os.environ.get('NPM_TEST_EXIT', '0')))
""",
            )
            report = site / "npm-ci-timing.json"
            for exit_status in (0, 9):
                environment["NPM_TEST_EXIT"] = str(exit_status)
                result = invoke(root, "docs_timing.py", "npm-ci", env=environment)
                self.assertEqual(result.returncode == 0, exit_status == 0, result.stderr)
                self.assertEqual((site / "invoked").read_text(), "npm ci")
                actual = json.loads(report.read_text())
                self.assertEqual(len(actual["stages"]), 1)
                stage = actual["stages"][0]
                self.assertEqual(stage["name"], "npm-ci")
                self.assertEqual(stage["status"], "passed" if exit_status == 0 else "failed")
                self.assertGreaterEqual(stage["seconds"], 0)


if __name__ == "__main__":
    unittest.main()
