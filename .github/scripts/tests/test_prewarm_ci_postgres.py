#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Offline behavior checks for the shared PostgreSQL preparation step."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "prewarm-ci-postgres.sh"


class PostgresPreparationTest(unittest.TestCase):
    def run_case(self, mode, source=None, scanner_failure=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            module = root / "openbank-fixture"
            module.mkdir()
            if source is not None:
                tests = module / "src/test/kotlin"
                tests.mkdir(parents=True)
                (tests / "Fixture.kt").write_text(source)
            commands = root / "commands"
            docker = root / "docker"
            docker.write_text("""#!/bin/bash
printf '%s\n' "$*" >> "$COMMANDS"
case "$MODE:$1:$*" in
  cached_mirror:image:*mirror.gcr.io*) exit 0;;
  cached_upstream:image:*postgres:*) [[ "$*" != *mirror.gcr.io* ]]; exit $?;;
  mirror_pull:pull:*mirror.gcr.io*) exit 0;;
  fallback:pull:*) [[ "$*" != *mirror.gcr.io* ]]; exit $?;;
  *:tag:*) exit 0;;
  *) exit 1;;
esac
""")
            docker.chmod(0o755)
            timeout = root / "timeout"
            timeout.write_text("""#!/bin/bash
[[ "$1" == "90s" ]] || exit 2
[[ "$MODE" != "timeouts" ]] || exit 124
shift
exec "$@"
""")
            timeout.chmod(0o755)
            if scanner_failure:
                scanner = root / "grep"
                scanner.write_text("#!/bin/bash\nexit 2\n")
                scanner.chmod(0o755)
            env = dict(os.environ, MODE=mode, COMMANDS=str(commands))
            env["PATH"] = str(root) + os.pathsep + env["PATH"]
            result = subprocess.run(["bash", str(SCRIPT), "openbank-fixture"], cwd=root,
                                    env=env, capture_output=True, text=True)
            calls = commands.read_text().splitlines() if commands.exists() else []
            return result.returncode, calls

    def test_modules_without_test_sources_do_not_touch_docker(self):
        self.assertEqual((0, []), self.run_case("both_fail"))

    def test_unrelated_tests_do_not_touch_docker(self):
        self.assertEqual((0, []), self.run_case("both_fail", "class Other"))

    def test_cache_and_registry_paths(self):
        for mode in ("cached_mirror", "cached_upstream", "mirror_pull", "fallback"):
            with self.subTest(mode=mode):
                code, calls = self.run_case(mode, 'const val IMAGE = "postgres:18.6-alpine"')
                self.assertEqual(0, code)
                self.assertTrue(calls[-1].startswith("tag "))
                self.assertTrue(calls[-1].endswith(" postgres:18.6-alpine"))
                if mode.startswith("cached"):
                    self.assertFalse(any(call.startswith("pull ") for call in calls))

    def test_shared_resources_are_recognized(self):
        code, _ = self.run_case("cached_mirror", "import com.openbank.libs.testing.containers.PostgresTestResource")
        self.assertEqual(0, code)

    def test_both_registries_fail_closed(self):
        code, calls = self.run_case("both_fail", 'val image = "postgres:18.6-alpine"')
        self.assertEqual(1, code)
        self.assertEqual(2, sum(call.startswith("pull ") for call in calls))
        self.assertFalse(any(call.startswith("tag ") for call in calls))

    def test_timeouts_fail_closed(self):
        code, _ = self.run_case("timeouts", 'val image = "postgres:18.6-alpine"')
        self.assertEqual(1, code)

    def test_scanner_errors_are_not_an_empty_dependency_set(self):
        code, calls = self.run_case("both_fail", "class Other", scanner_failure=True)
        self.assertEqual((2, []), (code, calls))


if __name__ == "__main__":
    unittest.main()
