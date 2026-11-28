#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

"""Known-positive and known-negative fixtures for the service-local mapper ratchet."""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import tempfile
import unittest


def load_checker():
    path = pathlib.Path(__file__).with_name("check-service-local-exception-mappers.py")
    spec = importlib.util.spec_from_file_location("check_service_local_exception_mappers", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


checker = load_checker()


NAMED = "class LocalMapper : ExceptionMapper<WidgetException> { }\n"
ANONYMOUS = "val anonymous = object : ExceptionMapper<OtherWidgetException> { }\n"


class MapperRatchetTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write(self, source: str, module: str = "openbank-widget-service", name: str = "Mappers.kt") -> None:
        target = self.root / module / "src/main/kotlin" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding="utf-8")

    def test_named_and_anonymous_mappers_are_distinct_new_findings(self) -> None:
        self.write(NAMED + ANONYMOUS)
        current, subjects = checker.scan(self.root)
        self.assertEqual(1, subjects)
        self.assertEqual(2, len(current))
        self.assertTrue(any(key.endswith("\tLocalMapper") for key in current))
        self.assertTrue(any(key.endswith("\tanonymous-1") for key in current))
        self.assertEqual(2, len(checker.evaluate(current, set())))

    def test_exact_baseline_passes_and_removed_declaration_can_shrink(self) -> None:
        self.write(NAMED + ANONYMOUS)
        current, _ = checker.scan(self.root)
        self.assertEqual([], checker.evaluate(current, set(current)))
        self.write(NAMED)
        remaining, _ = checker.scan(self.root)
        baseline_without_anonymous = {key for key in current if not key.endswith("anonymous-1")}
        self.assertEqual([], checker.evaluate(remaining, baseline_without_anonymous))

    def test_stale_baseline_entry_fails_instead_of_disappearing_silently(self) -> None:
        self.write(NAMED + ANONYMOUS)
        current, _ = checker.scan(self.root)
        self.write(NAMED)
        remaining, _ = checker.scan(self.root)
        errors = checker.evaluate(remaining, set(current))
        self.assertTrue(any("stale mapper baseline" in error for error in errors))

    def test_comments_and_libs_runtime_are_not_service_mappers(self) -> None:
        self.write("// class Commented : ExceptionMapper<WidgetException>\nclass Clean")
        self.write(NAMED, module="openbank-libs-runtime")
        current, _ = checker.scan(self.root)
        self.assertEqual([], current)

    def test_psd2_exclusion_requires_the_berlin_group_body(self) -> None:
        self.write("class BerlinMapper : ExceptionMapper<WidgetException> { fun x() = tppMessages }", module="openbank-psd2-service")
        current, _ = checker.scan(self.root)
        self.assertEqual([], current)
        self.write(NAMED, module="openbank-psd2-service", name="Other.kt")
        current, _ = checker.scan(self.root)
        self.assertEqual(1, len(current))

    def test_quote_inside_a_kotlin_char_literal_does_not_break_the_scan(self) -> None:
        self.write("val quote = '\"'\n" + NAMED)
        current, _ = checker.scan(self.root)
        self.assertEqual(1, len(current))

    def test_no_sources_is_reported_by_the_subject_count(self) -> None:
        current, subjects = checker.scan(self.root)
        self.assertEqual([], current)
        self.assertEqual(0, subjects)


def run_self_test() -> int:
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(MapperRatchetTest)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(run_self_test())
