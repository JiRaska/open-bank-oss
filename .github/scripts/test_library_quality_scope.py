#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Regression proofs for library discovery and blocking workflow contracts."""
import tempfile
import unittest
from pathlib import Path

from library_quality_scope import tasks

ROOT = Path(__file__).resolve().parents[2]


class QualityScopeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def library(self, name, abi=True):
        module = self.root / name
        (module / "src/main/kotlin").mkdir(parents=True)
        (module / "src/main/kotlin/Example.kt").write_text("class Example")
        (module / "build.gradle.kts").write_text("abiValidation()" if abi else "kotlin {}")
        (module / "api").mkdir()
        (module / "api" / f"{name}.api").write_text("public final class Example {}")
        return module

    def test_new_library_is_automatically_selected_with_all_proofs(self):
        self.library("openbank-libs-domain")
        self.library("openbank-libs-future")
        self.assertEqual(tasks(self.root), [f":{m}:{t}" for m in
                         ("openbank-libs-domain", "openbank-libs-future") for t in
                         ("build", "koverVerify", "checkKotlinAbi")])

    def test_new_library_without_abi_is_rejected(self):
        self.library("openbank-libs-finance", abi=False)
        with self.assertRaisesRegex(ValueError, "enable ABI"):
            tasks(self.root)

    def test_missing_and_empty_snapshots_are_rejected(self):
        module = self.library("openbank-libs-finance")
        snapshot = module / "api/openbank-libs-finance.api"
        for missing in (False, True):
            if missing:
                snapshot.unlink()
            else:
                snapshot.write_text("")
            with self.assertRaisesRegex(ValueError, "ABI snapshot"):
                tasks(self.root)

    def test_umbrella_and_tools_do_not_create_runtime_abi_tasks(self):
        self.library("openbank-libs-domain")
        self.library("openbank-libs-detekt-rules", abi=False)
        self.library("openbank-libs-benchmarks", abi=False)
        (self.root / "openbank-libs").mkdir()
        self.assertEqual(len(tasks(self.root)), 3)

    def test_empty_checkout_is_not_a_green_run(self):
        with self.assertRaisesRegex(ValueError, "empty quality run"):
            tasks(self.root)

    def test_finance_is_in_actual_quality_scope(self):
        self.assertIn(":openbank-libs-finance:checkKotlinAbi", tasks(ROOT))

    def test_crashes_and_allocation_regressions_block_workflows(self):
        fuzz = (ROOT / ".github/workflows/cflite-pr.yml").read_text()
        self.assertNotIn("continue-on-error:", fuzz)
        bench = (ROOT / ".github/workflows/libs-bench.yml").read_text()
        self.assertIn("--results openbank-libs-benchmarks/build/reports/jmh/results.json --enforce", bench)
        quality = (ROOT / ".github/workflows/libs-quality.yml").read_text()
        self.assertIn("library_quality_scope.py", quality)
        self.assertIn('"${proof_tasks[@]}"', quality)


if __name__ == "__main__":
    unittest.main()
