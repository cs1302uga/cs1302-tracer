"""Test HTTP checks and preview serving against local sockets."""

import json
import select
import signal
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

from scripts import check_external_links

from tests.support import copy_scripts, invoke


class LinkHandler(BaseHTTPRequestHandler):
    """Return deterministic HTTP success and failure responses."""

    def do_GET(self) -> None:
        """Serve only the known test link."""
        self.send_response(200 if self.path == "/ok" else 404)
        self.end_headers()
        self.wfile.write(b"linked content")

    def log_message(self, format: str, *args: object) -> None:
        """Keep test server diagnostics quiet."""


class HttpToolsTests(unittest.TestCase):
    """Verify user-visible HTTP outcomes and server shutdown."""

    def test_external_links_are_deduplicated_and_failures_set_exit_status(self) -> None:
        """Write a report for reachable and broken links without remote network access."""
        with ThreadingHTTPServer(("127.0.0.1", 0), LinkHandler) as server:
            thread = threading.Thread(target=server.serve_forever)
            thread.start()
            try:
                base = f"http://127.0.0.1:{server.server_port}"
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    copy_scripts(root)
                    (root / "docs").mkdir()
                    (root / "README.md").write_text(f"[OK]({base}/ok#first) [Again]({base}/ok#second)")
                    (root / "HACKING.md").write_text("")
                    result = invoke(root, "check_external_links.py")
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(result.stdout.count(f"| {base}/ok | 200 |"), 1)
                    (root / "docs/broken.md").write_text(f"[Broken]({base}/missing)")
                    result = invoke(root, "check_external_links.py")
                    self.assertEqual(result.returncode, 1)
                    report = (root / "external-links-report.md").read_text()
                    self.assertEqual(report, result.stdout)
                    self.assertIn(f"| {base}/missing | FAIL |", report)
                    self.assertIn("404", report)
            finally:
                server.shutdown()
                thread.join(timeout=5)

    def test_transport_failures_return_readable_details(self) -> None:
        """Preserve timeout and operating-system failure details at the network boundary."""
        for error in (TimeoutError("slow response"), OSError("offline"), urllib.error.URLError("no route")):
            with self.subTest(error=error), patch("urllib.request.urlopen", side_effect=error):
                url, status, detail = check_external_links.check("https://example.test")
                self.assertEqual(url, "https://example.test")
                self.assertEqual(status, "FAIL")
                self.assertIn(str(error), detail)

    def test_preview_serves_project_paths_and_stops_cleanly(self) -> None:
        """Serve Javadoc HTML unchanged under the project prefix and handle Ctrl-C."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            copy_scripts(root)
            missing = invoke(root, "serve_docs.py", "--port", "0")
            self.assertEqual(missing.returncode, 2)
            self.assertIn("Build the documentation site first", missing.stderr)
            build = root / "website/build"
            (build / "api").mkdir(parents=True)
            (build / "index.html").write_text("site home")
            (build / "api/Example.html").write_text("exact API page")
            (build / "release-manifest.json").write_text(json.dumps({"baseUrl": "/project/"}))
            process = subprocess.Popen(
                [sys.executable, str(root / "scripts/serve_docs.py"), "--port", "0"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
            try:
                assert process.stdout is not None
                ready, _, _ = select.select([process.stdout], [], [], 10)
                self.assertTrue(ready, "Preview server did not announce its address")
                address = process.stdout.readline().strip().removeprefix("Serving ")
                with urllib.request.urlopen(address + "api/Example.html", timeout=5) as response:
                    self.assertEqual(response.read(), b"exact API page")
                process.send_signal(signal.SIGINT)
                _, errors = process.communicate(timeout=10)
                self.assertEqual(process.returncode, 0, errors)
                self.assertNotIn("Traceback", errors)
            finally:
                if process.poll() is None:
                    process.kill()
                process.communicate(timeout=5)
