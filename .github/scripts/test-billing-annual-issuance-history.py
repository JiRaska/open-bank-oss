#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Synthetic, customer-free tests for annual issuance history preflight."""

from __future__ import annotations

import importlib.util
import io
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("check-billing-annual-issuance-history.py")
spec = importlib.util.spec_from_file_location("annual_issuance_preflight", SCRIPT)
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

EVENT_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
EVENT_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
HEADER = "account_id,calendar_year,source_event_id\n"


class AnnualIssuanceHistoryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def csv(self, name: str, *rows: str) -> Path:
        path = self.root / name
        path.write_text(HEADER + "".join(row + "\n" for row in rows))
        return path

    def cli(self, history: Path, issuance: Path, expected: int = 1) -> tuple[int, str]:
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch("sys.argv", ["preflight", "--historical-events", str(history),
                                "--issuance-keys", str(issuance), "--year", "2025",
                                "--expected-events", str(expected)]), \
             redirect_stdout(stdout), redirect_stderr(stderr):
            result = module.main()
        return result, stdout.getvalue() + stderr.getvalue()

    def test_exact_historical_coverage(self) -> None:
        history = self.csv("history", f"synthetic-a,2025,{EVENT_A}",
                           f"synthetic-a,2025,{EVENT_A}")
        issuance = self.csv("issuance", f"synthetic-a,2025,{EVENT_A}")
        self.assertEqual(module.compare(history, issuance, 2025, 1), (1, 1, 1, 0, 0))

    def test_purged_historical_event_without_key_is_detected(self) -> None:
        history = self.csv("history", f"synthetic-a,2025,{EVENT_A}")
        issuance = self.csv("issuance")
        self.assertEqual(module.compare(history, issuance, 2025, 1), (1, 0, 0, 1, 0))

    def test_wrong_event_or_incomplete_archive_fails(self) -> None:
        history = self.csv("history", f"synthetic-a,2025,{EVENT_A}")
        issuance = self.csv("issuance", f"synthetic-a,2025,{EVENT_B}")
        self.assertEqual(module.compare(history, issuance, 2025, 1), (1, 1, 0, 0, 1))
        with self.assertRaisesRegex(ValueError, "archive count"):
            module.compare(history, issuance, 2025, 2)

    def test_duplicate_issuance_and_reused_event_are_refused(self) -> None:
        history = self.csv("history", f"synthetic-a,2025,{EVENT_A}")
        duplicate = self.csv("duplicate", f"synthetic-a,2025,{EVENT_A}",
                             f"synthetic-a,2025,{EVENT_A}")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            module.compare(history, duplicate, 2025, 1)
        reused = self.csv("reused", f"synthetic-a,2025,{EVENT_A}",
                          f"synthetic-b,2025,{EVENT_A}")
        with self.assertRaisesRegex(ValueError, "reused"):
            module.compare(reused, duplicate, 2025, 2)

    def test_failure_output_contains_counts_but_no_identifiers(self) -> None:
        history = self.csv("history", f"synthetic-a,2025,{EVENT_A}")
        issuance = self.csv("issuance")
        result, output = self.cli(history, issuance)
        self.assertEqual(result, 1)
        self.assertIn("missing_keys=1", output)
        self.assertNotIn("synthetic-a", output)
        self.assertNotIn(EVENT_A, output)

    def test_same_count_wrong_set_fails_without_identifiers(self) -> None:
        history = self.csv("history", f"synthetic-a,2025,{EVENT_A}")
        issuance = self.csv("issuance", f"synthetic-b,2025,{EVENT_B}")
        result, output = self.cli(history, issuance)
        self.assertEqual(result, 1)
        self.assertIn("historical_events=1 issuance_keys=1", output)
        self.assertIn("missing_keys=1 conflicting_or_unarchived=1", output)
        for identity in ("synthetic-a", "synthetic-b", EVENT_A, EVENT_B):
            self.assertNotIn(identity, output)

    def test_malformed_and_duplicate_inputs_fail_without_identifiers(self) -> None:
        history = self.csv("history", f"synthetic-a,2025,{EVENT_A}")
        malformed = self.csv("malformed", "synthetic-b,2025,not-a-uuid")
        duplicate = self.csv("duplicate", f"synthetic-a,2025,{EVENT_A}",
                             f"synthetic-a,2025,{EVENT_A}")
        for bad_file in (malformed, duplicate):
            with self.subTest(input=bad_file.name):
                result, output = self.cli(history, bad_file)
                self.assertEqual(result, 2)
                self.assertIn("preflight failed: invalid, incomplete, or unreadable input", output)
                for identity in ("synthetic-a", "synthetic-b", EVENT_A):
                    self.assertNotIn(identity, output)


if __name__ == "__main__":
    unittest.main()
