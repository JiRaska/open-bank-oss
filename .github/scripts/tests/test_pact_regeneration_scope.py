# SPDX-License-Identifier: Apache-2.0
import importlib.util
import shutil
import subprocess
import sys
import tempfile
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

    def test_ui_with_exact_keycloak_realm_template_cochange_omits_jvm(self):
        self.assertFalse(MODULE.jvm_required([
            "openbank-admin-ui/src/lib/auth/authOptions.ts",
            "openbank-infra/gitops/components/keycloak/realm-template.json",
        ]))

    def test_ui_module_and_changed_root_build_files_require_jvm(self):
        ui_and_realm = ["openbank-admin-ui/src/lib/auth/authOptions.ts",
                        "openbank-infra/gitops/components/keycloak/realm-template.json"]
        self.assertTrue(MODULE.jvm_required(ui_and_realm, ui_is_jvm_module=True))
        for build_file in ["openbank-admin-ui/build.gradle",
                           "openbank-admin-ui/build.gradle.kts"]:
            with self.subTest(build_file=build_file):
                self.assertTrue(MODULE.jvm_required([build_file]))

    def test_actual_cli_reads_ui_module_marker_from_its_checkout(self):
        ui_path = b"openbank-admin-ui/src/lib/auth/authOptions.ts\0"
        ui_and_realm = (ui_path +
                        b"openbank-infra/gitops/components/keycloak/realm-template.json\0")
        self.assertEqual(run_cli(ui_and_realm, ui_module_marker=False), b"false\n")
        for marker in ("build.gradle", "build.gradle.kts"):
            with self.subTest(marker=marker):
                self.assertEqual(run_cli(ui_and_realm, ui_module_marker=marker), b"true\n")
                self.assertEqual(run_cli(ui_path, ui_module_marker=marker), b"true\n")
        # A marker deletion is represented in the changed-path inventory and still fails closed.
        self.assertEqual(run_cli(b"openbank-admin-ui/build.gradle.kts\0", ui_module_marker=False),
                         b"true\n")

    def test_realm_template_alone_or_nearby_infra_changes_keep_jvm(self):
        realm = "openbank-infra/gitops/components/keycloak/realm-template.json"
        for paths in [[realm],
                      ["openbank-admin-ui/package.json",
                       "openbank-infra/gitops/components/keycloak/realm-template.json.bak"],
                      ["openbank-admin-ui/package.json",
                       "openbank-infra/gitops/components/keycloak/realm-template.json/child"],
                      ["openbank-admin-ui/package.json",
                       "openbank-infra/gitops/components/keycloak/realm-template.JSON"],
                      ["openbank-admin-ui/package.json",
                       "openbank-infra/gitops/components/keycloak/other.json"]]:
            with self.subTest(paths=paths):
                self.assertTrue(MODULE.jvm_required(paths))

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
                              (b"openbank-admin-ui/src/lib/auth/authOptions.ts\0"
                               b"openbank-infra/gitops/components/keycloak/realm-template.json\0",
                               b"false\n"),
                              (b"openbank-admin-ui/package.json\0"
                               b"openbank-infra/gitops/components/keycloak/other.json\0",
                               b"true\n"),
                              (b"openbank-admin-ui/package.json", b"true\n"),
                              (b"openbank-admin-ui/\xff\0", b"true\n"),
                              (b"", b"true\n")]:
            with self.subTest(raw=raw):
                self.assertEqual(run_cli(raw, ui_module_marker=False), expected)


def run_cli(raw, ui_module_marker):
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        script = root / ".github" / "scripts" / SCRIPT.name
        script.parent.mkdir(parents=True)
        shutil.copyfile(SCRIPT, script)
        if ui_module_marker:
            marker = root / "openbank-admin-ui" / ui_module_marker
            marker.parent.mkdir(parents=True)
            marker.touch()
        result = subprocess.run([sys.executable, str(script)], input=raw,
                                capture_output=True, check=True)
        return result.stdout


if __name__ == "__main__":
    unittest.main()
