"""Check the managed runtime boundary used by documentation CI."""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from tests.support import SCRIPTS


class ManagedRuntimeTests(unittest.TestCase):
    """Keep child commands on the declared Python runtime."""

    def test_wrapped_build_command_inherits_managed_python(self) -> None:
        """Override an incompatible ambient interpreter for npm-style child commands."""
        with tempfile.TemporaryDirectory() as directory:
            binaries = Path(directory)
            for name, body in {
                "python3": "#!/bin/sh\necho 'ambient Python selected' >&2\nexit 99\n",
                "npm": '#!/bin/sh\nexec python3 "$@"\n',
            }.items():
                path = binaries / name
                path.write_text(body)
                path.chmod(0o755)
            env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ["PATH"], UVW_GIT_COMMIT="never")
            arguments = ["npm", "-c", "import sys; print(sys.version_info >= (3, 15))"]
            ambient = subprocess.run(arguments, env=env, capture_output=True, text=True, timeout=10)
            self.assertEqual(ambient.returncode, 99)
            managed = subprocess.run(
                [str(SCRIPTS / "uv"), "run", "--locked", *arguments],
                cwd=SCRIPTS.parent,
                env=env,
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(managed.returncode, 0, managed.stderr)
            self.assertEqual(managed.stdout, "True\n")
