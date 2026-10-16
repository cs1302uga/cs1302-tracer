"""Real temporary projects and subprocesses for script-interface tests."""

import os
import shutil
import subprocess
import sys
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]


def copy_scripts(root: Path) -> None:
    """Copy production scripts without caches, environments, or tests."""
    destination = root / "scripts"
    destination.mkdir(exist_ok=True)
    for source in SCRIPTS.glob("*.py"):
        shutil.copy2(source, destination / source.name)


def invoke(root: Path, name: str, *args: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    """Run a script in its isolated project with captured command output."""
    return subprocess.run(
        [sys.executable, str(root / "scripts" / name), *args],
        cwd=root,
        env=env,
        capture_output=True,
        text=True,
        timeout=30,
    )


def executable(path: Path, body: str) -> None:
    """Create an executable stand-in for an external process."""
    path.write_text(f"#!{sys.executable}\n{body}")
    path.chmod(0o755)


def tool_environment(root: Path) -> dict[str, str]:
    """Prepend a project's external-tool directory to the inherited environment."""
    binaries = root / "bin"
    binaries.mkdir(exist_ok=True)
    return dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ["PATH"])
