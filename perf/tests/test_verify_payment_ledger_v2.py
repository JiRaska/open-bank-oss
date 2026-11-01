#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Synthetic schema-v2 tests for causal transaction/ledger closure and ambient census."""

from __future__ import annotations

import copy
import importlib.util
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "verify-payment-ledger-v2.py"
SPEC = importlib.util.spec_from_file_location("verify_payment_ledger_v2", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("could not load schema-v2 verifier")
VERIFIER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VERIFIER
SPEC.loader.exec_module(VERIFIER)

NOW = datetime(2026, 10, 28, 12, 0, tzinfo=timezone.utc)
RUN_ID = "pilot-001"
KEY = f"capacity-pilot:{RUN_ID}:instruction-001"
TX_ID = "20000000-0000-0000-0000-000000000001"
ENTRY_ID = "30000000-0000-0000-0000-000000000001"
SOURCE = "10000000-0000-0000-0000-000000000001"
TARGET = "10000000-0000-0000-0000-000000000002"
CONTROL_GL = VERIFIER.V1.CZK_DEPOSIT_CONTROL_GL_ID
ENTRY_DATE = "2026-10-28"
HASHES = {"expected": "a" * 64, "transactions": "b" * 64, "ledger": "c" * 64}


def evidence():
    started = NOW - timedelta(minutes=3)
    ended = NOW - timedelta(minutes=2)
    captured = NOW - timedelta(minutes=1)
    entry = {
        "id": ENTRY_ID, "entry_date": ENTRY_DATE, "transaction_id": TX_ID,
        "status": "POSTED", "reversal_of": None, "created_at": (ended - timedelta(seconds=1)).isoformat(),
        "synthetic": True,
    }
    lines = [
        {
            "id": "40000000-0000-0000-0000-000000000001", "journal_id": ENTRY_ID,
            "side": "D", "amount": "10.000000", "currency_code": "CZK",
            "base_amount": "10.000000", "base_currency": "CZK",
            "gl_account_id": CONTROL_GL, "sub_account_id": SOURCE,
        },
        {
            "id": "40000000-0000-0000-0000-000000000002", "journal_id": ENTRY_ID,
            "side": "C", "amount": "10.000000", "currency_code": "CZK",
            "base_amount": "10.000000", "base_currency": "CZK",
            "gl_account_id": CONTROL_GL, "sub_account_id": TARGET,
        },
    ]
    expected = {
        "schema_version": 2, "run_id": RUN_ID,
        "profile_id": "cz-current-account-5m-v1", "profile_version": 1,
        "scope": "internal-czk-transfer-pilot",
        "started_at": started.isoformat(), "ended_at": ended.isoformat(),
        "instructions": [{
            "instruction_id": "instruction-001", "idempotency_key": KEY, "type": "TRANSFER",
            "amount": "10.00", "currency_code": "CZK",
            "source_account_id": SOURCE, "target_account_id": TARGET,
            "observed_transaction_id": TX_ID,
        }],
    }
    transactions = {
        "schema_version": 2, "run_id": RUN_ID, "profile_id": "cz-current-account-5m-v1",
        "profile_version": 1, "source": "transaction-service", "captured_at": captured.isoformat(),
        "claims": [{"idempotency_key": KEY, "transaction_id": TX_ID, "booking_date": ENTRY_DATE}],
        "rows": [{
            "id": TX_ID, "booking_date": ENTRY_DATE, "idempotency_key": KEY,
            "type": "TRANSFER", "status": "COMPLETED", "amount": "10.000000",
            "currency_code": "CZK", "source_account_id": SOURCE, "target_account_id": TARGET,
            "reversal_of": None,
        }],
    }
    ledger = {
        "schema_version": 2, "run_id": RUN_ID, "profile_id": "cz-current-account-5m-v1",
        "profile_version": 1, "source": "ledger-service", "captured_at": captured.isoformat(),
        "ledger_idempotency": [{
            "idempotency_key": f"workflow-{TX_ID}-ledger", "journal_id": ENTRY_ID,
            "journal_entry_date": ENTRY_DATE, "created_at": (ended - timedelta(seconds=2)).isoformat(),
        }],
        "journal_entries": [entry], "journal_lines": lines,
        "gl_accounts": [{"id": CONTROL_GL, "code": "2100", "currency_code": "CZK", "is_enabled": True}],
        "window_journal_entries": [copy.deepcopy(entry)],
        "window_journal_lines": copy.deepcopy(lines),
        "orphan_journal_lines": [],
    }
    return expected, transactions, ledger


class VerifyPaymentLedgerV2Test(unittest.TestCase):
    def run_oracle(self, expected=None, transactions=None, ledger=None):
        base = evidence()
        return VERIFIER.verify_documents(
            expected or base[0], transactions or base[1], ledger or base[2], HASHES, now=NOW
        )

    def test_causal_claim_row_pointer_and_journal_pass_with_clear_window(self):
        result = self.run_oracle()
        self.assertEqual("pass", result["status"])
        self.assertEqual("pass", result["linked_run_status"])
        self.assertEqual("clear", result["ambient_census_status"])
        self.assertEqual("pass", result["linked_v1_financial_result"]["status"])

    def test_missing_optional_observed_api_id_does_not_invalidate_durable_chain(self):
        expected, _, _ = evidence()
        del expected["instructions"][0]["observed_transaction_id"]
        result = self.run_oracle(expected=expected)
        self.assertEqual("pass", result["status"])

    def test_unrelated_window_journal_is_retained_and_blocks_overall_pass(self):
        _, _, ledger = evidence()
        ledger["window_journal_entries"].append({
            "id": "30000000-0000-0000-0000-000000000099", "entry_date": ENTRY_DATE,
            "transaction_id": "20000000-0000-0000-0000-000000000099",
            "status": "POSTED", "reversal_of": None,
            "created_at": (NOW - timedelta(minutes=2, seconds=1)).isoformat(), "synthetic": False,
        })
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("pass", result["linked_run_status"])
        self.assertEqual("inconclusive", result["status"])
        self.assertEqual(1, result["counts"]["unattributed_window_entries"])

    def test_window_header_same_identity_must_match_causal_row(self):
        _, _, ledger = evidence()
        ledger["window_journal_entries"][0]["status"] = "FAILED"
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("pass", result["linked_run_status"])
        self.assertEqual("unresolved", result["ambient_census_status"])
        self.assertEqual("inconclusive", result["status"])
        self.assertTrue(any("differs from its causal snapshot" in item
                            for item in result["ambient_census_issues"]))

    def test_window_header_census_must_include_in_window_causal_header(self):
        _, _, ledger = evidence()
        ledger["window_journal_entries"] = []
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("pass", result["linked_run_status"])
        self.assertEqual("inconclusive", result["status"])
        self.assertTrue(any("missing from the independent window census" in item
                            for item in result["ambient_census_issues"]))

    def test_window_line_without_a_window_header_is_unresolved(self):
        _, _, ledger = evidence()
        extra = copy.deepcopy(ledger["window_journal_lines"][0])
        extra["id"] = "40000000-0000-0000-0000-000000000099"
        extra["journal_id"] = "30000000-0000-0000-0000-000000000099"
        ledger["window_journal_lines"].append(extra)
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("pass", result["linked_run_status"])
        self.assertEqual("inconclusive", result["status"])
        self.assertTrue(any("has no header in the window census" in item
                            for item in result["ambient_census_issues"]))

    def test_window_lines_must_match_causal_lines_for_in_window_header(self):
        _, _, ledger = evidence()
        ledger["window_journal_lines"].pop()
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("pass", result["linked_run_status"])
        self.assertEqual("inconclusive", result["status"])
        self.assertTrue(any("differ from the causal snapshot" in item
                            for item in result["ambient_census_issues"]))

    def test_global_orphan_census_remains_nonzero_even_when_run_join_passes(self):
        _, _, ledger = evidence()
        ledger["orphan_journal_lines"] = [{
            "id": "40000000-0000-0000-0000-000000000099",
            "journal_id": "30000000-0000-0000-0000-000000000099",
        }]
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("pass", result["linked_run_status"])
        self.assertEqual("inconclusive", result["status"])
        self.assertEqual(1, result["counts"]["whole_table_orphan_journal_lines"])

    def test_missing_claim_fails_even_when_transaction_row_and_effect_exist(self):
        _, transactions, _ = evidence()
        transactions["claims"] = []
        result = self.run_oracle(transactions=transactions)
        self.assertEqual("fail", result["linked_run_status"])
        self.assertTrue(any("missing V22 idempotency claim" in item for item in result["linked_failures"]))

    def test_missing_transaction_row_fails_but_retained_claim_still_closes_ledger_query(self):
        _, transactions, _ = evidence()
        transactions["rows"] = []
        result = self.run_oracle(transactions=transactions)
        self.assertEqual("fail", result["linked_run_status"])
        self.assertTrue(any("missing transaction row" in item for item in result["linked_failures"]))

    def test_observed_api_id_must_match_persisted_claim(self):
        expected, _, _ = evidence()
        expected["instructions"][0]["observed_transaction_id"] = "20000000-0000-0000-0000-000000000099"
        result = self.run_oracle(expected=expected)
        self.assertEqual("fail", result["linked_run_status"])
        self.assertTrue(any("observed API transaction ID differs" in item for item in result["linked_failures"]))

    def test_missing_expected_ledger_key_fails(self):
        _, _, ledger = evidence()
        ledger["ledger_idempotency"] = []
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("fail", result["linked_run_status"])
        self.assertTrue(any("missing ledger idempotency row" in item for item in result["linked_failures"]))

    def test_unexpected_alias_key_to_run_journal_fails(self):
        _, _, ledger = evidence()
        alias = copy.deepcopy(ledger["ledger_idempotency"][0])
        alias["idempotency_key"] = "unexpected-alias-key"
        ledger["ledger_idempotency"].append(alias)
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("fail", result["linked_run_status"])
        self.assertTrue(any("unexpected ledger idempotency key" in item
                            for item in result["linked_failures"]))

    def test_dangling_idempotency_pointer_fails(self):
        _, _, ledger = evidence()
        dangling_id = "30000000-0000-0000-0000-000000000099"
        ledger["ledger_idempotency"][0]["journal_id"] = dangling_id
        orphan = copy.deepcopy(ledger["journal_lines"][0])
        orphan["id"] = "40000000-0000-0000-0000-000000000099"
        orphan["journal_id"] = dangling_id
        ledger["journal_lines"].append(orphan)
        ledger["orphan_journal_lines"] = [{"id": orphan["id"], "journal_id": dangling_id}]
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("fail", result["linked_run_status"])
        self.assertTrue(any("has no journal header" in item for item in result["linked_failures"]))
        self.assertEqual(1, result["counts"]["whole_table_orphan_journal_lines"])

    def test_pointer_wrong_date_fails_even_when_header_is_found_by_id(self):
        _, _, ledger = evidence()
        ledger["ledger_idempotency"][0]["journal_entry_date"] = "2026-10-27"
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("fail", result["linked_run_status"])
        self.assertTrue(any("has no journal header" in item for item in result["linked_failures"]))

    def test_matching_ledger_dates_must_equal_transaction_booking_date(self):
        _, _, ledger = evidence()
        wrong_date = "2026-10-27"
        ledger["ledger_idempotency"][0]["journal_entry_date"] = wrong_date
        ledger["journal_entries"][0]["entry_date"] = wrong_date
        ledger["window_journal_entries"][0]["entry_date"] = wrong_date
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("fail", result["linked_run_status"])
        self.assertTrue(any("differs from transaction booking date" in item
                            for item in result["linked_failures"]))

    def test_pointer_selected_wrong_date_duplicate_is_visible_and_fails(self):
        _, _, ledger = evidence()
        wrong_date = copy.deepcopy(ledger["journal_entries"][0])
        wrong_date["entry_date"] = "2026-10-27"
        ledger["journal_entries"].append(wrong_date)
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("fail", result["linked_run_status"])
        self.assertTrue(any("multiple partitions" in item for item in result["linked_failures"]))

    def test_pointer_selected_same_id_wrong_transaction_is_visible_outside_window(self):
        _, _, ledger = evidence()
        wrong_partition = copy.deepcopy(ledger["journal_entries"][0])
        wrong_partition["entry_date"] = "2026-10-27"
        wrong_partition["transaction_id"] = "20000000-0000-0000-0000-000000000099"
        wrong_partition["created_at"] = (NOW - timedelta(days=3)).isoformat()
        ledger["journal_entries"].append(wrong_partition)
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("fail", result["linked_run_status"])
        self.assertTrue(any("multiple partitions" in item for item in result["linked_failures"]))

    def test_second_journal_for_same_transaction_fails_even_with_another_idempotency_key(self):
        _, _, ledger = evidence()
        extra = copy.deepcopy(ledger["journal_entries"][0])
        extra["id"] = "30000000-0000-0000-0000-000000000099"
        ledger["journal_entries"].append(extra)
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("fail", result["linked_run_status"])
        self.assertTrue(any("duplicate effects" in item for item in result["linked_failures"]))

    def test_late_same_id_journal_is_in_causal_census_and_fails_run_window(self):
        _, _, ledger = evidence()
        ledger["journal_entries"][0]["created_at"] = (NOW - timedelta(minutes=10)).isoformat()
        ledger["window_journal_entries"] = []
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("fail", result["linked_run_status"])
        self.assertTrue(any("lies outside the declared run window" in item for item in result["linked_failures"]))

    def test_pending_or_failed_intended_transaction_is_not_filtered_out(self):
        _, transactions, _ = evidence()
        transactions["rows"][0]["status"] = "FAILED"
        result = self.run_oracle(transactions=transactions)
        self.assertEqual("fail", result["linked_run_status"])
        self.assertTrue(any("expected COMPLETED" in item for item in result["linked_failures"]))

    def test_unexpected_run_prefix_claim_is_not_discarded(self):
        _, transactions, _ = evidence()
        extra = copy.deepcopy(transactions["claims"][0])
        extra["idempotency_key"] = f"capacity-pilot:{RUN_ID}:unknown"
        transactions["claims"].append(extra)
        result = self.run_oracle(transactions=transactions)
        self.assertEqual("fail", result["linked_run_status"])
        self.assertTrue(any("unknown run-scoped transaction key" in item for item in result["linked_failures"]))

    def test_claimed_transaction_id_also_selects_row_with_other_key_and_date(self):
        _, transactions, _ = evidence()
        other = copy.deepcopy(transactions["rows"][0])
        other["booking_date"] = "2026-10-27"
        other["idempotency_key"] = "unrelated-key"
        transactions["rows"].append(other)
        result = self.run_oracle(transactions=transactions)
        self.assertEqual("fail", result["linked_run_status"])
        self.assertTrue(any("maps to 2 transaction rows" in item for item in result["linked_failures"]))

    def test_run_linked_window_header_missing_from_causal_snapshot_fails_closed(self):
        _, _, ledger = evidence()
        ledger["journal_entries"] = []
        result = self.run_oracle(ledger=ledger)
        self.assertEqual("fail", result["linked_run_status"])
        self.assertTrue(any("run transaction" in item and "missing from causal closure" in item
                            for item in result["linked_failures"]))


if __name__ == "__main__":
    unittest.main()
