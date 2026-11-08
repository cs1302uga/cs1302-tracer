#!/usr/bin/env python3
"""Rehearse snapshots and publishing in a disposable tree without GitHub writes."""

import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from docs_release import maven_version

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    """Run the command-line workflow."""
    with tempfile.TemporaryDirectory(prefix="tracer-docs-rehearsal-") as temporary:
        root = Path(temporary)
        for name in ("docs", "scripts", "src", ".mvn"):
            shutil.copytree(
                ROOT / name, root / name, ignore=shutil.ignore_patterns("__pycache__", ".venv", ".uv", "releases")
            )
        shutil.copy2(ROOT / "mvnw", root / "mvnw")
        site = root / "src/site"
        (site / "versions.json").write_text("[]")
        (site / "legacy-assets.json").unlink(missing_ok=True)
        (site / "releases").mkdir()
        (site / "release-provenance.json").write_text('{"schemaVersion":1,"releases":{}}')
        (root / ".gitignore").write_text("target/\n.cache/\n")
        subprocess.run(["git", "init", "-q", str(root)], check=True)

        def run(*args: str) -> None:
            """Run a child command and reject failures."""
            subprocess.run(args, cwd=root, check=True)

        for version in ("9998.0.0", "9999.0.0"):
            pom = (ROOT / "pom.xml").read_text()
            current = maven_version(pom)
            (root / "pom.xml").write_text(
                pom.replace(f"<version>{current}</version>", f"<version>{version}</version>", 1)
            )
            run(sys.executable, "scripts/docs_release.py", "prepare")
            run(sys.executable, "scripts/docs_release.py", "check", "--tag", "v" + version)
            run("git", "add", ".")
            run(
                "git",
                "-c",
                "user.name=Docs Test",
                "-c",
                "user.email=docs@example.invalid",
                "-c",
                "commit.gpgsign=false",
                "commit",
                "-qm",
                version,
            )
            run("git", "-c", "tag.gpgSign=false", "tag", "v" + version)

        releases = root / "releases.json"
        # The newer prepared snapshot must stay private until its software release exists.
        releases.write_text(json.dumps([{"tag_name": "v9998.0.0"}]))
        run(sys.executable, "scripts/docs_release.py", "published", str(releases))
        run(
            sys.executable,
            "scripts/docs_site.py",
            "build",
            "--javadoc-source",
            "source",
            "--mode",
            "published",
            "--releases",
            str(releases),
        )
        run(sys.executable, "scripts/check_site.py")
        assert (root / "target/pages/docs/9998.0.0/index.html").exists()
        assert (root / "target/pages/docs/dev/index.html").exists()
        assert not (root / "target/pages/docs/9999.0.0").exists()
        assert not (root / "target/pages/api/9999.0.0").exists()
        assert "/9998.0.0/" in (root / "target/pages/index.html").read_text()

        releases.write_text(json.dumps([{"tag_name": "v9998.0.0"}, {"tag_name": "v9999.0.0"}]))
        run(sys.executable, "scripts/docs_release.py", "published", str(releases))
        run(
            sys.executable,
            "scripts/docs_site.py",
            "build",
            "--javadoc-source",
            "source",
            "--mode",
            "published",
            "--releases",
            str(releases),
        )
        run(sys.executable, "scripts/check_site.py")
        assert (root / "target/pages/docs/9998.0.0/index.html").exists()
        assert (root / "target/pages/docs/9999.0.0/index.html").exists()
        assert (root / "target/pages/api/9998.0.0/index.html").exists()
        assert (root / "target/pages/api/9999.0.0/index.html").exists()
        assert "/9999.0.0/" in (root / "target/pages/index.html").read_text()
        # Retry selection is deterministic and retains both release versions.
        selected = (root / "target/docsite/published-versions.json").read_text()
        run(sys.executable, "scripts/docs_release.py", "published", str(releases))
        assert selected == (root / "target/docsite/published-versions.json").read_text()
        # A published release must never fall back to the current working source,
        # even when its version matches the current POM. Failed generation also
        # leaves the previous complete API output available for inspection.
        api_index = root / "target/generated-api/api/9999.0.0/index.html"
        previous_index = api_index.read_bytes()
        run("git", "tag", "-d", "v9999.0.0")
        missing = subprocess.run(
            [sys.executable, "scripts/docs_site.py", "api", "--mode", "published", "--releases", str(releases)],
            cwd=root,
            text=True,
            capture_output=True,
        )
        assert missing.returncode != 0 and "Missing release tag for 9999.0.0" in missing.stderr
        assert api_index.read_bytes() == previous_index
        print(
            "Release rehearsal passed: hidden pending snapshot, stable/latest channels, "
            "retained guides, retry, and missing tag."
        )


if __name__ == "__main__":
    main()
