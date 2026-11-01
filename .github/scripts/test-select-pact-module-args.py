#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Regression tests for the `modules` Pact scope selection."""

import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).with_name("select-pact-module-args.py")
SPEC = importlib.util.spec_from_file_location("select_pact_module_args", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

GROUP = ["--rerun", "--tests", "*.contract.*PactConsumerTest"]
ALL = [":openbank-a-service:consumerPactTest", *GROUP, ":openbank-b-service:consumerPactTest", *GROUP, ":openbank-c-service:consumerPactTest", *GROUP]


class SelectTest(unittest.TestCase):
    def test_selects_only_listed_modules_in_derived_order(self):
        self.assertEqual(MODULE.select_args(ALL, ["openbank-c-service", "openbank-a-service"]),
                         [":openbank-a-service:consumerPactTest", *GROUP, ":openbank-c-service:consumerPactTest", *GROUP])

    def test_module_without_consumer_test_selects_nothing(self):
        self.assertEqual(MODULE.select_args(ALL, ["openbank-z-service"]), [])

    def test_rejects_malformed_scope_and_names(self):
        with self.assertRaises(ValueError):
            MODULE.select_args(ALL[:-1], ["openbank-a-service"])
        with self.assertRaises(ValueError):
            MODULE.select_args(ALL + ALL[:4], ["openbank-a-service"])
        with self.assertRaises(ValueError):
            MODULE.select_args(ALL, ["openbank-a-service; rm -rf /"])
        with self.assertRaises(ValueError):
            MODULE.select_args([], ["openbank-a-service"])
        # The pre-#2291 `:test` task shape must stay rejected: derive-pact-drift-scope.sh
        # emits the dedicated :consumerPactTest task, and a stale shape here silently
        # deselects every listed module (the empty-scope archive path).
        with self.assertRaises(ValueError):
            MODULE.select_args([":openbank-a-service:test", *GROUP], ["openbank-a-service"])


if __name__ == "__main__":
    unittest.main()
