"""Exercise the CI aggregate gate with real shell subprocesses."""

import os
import subprocess
import unittest

from tests.support import SCRIPTS

RESULTS = {
    "JAVA_RESULT": "Java",
    "DOCUMENTATION_RESULT": "Documentation",
    "SCRIPTS_QUALITY_RESULT": "Scripts quality",
    "WINDOWS_WRAPPER_RESULT": "Windows packaging and launchers",
}


class RegressionGatesTests(unittest.TestCase):
    """Require literal success from every CI group and reject incomplete evidence."""

    def run_gate(self, inputs: dict[str, str]) -> subprocess.CompletedProcess[str]:
        """Run the executable gate with only the supplied group results."""
        environment = {key: value for key, value in os.environ.items() if key not in RESULTS}
        environment.update(inputs)
        return subprocess.run(
            [str(SCRIPTS.parent / ".github/scripts/check-regression-gates.sh")],
            env=environment,
            capture_output=True,
            text=True,
            check=False,
            timeout=10,
        )

    def test_all_successful_groups(self) -> None:
        """Accept a complete successful run and print its success diagnostic."""
        result = self.run_gate(dict.fromkeys(RESULTS, "success"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "All regression gate groups succeeded.\n")
        self.assertEqual(result.stderr, "")

    def test_each_unsuccessful_group(self) -> None:
        """Reject empty, failed, cancelled, skipped, and unknown results in every group."""
        for variable, label in RESULTS.items():
            for value in ("", "failure", "cancelled", "skipped", "unknown", "Success", " success", "success\n"):
                with self.subTest(variable=variable, value=value):
                    inputs = dict.fromkeys(RESULTS, "success")
                    inputs[variable] = value
                    result = self.run_gate(inputs)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(f"{label} did not succeed", result.stderr)
                    self.assertEqual(result.stdout, "")

    def test_each_missing_group(self) -> None:
        """Reject an absent environment variable even when all other groups succeeded."""
        for variable, label in RESULTS.items():
            with self.subTest(variable=variable):
                inputs = dict.fromkeys(RESULTS, "success")
                inputs.pop(variable)
                result = self.run_gate(inputs)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(f"{label} did not succeed (result: missing)", result.stderr)
                self.assertEqual(result.stdout, "")

    def test_all_missing_groups(self) -> None:
        """Fail closed with a diagnostic for every missing group."""
        result = self.run_gate({})
        self.assertNotEqual(result.returncode, 0)
        for label in RESULTS.values():
            self.assertIn(f"{label} did not succeed (result: missing)", result.stderr)
        self.assertEqual(result.stdout, "")

    def test_multiple_unsuccessful_groups(self) -> None:
        """Report every unsuccessful dependency instead of stopping at the first one."""
        result = self.run_gate(dict(zip(RESULTS, ("failure", "cancelled", "skipped", "unknown"), strict=True)))
        self.assertNotEqual(result.returncode, 0)
        for label in RESULTS.values():
            self.assertIn(f"{label} did not succeed", result.stderr)
        self.assertEqual(result.stdout, "")
