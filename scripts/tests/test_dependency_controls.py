"""Run the shipped static disclosure controls in V8 without Node, npm or a browser stack."""

import json
import unittest
from pathlib import Path

from py_mini_racer import MiniRacer

ROOT = Path(__file__).resolve().parents[2]
HARNESS = Path(__file__).with_name("dependencies-dom.js").read_text()
SCRIPT = (ROOT / "src/site/resources/dependencies.js").read_text()


class DependencyControlsTests(unittest.TestCase):
    """Substitute only the DOM/events boundary while executing production JavaScript."""

    def test_expansion_collapse_print_and_reinitialization(self) -> None:
        """Keep each inventory independent and restore its exact pre-print disclosure states."""
        with MiniRacer() as runtime:
            runtime.eval(HARNESS + '\ndocument.readyState = "complete";\n' + SCRIPT)
            self.assertEqual(json.loads(str(runtime.eval("states()"))), [[True, False], [False, True]])
            self.assertEqual(runtime.eval("document.querySelectorAll('.dependency-controls').length"), 2)
            self.assertEqual(
                runtime.eval("groups[0].querySelector('.dependency-controls').attributes.get('aria-label')"),
                "Dependency details controls",
            )
            runtime.eval(
                "groups[0].querySelector('.dependency-controls').children[0].dispatchEvent(new Event('click'))"
            )
            self.assertEqual(json.loads(str(runtime.eval("states()"))), [[True, True], [False, True]])
            runtime.eval(
                "groups[1].querySelector('.dependency-controls').children[1].dispatchEvent(new Event('click'))"
            )
            self.assertEqual(json.loads(str(runtime.eval("states()"))), [[True, True], [False, False]])
            runtime.eval(
                "window.dispatchEvent(new Event('beforeprint')); window.dispatchEvent(new Event('beforeprint'))"
            )
            self.assertEqual(json.loads(str(runtime.eval("states()"))), [[True, True], [True, True]])
            runtime.eval("window.dispatchEvent(new Event('afterprint')); window.dispatchEvent(new Event('afterprint'))")
            self.assertEqual(json.loads(str(runtime.eval("states()"))), [[True, True], [False, False]])
            runtime.eval("window.dispatchEvent(new Event('beforeprint')); enhanceDependencies()")
            self.assertEqual(runtime.eval("document.querySelectorAll('.dependency-controls').length"), 2)
            self.assertEqual(json.loads(str(runtime.eval("states()"))), [[True, True], [False, False]])
            self.assertEqual(runtime.eval("window.listeners.get('beforeprint').size"), 1)

    def test_loading_and_empty_inventory(self) -> None:
        """Wait for DOM readiness and leave a native empty inventory without artificial controls."""
        with MiniRacer() as runtime:
            runtime.eval(HARNESS + '\ndocument.readyState = "loading";\n' + SCRIPT)
            self.assertEqual(runtime.eval("document.querySelectorAll('.dependency-controls').length"), 0)
            runtime.eval("document.dispatchEvent(new Event('DOMContentLoaded'))")
            self.assertEqual(runtime.eval("document.querySelectorAll('.dependency-controls').length"), 2)
        with MiniRacer() as runtime:
            runtime.eval(HARNESS + "\ngroups = [inventory()]; document = new Document(...groups);\n" + SCRIPT)
            self.assertEqual(runtime.eval("document.querySelectorAll('.dependency-controls').length"), 0)
            runtime.eval(
                "window.dispatchEvent(new Event('beforeprint')); window.dispatchEvent(new Event('afterprint'))"
            )

    def test_native_disclosure_print_styles_use_current_skin_variables(self) -> None:
        """Keep no-JavaScript printable content and focus styles independent of the retired skin."""
        css = (ROOT / "src/site/resources/dependencies.css").read_text()
        self.assertNotIn("--ifm-", css)
        self.assertIn("@media print", css)
        self.assertIn(".dependency-details::details-content", css)
        self.assertIn("content-visibility: visible", css)
        self.assertIn(".dependency-summary:focus-visible", css)
        self.assertIn(".documentation-version-picker", css)


if __name__ == "__main__":
    unittest.main()
