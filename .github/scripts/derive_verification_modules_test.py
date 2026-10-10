"""Exercise the CLI used by Services CI, including its shared-input skip condition."""
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).with_name("derive-verification-modules.py")


class VerificationModuleSelectorTest(unittest.TestCase):
    def test_shared_inputs_select_the_whole_gradle_fleet(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            for name in ("openbank-z", "openbank-a", "openbank-admin-ui"):
                (root / name).mkdir()
            for name in ("openbank-z", "openbank-a"):
                (root / name / "build.gradle.kts").touch()
            for path in (
                "settings.gradle.kts", "build.gradle.kts", "gradle.properties",
                "build-logic/src/main/kotlin/openbank.dependency-vulnerability-pins.gradle.kts",
                "openbank-libs/gradle/libs.versions.toml", "gradle/verification-metadata.xml",
            ):
                with self.subTest(path=path):
                    result = subprocess.run([sys.executable, str(SCRIPT)], cwd=root,
                                            input=path + "\n", text=True, capture_output=True)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    lines = dict(line.split("=", 1) for line in result.stdout.splitlines())
                    self.assertEqual(lines["verification-modules"], "openbank-a,openbank-z")
                    self.assertEqual(json.loads(lines["verification-modules-json"]),
                                     ["openbank-a", "openbank-z"])

    def test_selector_regression_fixtures(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "--self-test"],
                                text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
