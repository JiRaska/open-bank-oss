#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

"""Known-positive and known-negative fixtures for the ADR-0326 catalogue gate."""

from __future__ import annotations

import pathlib
import tempfile
import unittest

from error_code_catalogue import (
    baseline_lines,
    duplicate_owner_lines,
    evaluate,
    load_baseline,
    load_duplicate_owners,
    scan,
)


VALID_ENUM = """
package probe

import com.openbank.libs.domain.error.ErrorCategory
import com.openbank.libs.domain.error.ErrorCode

enum class WidgetErrorCode(override val category: ErrorCategory, override val title: String) : ErrorCode {
    WIDGET_NOT_FOUND(ErrorCategory.NOT_FOUND, "The widget does not exist"),
    ;
    override val code: String get() = name
}
"""


class CatalogueTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        self.source = self.root / "openbank-widget-service/src/main/kotlin/WidgetErrorCode.kt"
        self.source.parent.mkdir(parents=True)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write(self, text: str, name: str = "WidgetErrorCode.kt") -> None:
        target = self.source.parent / name
        target.write_text(text, encoding="utf-8")

    def records(self):
        records, subjects = scan(self.root)
        self.assertEqual(1, subjects)
        return records

    def baseline(self, records):
        return load_baseline(self.root, baseline_lines(records))

    def duplicates(self, records):
        return load_duplicate_owners(self.root, duplicate_owner_lines(records))

    def test_valid_enum_is_discovered_and_clean(self) -> None:
        self.write(VALID_ENUM)
        records = self.records()
        self.assertEqual(["WIDGET_NOT_FOUND"], [record.code for record in records])
        self.assertEqual("NOT_FOUND", records[0].category)
        self.assertEqual("The widget does not exist", records[0].title)
        self.assertFalse(records[0].retryable)
        self.assertEqual([], evaluate(records, self.baseline(records), self.duplicates(records)))

    def test_retryable_default_and_override_are_recorded(self) -> None:
        self.write(VALID_ENUM.replace(
            "WIDGET_NOT_FOUND(ErrorCategory.NOT_FOUND, \"The widget does not exist\")",
            "WIDGET_UNAVAILABLE(ErrorCategory.UNAVAILABLE, \"Later\", retryableOverride = false)",
        ))
        records = self.records()
        self.assertFalse(records[0].retryable)
        self.assertEqual("UNAVAILABLE", records[0].category)

    def test_new_code_requires_a_baseline_entry(self) -> None:
        self.write(VALID_ENUM)
        records = self.records()
        self.assertTrue(any("not in the append-only baseline" in error for error in evaluate(records, {}, {})))

    def test_removed_published_code_is_rejected(self) -> None:
        self.write(VALID_ENUM)
        records = self.records()
        self.assertTrue(any("published code disappeared" in error for error in evaluate([], self.baseline(records), {})))

    def test_category_change_is_rejected(self) -> None:
        self.write(VALID_ENUM)
        before = self.records()
        self.write(VALID_ENUM.replace("ErrorCategory.NOT_FOUND", "ErrorCategory.CONFLICT"))
        after = self.records()
        self.assertTrue(any("changed its published metadata" in error for error in evaluate(after, self.baseline(before), {})))

    def test_duplicate_owner_is_rejected_without_an_explicit_legacy_row(self) -> None:
        self.write(VALID_ENUM)
        self.write(VALID_ENUM.replace("WidgetErrorCode", "OtherWidgetErrorCode"), "OtherWidgetErrorCode.kt")
        records, _ = scan(self.root)
        errors = evaluate(records, self.baseline(records), {})
        self.assertTrue(any("duplicate wire code" in error for error in errors))
        self.assertEqual([], evaluate(records, self.baseline(records), self.duplicates(records)))

    def test_stale_duplicate_owner_is_rejected(self) -> None:
        self.write(VALID_ENUM)
        self.write(VALID_ENUM.replace("WidgetErrorCode", "OtherWidgetErrorCode"), "OtherWidgetErrorCode.kt")
        records, _ = scan(self.root)
        baseline = self.baseline(records)
        duplicates = self.duplicates(records)
        (self.source.parent / "OtherWidgetErrorCode.kt").unlink()
        remaining = self.records()
        self.assertTrue(any("stale duplicate-owner" in error for error in evaluate(remaining, baseline, duplicates)))

    def test_malformed_code_is_rejected(self) -> None:
        self.write(VALID_ENUM.replace("WIDGET_NOT_FOUND", "widget_not_found"))
        with self.assertRaisesRegex(ValueError, "UPPER_SNAKE_CASE"):
            scan(self.root)

    def test_non_name_code_mapping_is_not_silently_omitted(self) -> None:
        self.write(VALID_ENUM.replace("get() = name", 'get() = "SOMETHING_ELSE"'))
        with self.assertRaisesRegex(ValueError, "must expose its enum name"):
            scan(self.root)

    def test_comments_do_not_create_codes_or_hide_nested_comment_end(self) -> None:
        self.write(VALID_ENUM.replace(
            "    WIDGET_NOT_FOUND",
            "    /* outer /* nested */ FAKE(ErrorCategory.INTERNAL, \"fake\") still comment */\n    WIDGET_NOT_FOUND",
        ))
        self.assertEqual(["WIDGET_NOT_FOUND"], [record.code for record in self.records()])

    def test_constructor_property_typed_error_code_is_not_a_supertype(self) -> None:
        self.write("""
            enum class InvalidMoneyReason(val errorCode: ErrorCode) {
                SCALE_EXCEEDED(PlatformErrorCode.AMOUNT_SCALE_EXCEEDED),
            }
        """)
        records, _ = scan(self.root)
        self.assertEqual([], records)

    def test_no_codes_is_not_a_clean_fleet(self) -> None:
        self.write("class NotAnEnum")
        records, _ = scan(self.root)
        self.assertTrue(any("no ErrorCode enums" in error for error in evaluate(records, {}, {})))


def run_self_test() -> int:
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(CatalogueTest)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(run_self_test())
