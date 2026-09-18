# SPDX-License-Identifier: Apache-2.0
"""Focused contract tests for the streaming Context/Audit export comparison."""

import csv
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("reconcile-context-audit.py")
IDS = [f"00000000-0000-4000-8000-{number:012x}" for number in range(1, 5)]
A = "a" * 64
B = "b" * 64


class ReconcileContextAuditTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.context = Path(self.directory.name) / "context.csv"
        self.fleet = Path(self.directory.name) / "fleet.csv"

    def write(self, path, records):
        with path.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.writer(stream)
            writer.writerow(("audit_id", "commitment"))
            writer.writerows(records)

    def run_script(self):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--context", str(self.context), "--fleet", str(self.fleet)],
            text=True,
            capture_output=True,
            check=False,
        )

    def test_equal_streams_pass(self):
        records = [(IDS[0], A), (IDS[2], B)]
        self.write(self.context, records)
        self.write(self.fleet, records)

        completed = self.run_script()

        self.assertEqual(completed.returncode, 0, completed.stderr)
        result = json.loads(completed.stdout)
        self.assertEqual(result["matched"], 2)
        self.assertEqual(result["status"], "MATCH")

    def test_missing_and_mismatched_records_fail(self):
        self.write(self.context, [(IDS[0], A), (IDS[1], B), (IDS[2], A)])
        self.write(self.fleet, [(IDS[0], B), (IDS[2], A), (IDS[3], A)])

        completed = self.run_script()

        self.assertEqual(completed.returncode, 1, completed.stderr)
        result = json.loads(completed.stdout)
        self.assertEqual(result["commitment_mismatch"], 1)
        self.assertEqual(result["missing_in_fleet"], 1)
        self.assertEqual(result["missing_in_context"], 1)
        self.assertEqual(result["matched"], 1)

    def test_duplicate_or_unsorted_id_is_invalid_not_a_difference(self):
        self.write(self.context, [(IDS[1], A), (IDS[0], A)])
        self.write(self.fleet, [])

        completed = self.run_script()

        self.assertEqual(completed.returncode, 2)
        self.assertIn("sorted with unique audit IDs", completed.stderr)

    def test_malformed_hash_is_invalid(self):
        self.write(self.context, [(IDS[0], "secret-not-a-hash")])
        self.write(self.fleet, [])

        completed = self.run_script()

        self.assertEqual(completed.returncode, 2)
        self.assertIn("invalid SHA-256 commitment", completed.stderr)

    def test_two_empty_exports_do_not_claim_reconciliation(self):
        self.write(self.context, [])
        self.write(self.fleet, [])

        completed = self.run_script()

        self.assertEqual(completed.returncode, 2)
        self.assertEqual(json.loads(completed.stdout)["status"], "EMPTY")

    def test_multiple_bank_scopes_merge_in_constant_space(self):
        second_bank = Path(self.directory.name) / "second-bank.csv"
        self.write(self.context, [(IDS[0], A), (IDS[2], A)])
        self.write(second_bank, [(IDS[1], B), (IDS[3], B)])
        self.write(self.fleet, [(IDS[0], A), (IDS[1], B), (IDS[2], A), (IDS[3], B)])

        completed = subprocess.run(
            [
                sys.executable, str(SCRIPT), "--context", str(self.context),
                "--context", str(second_bank), "--fleet", str(self.fleet),
            ],
            text=True, capture_output=True, check=False,
        )

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(json.loads(completed.stdout)["matched"], 4)

    def test_duplicate_id_across_bank_scopes_is_invalid(self):
        second_bank = Path(self.directory.name) / "second-bank.csv"
        self.write(self.context, [(IDS[0], A)])
        self.write(second_bank, [(IDS[0], A)])
        self.write(self.fleet, [(IDS[0], A)])

        completed = subprocess.run(
            [
                sys.executable, str(SCRIPT), "--context", str(self.context),
                "--context", str(second_bank), "--fleet", str(self.fleet),
            ],
            text=True, capture_output=True, check=False,
        )

        self.assertEqual(completed.returncode, 2)
        self.assertIn("duplicated across bank-scoped exports", completed.stderr)


if __name__ == "__main__":
    unittest.main()
