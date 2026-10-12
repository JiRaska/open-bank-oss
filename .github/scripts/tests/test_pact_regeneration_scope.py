# SPDX-License-Identifier: Apache-2.0
import importlib.util
import subprocess
import sys
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "pact-regeneration-scope.py"
SPEC = importlib.util.spec_from_file_location("pact_scope", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class PactScopeTests(unittest.TestCase):
    def test_ui_only_manifest_and_source_changes_omit_jvm(self):
        self.assertFalse(MODULE.jvm_required([
            "openbank-admin-ui/package.json", "openbank-admin-ui/package-lock.json",
            "openbank-admin-ui/src/test/sample-pact.contract.test.ts",
        ]))

    def test_shared_jvm_workflow_and_unknown_inputs_keep_jvm(self):
        for path in ["pacts/consumer-provider.json", "build-logic/src/main/plugin.kt",
                     "gradle/libs.versions.toml", "gradle.properties", "settings.gradle.kts",
                     "openbank-libs/src/testFixtures/kotlin/Fixture.kt",
                     "openbank-product-catalog/src/main/Client.kt",
                     ".github/workflows/pact-drift-check.yml", "future-input"]:
            with self.subTest(path=path):
                self.assertTrue(MODULE.jvm_required(["openbank-admin-ui/package.json", path]))

    def test_empty_and_ambiguous_inventory_keeps_jvm(self):
        for paths in [[], ["openbank-admin-ui/../build.gradle.kts"],
                      ["openbank-admin-ui//package.json"], ["openbank-admin-ui/"],
                      ["openbank-admin-ui/file\nname"], ["openbank-admin-ui-other/file"]]:
            with self.subTest(paths=paths):
                self.assertTrue(MODULE.jvm_required(paths))

    def test_actual_cli_requires_complete_nul_inventory(self):
        for raw, expected in [(b"openbank-admin-ui/package.json\0", b"false\n"),
                              (b"openbank-admin-ui/package.json", b"true\n"),
                              (b"openbank-admin-ui/\xff\0", b"true\n"),
                              (b"", b"true\n")]:
            with self.subTest(raw=raw):
                result = subprocess.run([sys.executable, str(SCRIPT)], input=raw,
                                        capture_output=True, check=True)
                self.assertEqual(result.stdout, expected)


if __name__ == "__main__":
    unittest.main()
