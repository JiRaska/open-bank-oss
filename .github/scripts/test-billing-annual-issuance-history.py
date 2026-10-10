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
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch("sys.argv", ["preflight", "--historical-events", str(history),
                                "--issuance-keys", str(issuance), "--year", "2025",
                                "--expected-events", "1"]), \
             redirect_stdout(stdout), redirect_stderr(stderr):
            self.assertEqual(module.main(), 1)
        output = stdout.getvalue() + stderr.getvalue()
        self.assertIn("missing_keys=1", output)
        self.assertNotIn("synthetic-a", output)
        self.assertNotIn(EVENT_A, output)


if __name__ == "__main__":
    unittest.main()
