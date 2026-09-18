#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Verify manual-build CodeQL cannot accept a partial cached fleet trace."""

import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "check-codeql-compile-coverage.py"
spec = importlib.util.spec_from_file_location("codeql_compile_coverage", SCRIPT)
coverage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(coverage)


class CompileCoverageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ("openbank-alpha-service", "openbank-beta-service"):
            module = self.root / name
            (module / "src/main/kotlin").mkdir(parents=True)
            (module / "build.gradle.kts").touch()
            (module / "src/main/kotlin/App.kt").write_text("class App\n")

    def test_all_modules_must_execute(self):
        log = "> Task :openbank-alpha-service:compileKotlin\n> Task :openbank-beta-service:compileKotlin\n"
        self.assertTrue(coverage.check(self.root, log)[0])

    def test_one_cached_module_is_not_whole_fleet_coverage(self):
        log = "> Task :openbank-alpha-service:compileKotlin\n> Task :openbank-beta-service:compileKotlin FROM-CACHE\n"
        valid, reason = coverage.check(self.root, log)
        self.assertFalse(valid)
        self.assertIn("openbank-beta-service", reason)

    def test_missing_and_no_source_modules_fail(self):
        self.assertFalse(coverage.check(self.root, "> Task :openbank-alpha-service:compileKotlin\n")[0])
        log = "> Task :openbank-alpha-service:compileKotlin\n> Task :openbank-beta-service:compileKotlin NO-SOURCE\n"
        self.assertFalse(coverage.check(self.root, log)[0])

    def test_duplicate_task_lines_cannot_create_a_false_pass(self):
        log = (
            "> Task :openbank-alpha-service:compileKotlin\n"
            "> Task :openbank-beta-service:compileKotlin FROM-CACHE\n"
            "> Task :openbank-beta-service:compileKotlin\n"
        )
        self.assertFalse(coverage.check(self.root, log)[0])


if __name__ == "__main__":
    unittest.main()
