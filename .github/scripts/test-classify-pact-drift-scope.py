#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Regression tests for fail-closed UI-only Pact scope classification."""

import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("classify-pact-drift-scope.py")
SPEC = importlib.util.spec_from_file_location("classify_pact_drift_scope", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ClassifierTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Pact scope fixture")
        self.git("config", "user.email", "pact-scope@example.test")
        self.write("openbank-admin-ui/src/page.tsx", "initial\n")
        self.write("pacts/openbank-admin-ui-provider.json", "{}\n")
        for module in ("openbank-alpha-service", "openbank-beta-service", "openbank-gamma-service"):
            self.write(f"{module}/build.gradle.kts", 'dependencies { implementation(project(":openbank-libs-domain")) }\n')
            self.write(f"{module}/src/main/kotlin/A.kt", "initial\n")
        self.write("openbank-libs-domain/build.gradle.kts", "plugins {}\n")
        self.write("openbank-libs-domain/src/main/kotlin/L.kt", "initial\n")
        self.write("openbank-no-build/README.md", "not a gradle module\n")
        # Existing non-input files: a MODIFICATION of these is ignored, an ADDED .github file is not.
        for path in (".github/gates/gates.yaml", ".github/event-contract-baseline.txt",
                     ".github/scripts/check-something-else.py", ".github/workflows/ci.yml",
                     ".github/workflows/pact-drift-check.yml", "openbank-libs/governance/rules.yaml"):
            self.write(path, "initial\n")
        # Ownership derivation stand-ins for the pacts/ check: the real scripts are not run here.
        self.write(".github/scripts/expand-pact-scope-modules.py",
                   "import sys\nprint('\\n'.join(m for m in sys.argv[1].split(',') if m != 'openbank-admin-ui'))\n"
                   "if 'openbank-admin-ui' in sys.argv[1]: print('openbank-gamma-service')\n")
        self.write(".github/scripts/derive-pact-drift-scope.sh",
                   "#!/bin/sh\nprintf 'openbank-alpha-service\\tpacts/openbank-alpha-service-openbank-p.json\\n"
                   "openbank-gamma-service\\tpacts/openbank-admin-ui-openbank-p.json\\n'\n")
        self.write(".github/scripts/pact-js-contracts.py", "print('pacts/openbank-admin-ui-openbank-js.json')\n")
        (self.repo / ".github/scripts/derive-pact-drift-scope.sh").chmod(0o755)
        for pact in ("openbank-alpha-service-openbank-p", "openbank-admin-ui-openbank-p", "openbank-admin-ui-openbank-js",
                     "openbank-beta-service-openbank-p"):
            self.write(f"pacts/{pact}.json", "{}\n")
        self.git("add", "--all")
        self.git("-c", "commit.gpgsign=false", "commit", "-qm", "fixture base")
        self.base = self.git("rev-parse", "HEAD").decode().strip()

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.repo)

    def write(self, path, content):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    def commit(self):
        self.git("add", "--all")
        self.git("-c", "commit.gpgsign=false", "commit", "-qm", "fixture change")
        return self.git("rev-parse", "HEAD").decode().strip()

    def test_only_admin_ui_changes_select_scoped_mode(self):
        self.write("openbank-admin-ui/src/page.tsx", "updated\n")
        self.write("openbank-admin-ui/src/new.tsx", "new\n")
        head = self.commit()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.UI_ONLY)

    def test_pact_and_shared_paths_force_full_mode(self):
        for path in ("pacts/openbank-admin-ui-provider.json", "gradle.properties",
                     ".github/workflows/pact-drift-check.yml", "openbank-libs-core/src/A.kt"):
            with self.subTest(path=path):
                self.write(path, "changed\n")
                head = self.commit()
                self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)
                # Each subtest starts from a new base to keep diff paths isolated.
                self.base = head

    def test_empty_diff_forces_full_mode(self):
        self.assertEqual(MODULE.classify(self.repo, self.base, self.base)[0], MODULE.FULL)

    def test_unavailable_revision_forces_full_mode(self):
        self.assertEqual(MODULE.classify(self.repo, self.base, "missing-revision")[0], MODULE.FULL)

    def test_non_ancestor_base_forces_full_mode(self):
        self.git("checkout", "--orphan", "unrelated")
        self.git("rm", "-rf", ".")
        self.write("openbank-admin-ui/src/page.tsx", "unrelated history\n")
        self.git("add", "--all")
        self.git("-c", "commit.gpgsign=false", "commit", "-qm", "unrelated head")
        head = self.git("rev-parse", "HEAD").decode().strip()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)

    def test_classification_outputs_single_ui_shard(self):
        self.write("openbank-admin-ui/src/page.tsx", "updated\n")
        head = self.commit()
        output = self.repo / "github-output"
        result = subprocess.run(
            ["python3", str(SCRIPT), "--repo", str(self.repo), "--base", self.base,
             "--head", head, "--output", str(output)],
            check=True, capture_output=True, text=True,
        )
        self.assertIn("ui-only", result.stdout)
        self.assertEqual(output.read_text(), 'mode=ui-only\nshards=["ui"]\nmodules=\n')

    def test_full_fallback_outputs_all_four_shards(self):
        output = self.repo / "github-output"
        subprocess.run(
            ["python3", str(SCRIPT), "--repo", str(self.repo), "--base", self.base,
             "--head", "missing-revision", "--output", str(output)],
            check=True, capture_output=True, text=True,
        )
        self.assertEqual(output.read_text(), 'mode=full\nshards=["0", "1", "2", "3"]\nmodules=\n')

    def test_only_service_module_changes_select_modules_mode(self):
        self.write("openbank-alpha-service/src/main/kotlin/A.kt", "updated\n")
        self.write("openbank-beta-service/src/test/kotlin/contract/BPactConsumerTest.kt", "new\n")
        head = self.commit()
        mode, _reason, modules = MODULE.classify(self.repo, self.base, head)
        self.assertEqual(mode, MODULE.MODULES)
        self.assertEqual(modules, ["openbank-alpha-service", "openbank-beta-service"])

    def test_any_build_script_change_forces_full_mode(self):
        self.write("openbank-alpha-service/build.gradle.kts", "dependencies {}\n")
        head = self.commit()
        mode, reason, _ = MODULE.classify(self.repo, self.base, head)
        self.assertEqual(mode, MODULE.FULL)
        self.assertIn("build.gradle.kts", reason)
        self.base = head
        self.write("openbank-new-service/build.gradle.kts", "plugins {}\n")
        self.write("openbank-new-service/src/main/kotlin/N.kt", "new module\n")
        head = self.commit()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)

    def test_added_file_under_github_forces_full_mode(self):
        for path in (".github/scripts/re.py", ".github/scripts/helper.sh", ".github/gates/new.yaml"):
            with self.subTest(path=path):
                self.write("openbank-alpha-service/src/main/kotlin/A.kt", f"touch {path}\n")
                self.write(path, "new\n")
                head = self.commit()
                self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)
                self.base = head

    def test_libs_root_gradle_and_non_module_dirs_force_full_mode(self):
        for path in ("openbank-libs-domain/src/main/kotlin/L.kt", "openbank-libs/gradle/libs.versions.toml",
                     "build.gradle.kts", "settings.gradle.kts", "openbank-no-build/README.md",
                     "openbank-infra/gitops/x.yaml", "docs/adr/0001.md"):
            with self.subTest(path=path):
                self.write("openbank-alpha-service/src/main/kotlin/A.kt", f"touch {path}\n")
                self.write(path, "changed\n")
                head = self.commit()
                self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)
                self.base = head

    def test_non_input_paths_are_ignored_for_scoping(self):
        for path in ("docs/adr/0001.md", ".github/gates/gates.yaml", ".github/event-contract-baseline.txt",
                     ".github/scripts/check-something-else.py", ".github/workflows/ci.yml",
                     "openbank-libs/governance/rules.yaml"):
            with self.subTest(path=path):
                self.write("openbank-alpha-service/src/main/kotlin/A.kt", f"touch {path}\n")
                self.write(path, "changed\n")
                head = self.commit()
                self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.MODULES)
                self.base = head
        self.write("openbank-admin-ui/src/page.tsx", "updated\n")
        self.write("docs/adr/0002.md", "new\n")
        head = self.commit()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.UI_ONLY)

    def test_gate_own_workflow_and_pact_scripts_force_full_mode(self):
        for path in (".github/workflows/pact-drift-check.yml", ".github/scripts/derive-pact-drift-scope.sh",
                     ".github/scripts/classify-pact-drift-scope.py", ".github/scripts/pact-js-contracts.py"):
            with self.subTest(path=path):
                self.write("openbank-alpha-service/src/main/kotlin/A.kt", f"touch {path}\n")
                self.write(path, "changed\n")
                head = self.commit()
                self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)
                self.base = head

    def test_only_non_input_paths_force_full_mode(self):
        self.write("docs/adr/0001.md", "changed\n")
        head = self.commit()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)

    def test_copy_between_scoped_modules_stays_scoped(self):
        self.write("openbank-alpha-service/src/main/kotlin/Big.kt", "line\n" * 40)
        self.base = self.commit()
        self.write("openbank-beta-service/src/main/kotlin/Big.kt", "line\n" * 40)
        head = self.commit()
        mode, _reason, modules = MODULE.classify(self.repo, self.base, head)
        self.assertEqual(mode, MODULE.MODULES)
        self.assertEqual(modules, ["openbank-alpha-service", "openbank-beta-service"])

    def test_copy_from_outside_a_scoped_module_forces_full_mode(self):
        self.write("openbank-libs-domain/src/main/kotlin/Big.kt", "line\n" * 40)
        self.base = self.commit()
        self.write("openbank-beta-service/src/main/kotlin/Big.kt", "line\n" * 40)
        head = self.commit()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)

    def test_mixed_admin_ui_and_service_changes_select_modules_with_admin_ui(self):
        self.write("openbank-admin-ui/src/page.tsx", "updated\n")
        self.write("openbank-alpha-service/src/main/kotlin/A.kt", "updated\n")
        head = self.commit()
        mode, _reason, modules = MODULE.classify(self.repo, self.base, head)
        self.assertEqual(mode, MODULE.MODULES)
        self.assertEqual(modules, ["openbank-admin-ui", "openbank-alpha-service"])

    def test_infra_and_release_please_paths_are_ignored_except_ledger_pension_co(self):
        self.write("openbank-alpha-service/src/main/kotlin/A.kt", "updated\n")
        self.write("openbank-infra/gitops/components/alpha/alpha-service.yaml", "x\n")
        self.write(".release-please-manifest.json", "{}\n")
        head = self.commit()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.MODULES)
        self.write("openbank-infra/gitops/components/ledger-pension-co/ledger-service.yaml", "x\n")
        head = self.commit()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)

    def test_changed_pact_owned_by_a_changed_module_stays_scoped(self):
        self.write("openbank-alpha-service/src/test/kotlin/contract/APactConsumerTest.kt", "x\n")
        self.write("pacts/openbank-alpha-service-openbank-p.json", "{\"v\": 2}\n")
        head = self.commit()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.MODULES)
        # The Admin UI stands for its JVM producer modules and for every PactJS pact.
        self.write("openbank-admin-ui/src/page.tsx", "updated\n")
        self.write("pacts/openbank-admin-ui-openbank-p.json", "{\"v\": 2}\n")
        self.write("pacts/openbank-admin-ui-openbank-js.json", "{\"v\": 2}\n")
        head = self.commit()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.MODULES)

    def test_changed_pact_owned_by_an_unchanged_module_forces_full_mode(self):
        self.write("openbank-alpha-service/src/main/kotlin/A.kt", "updated\n")
        self.write("pacts/openbank-beta-service-openbank-p.json", "{\"v\": 2}\n")
        head = self.commit()
        mode, reason, _ = MODULE.classify(self.repo, self.base, head)
        self.assertEqual(mode, MODULE.FULL)
        self.assertIn("openbank-beta-service-openbank-p.json", reason)

    def test_only_pacts_changed_forces_full_mode(self):
        self.write("pacts/openbank-alpha-service-openbank-p.json", "{\"v\": 2}\n")
        head = self.commit()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)

    def test_ownership_derivation_failure_forces_full_mode(self):
        self.write(".github/scripts/derive-pact-drift-scope.sh", "#!/bin/sh\nexit 1\n")
        self.base = self.commit()
        self.write("openbank-alpha-service/src/main/kotlin/A.kt", "updated\n")
        self.write("pacts/openbank-alpha-service-openbank-p.json", "{\"v\": 2}\n")
        head = self.commit()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)

    def test_dependent_with_consumer_pact_test_joins_the_scope(self):
        self.write("openbank-gamma-service/build.gradle.kts",
                   'dependencies { implementation(project(":openbank-alpha-service")) }\n')
        self.write("openbank-gamma-service/src/test/kotlin/com/x/contract/GPactConsumerTest.kt", "x\n")
        # beta depends on gamma: transitive, and without a pact test it is irrelevant.
        self.write("openbank-beta-service/build.gradle.kts",
                   'dependencies { implementation(project(":openbank-gamma-service")) }\n')
        self.base = self.commit()
        self.write("openbank-alpha-service/src/main/kotlin/A.kt", "updated\n")
        head = self.commit()
        mode, _reason, modules = MODULE.classify(self.repo, self.base, head)
        self.assertEqual(mode, MODULE.MODULES)
        self.assertEqual(modules, ["openbank-alpha-service", "openbank-gamma-service"])

    def test_dependent_without_consumer_pact_test_is_ignored(self):
        self.write("openbank-gamma-service/build.gradle.kts",
                   'dependencies { implementation(project(":openbank-alpha-service")) }\n')
        self.base = self.commit()
        self.write("openbank-alpha-service/src/main/kotlin/A.kt", "updated\n")
        head = self.commit()
        mode, _reason, modules = MODULE.classify(self.repo, self.base, head)
        self.assertEqual(mode, MODULE.MODULES)
        self.assertEqual(modules, ["openbank-alpha-service"])

    def test_too_many_modules_force_full_mode(self):
        for index in range(MODULE.MAX_MODULES + 1):
            self.write(f"openbank-many{index}-service/build.gradle.kts", "plugins {}\n")
        self.base = self.commit()
        for index in range(MODULE.MAX_MODULES + 1):
            self.write(f"openbank-many{index}-service/src/A.kt", "x\n")
        head = self.commit()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)

    def test_deleted_module_forces_full_mode(self):
        self.git("rm", "-rq", "openbank-alpha-service")
        head = self.commit()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)

    def test_modules_classification_outputs_single_modules_shard(self):
        self.write("openbank-beta-service/src/main/kotlin/A.kt", "updated\n")
        head = self.commit()
        output = self.repo / "github-output"
        subprocess.run(
            ["python3", str(SCRIPT), "--repo", str(self.repo), "--base", self.base,
             "--head", head, "--output", str(output)],
            check=True, capture_output=True, text=True,
        )
        self.assertEqual(output.read_text(),
                         'mode=modules\nshards=["modules"]\nmodules=openbank-beta-service\n')

    def test_rename_forces_full_mode_even_inside_ui_tree(self):
        self.git("mv", "openbank-admin-ui/src/page.tsx", "openbank-admin-ui/src/renamed.tsx")
        head = self.commit()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)

    def test_copy_forces_full_mode_even_inside_ui_tree(self):
        self.write("openbank-admin-ui/src/copy.tsx", "initial\n")
        head = self.commit()
        self.assertEqual(MODULE.classify(self.repo, self.base, head)[0], MODULE.FULL)


if __name__ == "__main__":
    unittest.main()
