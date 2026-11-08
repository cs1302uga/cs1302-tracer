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


# This substitutes only the external Maven renderer process. Python staging,
# provenance, channel assembly and HTML validation run through production code.
FAKE_SITE = r"""
import os, re, shutil, sys
from pathlib import Path
if os.environ.get('DOCS_TEST_FAIL_SITE'):
    sys.exit(9)
if os.environ.get('DOCS_TEST_MUTATE_SOURCE'):
    Path('docs/index.md').write_text('# Source changed during generation')
stage = Path(sys.argv[sys.argv.index('-f') + 1]).parent
source = stage / 'src/site'
output = stage / 'target/site'
output.mkdir(parents=True, exist_ok=True)
shutil.copytree(source / 'resources', output, dirs_exist_ok=True)
for path in (source / 'markdown').rglob('*.md'):
    target = output / path.relative_to(source / 'markdown').with_suffix('.html')
    target.parent.mkdir(parents=True, exist_ok=True)
    title = re.search(r'^# (.+)$', path.read_text(), re.M)
    text = '<h1>' + title[1] + '</h1>' if title else '<p>Plain content</p>'
    if os.environ.get('BAD_SITE'):
        text += '<a href="missing.html">Broken</a>'
    target.write_text('<html data-supa-project-version="1" data-supa-relative-path=".">'
                      '<head><title>Documentation</title><link rel="canonical" href="old.html"></head><body>'
                      '<span class="supa-version-badge badge">1</span>'
                      '<nav class="supa-sidebar-tree" aria-label="Site navigation"></nav>'
                      '<main id="supa-main-content">' + text + '</main></body></html>')
sys.exit(0)
"""
