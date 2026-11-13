#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Focused standard-library tests for the payment/ledger pilot oracle."""

import copy
import importlib.util
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

SCRIPT_PATH = Path(__file__).resolve().parents[1] / "scripts" / "verify-payment-ledger.py"
SPEC = importlib.util.spec_from_file_location("verify_payment_ledger", SCRIPT_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("could not load payment-ledger verifier module")
VERIFIER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VERIFIER
SPEC.loader.exec_module(VERIFIER)
EvidenceError = VERIFIER.EvidenceError
load_json = VERIFIER._load_json
verify_documents = VERIFIER.verify_documents


NOW = datetime(2026, 10, 28, 12, 0, tzinfo=timezone.utc)
RUN_ID = "pilot-001"
SOURCE = "10000000-0000-0000-0000-000000000001"
TARGET = "10000000-0000-0000-0000-000000000002"
TX_ID = "20000000-0000-0000-0000-000000000001"
ENTRY_ID = "30000000-0000-0000-0000-000000000001"
CONTROL_GL = "a0000000-0000-0000-0000-000000000002"
KEY = f"capacity-pilot:{RUN_ID}:instruction-001"


def evidence():
    started = NOW - timedelta(minutes=3)
    ended = NOW - timedelta(minutes=2)
    captured = NOW - timedelta(minutes=1)
    return (
        {
            "schema_version": 1,
            "run_id": RUN_ID,
            "profile_id": "cz-current-account-5m-v1",
            "profile_version": 1,
            "scope": "internal-czk-transfer-pilot",
            "started_at": started.isoformat(),
            "ended_at": ended.isoformat(),
            "instructions": [
                {
                    "instruction_id": "instruction-001",
                    "idempotency_key": KEY,
                    "type": "TRANSFER",
                    "amount": "10.00",
                    "currency_code": "CZK",
                    "source_account_id": SOURCE,
                    "target_account_id": TARGET,
                },
            ],
        },
        {
            "schema_version": 1,
            "run_id": RUN_ID,
            "profile_id": "cz-current-account-5m-v1",
            "profile_version": 1,
            "source": "transaction-service",
            "captured_at": captured.isoformat(),
            "rows": [
                {
                    "id": TX_ID,
                    "booking_date": "2026-10-28",
                    "idempotency_key": KEY,
                    "type": "TRANSFER",
                    "status": "COMPLETED",
                    "amount": "10.000000",
                    "currency_code": "CZK",
                    "source_account_id": SOURCE,
                    "target_account_id": TARGET,
                    "reversal_of": None,
                },
            ],
        },
        {
            "schema_version": 1,
            "run_id": RUN_ID,
            "profile_id": "cz-current-account-5m-v1",
            "profile_version": 1,
            "source": "ledger-service",
            "captured_at": captured.isoformat(),
            "journal_entries": [
                {
                    "id": ENTRY_ID,
                    "entry_date": "2026-10-28",
                    "transaction_id": TX_ID,
                    "status": "POSTED",
                    "reversal_of": None,
                    "synthetic": True,
                    "created_at": (ended - timedelta(seconds=1)).isoformat(),
                },
            ],
            "journal_lines": [
                {
                    "id": "40000000-0000-0000-0000-000000000001",
                    "journal_id": ENTRY_ID,
                    "side": "D",
                    "amount": "10.000000",
                    "currency_code": "CZK",
                    "base_amount": "10.000000",
                    "base_currency": "CZK",
                    "gl_account_id": CONTROL_GL,
                    "sub_account_id": SOURCE,
                },
                {
                    "id": "40000000-0000-0000-0000-000000000002",
                    "journal_id": ENTRY_ID,
                    "side": "C",
                    "amount": "10.000000",
                    "currency_code": "CZK",
                    "base_amount": "10.000000",
                    "base_currency": "CZK",
                    "gl_account_id": CONTROL_GL,
                    "sub_account_id": TARGET,
                },
            ],
            "gl_accounts": [
                {"id": CONTROL_GL, "code": "2100", "currency_code": "CZK", "is_enabled": True},
            ],
            "orphan_journal_lines": [],
        },
    )


HASHES = {"expected": "a" * 64, "transactions": "b" * 64, "ledger": "c" * 64}


class VerifyPaymentLedgerTest(unittest.TestCase):
    def run_oracle(self, expected=None, transactions=None, ledger=None):
        base = evidence()
        return verify_documents(
            expected or base[0],
            transactions or base[1],
            ledger or base[2],
            HASHES,
            now=NOW,
        )

    def test_valid_internal_transfer_passes_from_db_facts(self):
        result = self.run_oracle()
        self.assertEqual("pass", result["status"])
        self.assertEqual(1, result["counts"]["matched_transaction_rows"])
        self.assertEqual([], result["failures"])

    def test_wrong_amount_in_completed_transaction_fails(self):
        _, transactions, _ = evidence()
        transactions["rows"][0]["amount"] = "11.00"
        result = self.run_oracle(transactions=transactions)
        self.assertEqual("fail", result["status"])
        self.assertTrue(any("amount differs" in item for item in result["failures"]))

    def test_wrong_source_or_target_in_completed_transaction_fails(self):
        for field in ("source_account_id", "target_account_id"):
            with self.subTest(field=field):
                _, transactions, _ = evidence()
                transactions["rows"][0][field] = "10000000-0000-0000-0000-000000000099"
                result = self.run_oracle(transactions=transactions)
                self.assertEqual("fail", result["status"])
                self.assertTrue(any(field in item for item in result["failures"]))

    def test_balanced_journal_on_wrong_customer_accounts_fails(self):
        _, _, ledger = evidence()
        ledger["journal_lines"][0]["sub_account_id"] = TARGET
        ledger["journal_lines"][1]["sub_account_id"] = SOURCE
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("fail", result["status"])
        self.assertTrue(any("accounts do not match" in item for item in result["failures"]))

    def test_balanced_but_wrong_amount_journal_fails(self):
        _, _, ledger = evidence()
        ledger["journal_lines"][0]["amount"] = "11.00"
        ledger["journal_lines"][1]["amount"] = "11.00"
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("fail", result["status"])
        self.assertTrue(any("amount/currency differs" in item for item in result["failures"]))

    def test_balanced_but_wrong_base_amount_or_currency_fails(self):
        for field, value in (("base_amount", "11.00"), ("base_currency", "EUR")):
            with self.subTest(field=field):
                _, _, ledger = evidence()
                for line in ledger["journal_lines"]:
                    line[field] = value
                result = self.run_oracle(ledger=ledger)
                self.assertEqual("fail", result["status"])
                self.assertTrue(any("base amount/currency" in item for item in result["failures"]))

    def test_unmarked_journal_fails_synthetic_pilot(self):
        _, _, ledger = evidence()
        ledger["journal_entries"][0]["synthetic"] = False
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("fail", result["status"])
        self.assertTrue(any("not marked synthetic" in item for item in result["failures"]))

    def test_missing_transaction_row_fails(self):
        _, transactions, _ = evidence()
        transactions["rows"] = []
        result = self.run_oracle(transactions=transactions)
        self.assertEqual("fail", result["status"])
        self.assertTrue(any("missing transaction row" in item for item in result["failures"]))

    def test_duplicate_partitioned_transaction_key_fails(self):
        _, transactions, _ = evidence()
        transactions["rows"].append(copy.deepcopy(transactions["rows"][0]))
        with self.assertRaisesRegex(EvidenceError, "duplicate snapshot row"):
            self.run_oracle(transactions=transactions)

    def test_unknown_journal_effect_in_isolated_run_window_fails(self):
        _, _, ledger = evidence()
        extra = copy.deepcopy(ledger["journal_entries"][0])
        extra["id"] = "30000000-0000-0000-0000-000000000099"
        extra["transaction_id"] = "20000000-0000-0000-0000-000000000099"
        ledger["journal_entries"].append(extra)
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("fail", result["status"])
        self.assertTrue(any("unknown transaction_id" in item for item in result["failures"]))

    def test_reversal_is_explicitly_unsupported(self):
        _, transactions, _ = evidence()
        transactions["rows"][0]["reversal_of"] = "20000000-0000-0000-0000-000000000099"
        result = self.run_oracle(transactions=transactions)
        self.assertEqual("fail", result["status"])
        self.assertTrue(any("reversal journeys are unsupported" in item for item in result["failures"]))

    def test_wrong_run_binding_is_rejected(self):
        expected, transactions, ledger = evidence()
        ledger["run_id"] = "different-run"
        with self.assertRaisesRegex(EvidenceError, "run_id does not match"):
            self.run_oracle(expected, transactions, ledger)

    def test_stale_snapshot_is_rejected(self):
        expected, transactions, ledger = evidence()
        expected["started_at"] = (NOW - timedelta(days=4)).isoformat()
        expected["ended_at"] = (NOW - timedelta(days=3)).isoformat()
        transactions["captured_at"] = (NOW - timedelta(days=2)).isoformat()
        ledger["captured_at"] = (NOW - timedelta(days=2)).isoformat()
        with self.assertRaisesRegex(EvidenceError, "stale or future-dated"):
            self.run_oracle(expected, transactions, ledger)

    def test_non_finite_or_numeric_json_amount_is_rejected(self):
        expected, _, _ = evidence()
        expected["instructions"][0]["amount"] = "Infinity"
        with self.assertRaisesRegex(EvidenceError, "plain decimal string"):
            self.run_oracle(expected=expected)

    def test_fractional_czk_minor_unit_is_rejected(self):
        expected, _, _ = evidence()
        expected["instructions"][0]["amount"] = "10.001"
        with self.assertRaisesRegex(EvidenceError, "minor-unit"):
            self.run_oracle(expected=expected)

    def test_zero_and_negative_instruction_amounts_are_rejected(self):
        for amount in ("0", "-1.00"):
            with self.subTest(amount=amount):
                expected, _, _ = evidence()
                expected["instructions"][0]["amount"] = amount
                with self.assertRaisesRegex(EvidenceError, "plain decimal string|finite and positive"):
                    self.run_oracle(expected=expected)

    def test_amount_above_transaction_numeric_integer_range_is_rejected(self):
        expected, _, _ = evidence()
        expected["instructions"][0]["amount"] = "100000000000000.00"
        with self.assertRaisesRegex(EvidenceError, r"NUMERIC\(20,6\)"):
            self.run_oracle(expected=expected)

    def test_maximum_supported_amount_compares_without_decimal_context_rounding(self):
        expected, transactions, ledger = evidence()
        maximum = "99999999999999.99"
        expected["instructions"][0]["amount"] = maximum
        transactions["rows"][0]["amount"] = f"{maximum}0000"
        for line in ledger["journal_lines"]:
            line["amount"] = maximum
            line["base_amount"] = maximum
        result = self.run_oracle(expected, transactions, ledger)
        self.assertEqual("pass", result["status"])

    def test_duplicate_json_keys_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "duplicate.json"
            path.write_text('{"run_id":"first","run_id":"second"}', encoding="utf-8")
            with self.assertRaisesRegex(EvidenceError, "duplicate JSON object key"):
                load_json(path)

    def test_orphan_ledger_line_fails(self):
        _, _, ledger = evidence()
        ledger["orphan_journal_lines"] = [{"id": "orphan"}]
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("fail", result["status"])
        self.assertTrue(any("orphan journal lines" in item for item in result["failures"]))

    def test_wrong_profile_version_binding_is_rejected(self):
        expected, transactions, ledger = evidence()
        transactions["profile_version"] = 2
        with self.assertRaisesRegex(EvidenceError, "profile binding"):
            self.run_oracle(expected, transactions, ledger)

    def test_snapshot_age_or_skew_overrides_cannot_relax_fixed_caps(self):
        expected, transactions, ledger = evidence()
        with self.assertRaisesRegex(EvidenceError, "cannot exceed"):
            verify_documents(
                expected, transactions, ledger, HASHES, now=NOW,
                max_snapshot_age_seconds=86_401,
            )
        with self.assertRaisesRegex(EvidenceError, "cannot exceed"):
            verify_documents(
                expected, transactions, ledger, HASHES, now=NOW,
                max_snapshot_skew_seconds=301,
            )

    def test_effective_snapshot_policy_is_recorded(self):
        expected, transactions, ledger = evidence()
        result = verify_documents(
            expected, transactions, ledger, HASHES, now=NOW,
            max_snapshot_age_seconds=300, max_snapshot_skew_seconds=60,
        )
        self.assertEqual({
            "maximum_age_seconds": 300,
            "maximum_age_cap_seconds": 86_400,
            "maximum_cross_source_skew_seconds": 60,
            "maximum_cross_source_skew_cap_seconds": 300,
        }, result["snapshot_policy"])

    def test_empty_intended_set_cannot_pass(self):
        expected, _, _ = evidence()
        expected["instructions"] = []
        with self.assertRaisesRegex(EvidenceError, "non-empty list"):
            self.run_oracle(expected=expected)


if __name__ == "__main__":
    unittest.main()
