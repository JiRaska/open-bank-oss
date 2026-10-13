# SPDX-License-Identifier: Apache-2.0
import os
import shlex
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "ensure-admin-ui-deps.sh"


class EnsureAdminUiDepsTests(unittest.TestCase):
    def setUp(self):
        prefix = "gate deps 'quoted "
        self.temp = tempfile.TemporaryDirectory(prefix=prefix)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.scripts = self.root / ".github" / "scripts"
        self.scripts.mkdir(parents=True)
        self.ui = self.root / "openbank-admin-ui"
        self.ui.mkdir()
        self.fake_bin = self.root / "fake-bin"
        self.fake_bin.mkdir()
        self.helper = self.scripts / "ensure-admin-ui-deps.sh"
        shutil.copyfile(SCRIPT, self.helper)
        self.runner = self.root / "run-helper.sh"
        self.runner.write_text(
            "#!/usr/bin/env bash\n"
            "set -euo pipefail\n"
            f"source {shlex.quote(str(self.helper))}\n"
            'ensure_deps() { ensure_admin_ui_deps "unit-test"; }\n'
            "ensure_deps\n"
            ": # match the gates, which continue after the dependency helper\n"
        )
        self.runner.chmod(0o755)

    def run_with_npm(self, body):
        npm = self.fake_bin / "npm"
        npm.write_text("#!/usr/bin/env bash\n" + body + "\n")
        npm.chmod(0o755)
        env = os.environ.copy()
        env["PATH"] = f"{self.fake_bin}{os.pathsep}{env['PATH']}"
        return subprocess.run(
            ["bash", str(self.runner)],
            env=env,
            capture_output=True,
            text=True,
            check=False,
            timeout=10,
        )

    def test_npm_failure_preserves_error_and_releases_lock(self):
        result = self.run_with_npm('echo "simulated npm failure" >&2\nexit 42')

        self.assertEqual(result.returncode, 42, result.stderr)
        self.assertNotIn("unbound variable", result.stderr)
        self.assertFalse((self.ui / ".node_modules.gate-lock").exists())
        self.assertFalse((self.ui / "node_modules" / ".openbank-gate-deps-ok").exists())

    def test_success_writes_marker_and_releases_lock(self):
        result = self.run_with_npm("mkdir -p node_modules\nexit 0")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.ui / "node_modules" / ".openbank-gate-deps-ok").is_file())
        self.assertFalse((self.ui / ".node_modules.gate-lock").exists())


if __name__ == "__main__":
    unittest.main()
