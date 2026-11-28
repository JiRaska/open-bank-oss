#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

"""Falsification fixtures for deterministic error-catalogue generation."""

from __future__ import annotations

import importlib.util
import pathlib
import sys
import tempfile
import unittest

import yaml

from error_code_catalogue import CodeRecord


def load_generator():
    path = pathlib.Path(__file__).with_name("gen-error-code-catalogue.py")
    spec = importlib.util.spec_from_file_location("gen_error_code_catalogue", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


generator = load_generator()
drift = generator.drift
render_markdown = generator.render_markdown
render_openapi = generator.render_openapi
write_outputs = generator.write_outputs


RECORDS = [
    CodeRecord("WIDGET_UNAVAILABLE", "UNAVAILABLE", "Try later", False, "openbank-widget", "WidgetErrorCode", "b.kt"),
    CodeRecord("WIDGET_NOT_FOUND", "NOT_FOUND", "No widget", False, "openbank-widget", "WidgetErrorCode", "a.kt"),
    CodeRecord("WIDGET_NOT_FOUND", "NOT_FOUND", "No widget", False, "openbank-legacy", "LegacyErrorCode", "c.kt"),
]


class GeneratorTest(unittest.TestCase):
    def test_markdown_lists_sorted_codes_metadata_and_owners(self) -> None:
        rendered = render_markdown(RECORDS)
        self.assertLess(rendered.index("WIDGET_NOT_FOUND"), rendered.index("WIDGET_UNAVAILABLE"))
        self.assertIn("openbank-legacy.LegacyErrorCode", rendered)
        self.assertIn("openbank-widget.WidgetErrorCode", rendered)
        self.assertIn("Try later", rendered)
        self.assertIn("false", rendered)

    def test_openapi_contains_the_complete_problem_detail_and_sorted_code_enum(self) -> None:
        document = yaml.safe_load(render_openapi(RECORDS))
        schema = document["components"]["schemas"]["ProblemDetail"]
        self.assertEqual(["WIDGET_NOT_FOUND", "WIDGET_UNAVAILABLE"], schema["properties"]["code"]["enum"])
        expected = {
            "type", "title", "status", "detail", "instance", "code", "correlationId", "retryable",
            "violations", "traceId", "message", "timestamp", "details", "error",
        }
        self.assertEqual(expected, set(schema["properties"]))
        self.assertNotIn("error", schema["required"])
        self.assertIn("traceId", schema["required"])
        self.assertFalse(schema["additionalProperties"])

    def test_check_detects_missing_and_stale_outputs_and_regeneration_is_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            self.assertEqual(2, len(drift(root)))
            write_outputs(root)
            self.assertEqual([], drift(root))
            before = {path: path.read_bytes() for path in root.glob("**/*") if path.is_file()}
            write_outputs(root)
            after = {path: path.read_bytes() for path in root.glob("**/*") if path.is_file()}
            self.assertEqual(before, after)
            next(iter(before)).write_text("stale\n", encoding="utf-8")
            self.assertEqual(1, len(drift(root)))


def run_self_test() -> int:
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(GeneratorTest)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(run_self_test())
