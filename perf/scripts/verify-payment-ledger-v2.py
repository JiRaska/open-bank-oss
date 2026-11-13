#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Check run-key-to-ledger joins and preserve the independent ambient-window census (schema v2)."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

V1_PATH = Path(__file__).with_name("verify-payment-ledger.py")
V1_SPEC = importlib.util.spec_from_file_location("verify_payment_ledger_v1", V1_PATH)
if V1_SPEC is None or V1_SPEC.loader is None:  # pragma: no cover
    raise RuntimeError("cannot load v1 financial invariant implementation")
V1 = importlib.util.module_from_spec(V1_SPEC)
V1_SPEC.loader.exec_module(V1)
EvidenceError = V1.EvidenceError
SCHEMA_VERSION = 2
RUN_KEY_PREFIX = V1.RUN_KEY_PREFIX
PROFILE_ID = V1.PROFILE_ID
PROFILE_VERSION = V1.PROFILE_VERSION


def _keys(document: dict[str, Any], expected: set[str], label: str) -> None:
    if set(document) != expected:
        missing = sorted(expected - set(document))
        extra = sorted(set(document) - expected)
        raise EvidenceError(f"{label} fields mismatch; missing={missing}, extra={extra}")


def _snapshot(document: dict[str, Any], source: str, run_id: str, fields: set[str]) -> None:
    _keys(document, fields, source)
    if type(document["schema_version"]) is not int or document["schema_version"] != SCHEMA_VERSION:
        raise EvidenceError(f"{source}.schema_version must be {SCHEMA_VERSION}")
    if document["run_id"] != run_id or document["profile_id"] != PROFILE_ID:
        raise EvidenceError(f"{source} run/profile binding does not match expected input")
    if type(document["profile_version"]) is not int or document["profile_version"] != PROFILE_VERSION:
        raise EvidenceError(f"{source}.profile_version must be {PROFILE_VERSION}")
    if document["source"] != source:
        raise EvidenceError(f"snapshot source must be {source}")
    V1._timestamp(document["captured_at"], f"{source}.captured_at")


def _list(rows: Any, label: str, identity: tuple[str, ...]) -> list[dict[str, Any]]:
    return V1._unique_rows(rows, label, identity)


def _uuid(value: Any, label: str) -> str:
    result = V1._uuid(value, label)
    assert result is not None
    return result


def verify_documents(
    expected: dict[str, Any],
    transactions: dict[str, Any],
    ledger: dict[str, Any],
    hashes: dict[str, str],
    *,
    now: datetime | None = None,
    max_snapshot_age_seconds: int = V1.MAX_SNAPSHOT_AGE_SECONDS,
    max_snapshot_skew_seconds: int = V1.MAX_SNAPSHOT_SKEW_SECONDS,
) -> dict[str, Any]:
    """Return linked-run status separately from the preserved ambient census."""
    if not isinstance(expected, dict):
        raise EvidenceError("expected root must be an object")
    _keys(expected, {
        "schema_version", "run_id", "profile_id", "profile_version", "scope", "started_at", "ended_at", "instructions",
    }, "expected")
    if type(expected["schema_version"]) is not int or expected["schema_version"] != SCHEMA_VERSION:
        raise EvidenceError(f"expected.schema_version must be {SCHEMA_VERSION}")
    run_id = V1._string(expected["run_id"], "expected.run_id")
    if not V1.RUN_ID_RE.fullmatch(run_id):
        raise EvidenceError("expected.run_id has invalid characters")
    if expected["profile_id"] != PROFILE_ID or type(expected["profile_version"]) is not int or expected["profile_version"] != PROFILE_VERSION:
        raise EvidenceError("expected profile binding is invalid")
    if expected["scope"] != "internal-czk-transfer-pilot":
        raise EvidenceError("schema v2 supports internal-czk-transfer-pilot only")
    if not isinstance(expected["instructions"], list) or not expected["instructions"]:
        raise EvidenceError("expected.instructions must be a non-empty list")

    v1_instructions: list[dict[str, Any]] = []
    observed_ids: dict[str, str] = {}
    for index, item in enumerate(expected["instructions"]):
        if not isinstance(item, dict):
            raise EvidenceError(f"expected.instructions[{index}] must be an object")
        allowed = {
            "instruction_id", "idempotency_key", "type", "amount", "currency_code",
            "source_account_id", "target_account_id", "observed_transaction_id",
        }
        required = allowed - {"observed_transaction_id"}
        if not required <= set(item) or set(item) - allowed:
            raise EvidenceError(f"expected.instructions[{index}] fields mismatch")
        if "observed_transaction_id" in item:
            observed_ids[item["idempotency_key"]] = _uuid(
                item["observed_transaction_id"], f"expected.instructions[{index}].observed_transaction_id"
            )
        v1_instructions.append({key: value for key, value in item.items() if key != "observed_transaction_id"})

    v1_expected = dict(expected, schema_version=1, instructions=v1_instructions)
    intended = V1._instruction_rows(v1_expected, run_id)
    _snapshot(transactions, "transaction-service", run_id, {
        "schema_version", "run_id", "profile_id", "profile_version", "source", "captured_at", "claims", "rows",
    })
    _snapshot(ledger, "ledger-service", run_id, {
        "schema_version", "run_id", "profile_id", "profile_version", "source", "captured_at",
        "ledger_idempotency", "journal_entries", "journal_lines", "gl_accounts",
        "window_journal_entries", "window_journal_lines", "orphan_journal_lines",
    })

    claims = _list(transactions["claims"], "transaction claims", ("idempotency_key",))
    rows = _list(transactions["rows"], "transaction rows", ("id", "booking_date"))
    claims_by_key: dict[str, dict[str, Any]] = {}
    rows_by_key: dict[str, list[dict[str, Any]]] = {}
    rows_by_id: dict[str, list[dict[str, Any]]] = {}
    expected_prefix = f"{RUN_KEY_PREFIX}{run_id}:"
    for i, claim in enumerate(claims):
        _keys(claim, {"idempotency_key", "transaction_id", "booking_date"}, f"transaction claims[{i}]")
        key = V1._string(claim["idempotency_key"], f"transaction claims[{i}].idempotency_key")
        if not key.startswith(expected_prefix):
            raise EvidenceError(f"transaction claim contains out-of-run key {key}")
        _uuid(claim["transaction_id"], f"transaction claims[{i}].transaction_id")
        V1._string(claim["booking_date"], f"transaction claims[{i}].booking_date")
        claims_by_key[key] = claim
    for i, row in enumerate(rows):
        _keys(row, {
            "id", "booking_date", "idempotency_key", "type", "status", "amount", "currency_code",
            "source_account_id", "target_account_id", "reversal_of",
        }, f"transaction rows[{i}]")
        key = V1._string(row["idempotency_key"], f"transaction rows[{i}].idempotency_key")
        txid = _uuid(row["id"], f"transaction rows[{i}].id")
        if not key.startswith(expected_prefix) and txid not in {
            _uuid(claim["transaction_id"], "transaction claim transaction_id") for claim in claims
        }:
            raise EvidenceError(f"transaction row contains out-of-run key {key}")
        rows_by_key.setdefault(key, []).append(row)
        rows_by_id.setdefault(txid, []).append(row)

    contract_errors: list[str] = []
    expected_keys = set(intended)
    for key in sorted(expected_keys | set(claims_by_key) | set(rows_by_key)):
        claim = claims_by_key.get(key)
        key_rows = rows_by_key.get(key, [])
        claim_id = _uuid(claim["transaction_id"], f"claim[{key}].transaction_id") if claim else None
        claim_rows = rows_by_id.get(claim_id, []) if claim_id else []
        row = next((candidate for candidate in claim_rows if candidate["booking_date"] == claim["booking_date"]
                    and candidate["idempotency_key"] == key), None) if claim else (key_rows[0] if key_rows else None)
        if key not in expected_keys:
            contract_errors.append(f"unknown run-scoped transaction key {key}")
            continue
        if claim is None:
            contract_errors.append(f"missing V22 idempotency claim for {key}")
        if row is None:
            contract_errors.append(f"missing transaction row for {key}")
        if claim is not None and len(claim_rows) != 1:
            contract_errors.append(f"V22 claim for {key} maps to {len(claim_rows)} transaction rows by id; expected one")
        if len(key_rows) != 1:
            contract_errors.append(f"transaction key {key} has {len(key_rows)} run-prefix rows; expected one")
        if claim is not None and row is not None:
            row_id = _uuid(row["id"], f"transaction[{key}].id")
            if (claim_id, claim["booking_date"]) != (row_id, row["booking_date"]):
                contract_errors.append(f"V22 claim identity differs from transaction row for {key}")
            if key in observed_ids and observed_ids[key] != claim_id:
                contract_errors.append(f"observed API transaction ID differs from V22 claim for {key}")
        if row is not None and key in observed_ids and observed_ids[key] != _uuid(row["id"], f"transaction[{key}].id"):
            contract_errors.append(f"observed API transaction ID differs from transaction row for {key}")

    # V1 remains the financial-invariant implementation. Its input here is the causal closure
    # only; ambient and whole-table orphan evidence is preserved separately below.
    claim_ids = {_uuid(claim["transaction_id"], f"claim[{key}].transaction_id") for key, claim in claims_by_key.items()}
    row_ids = {_uuid(row["id"], "transaction row id") for row in rows}
    all_run_ids = claim_ids | row_ids | set(observed_ids.values())
    expected_ledger_keys = {f"workflow-{txid}-ledger" for txid in all_run_ids}
    ledger_keys = _list(ledger["ledger_idempotency"], "ledger idempotency", ("idempotency_key",))
    ledger_by_key: dict[str, dict[str, Any]] = {}
    for i, item in enumerate(ledger_keys):
        _keys(item, {"idempotency_key", "journal_id", "journal_entry_date", "created_at"}, f"ledger idempotency[{i}]")
        key = V1._string(item["idempotency_key"], f"ledger idempotency[{i}].idempotency_key")
        if key not in expected_ledger_keys:
            contract_errors.append(f"unexpected ledger idempotency key {key}")
        ledger_by_key[key] = item
        _uuid(item["journal_id"], f"ledger idempotency[{i}].journal_id")
        V1._string(item["journal_entry_date"], f"ledger idempotency[{i}].journal_entry_date")
        V1._timestamp(item["created_at"], f"ledger idempotency[{i}].created_at")
    for key in sorted(expected_ledger_keys - set(ledger_by_key)):
        contract_errors.append(f"missing ledger idempotency row for {key}")

    entries = _list(ledger["journal_entries"], "causal journal entries", ("id", "entry_date"))
    causal_ids: set[str] = set()
    entries_by_pair: dict[tuple[str, str], dict[str, Any]] = {}
    entries_by_tx: dict[str, list[dict[str, Any]]] = {}
    for i, entry in enumerate(entries):
        _keys(entry, {"id", "entry_date", "transaction_id", "status", "reversal_of", "created_at", "synthetic"}, f"causal journal entries[{i}]")
        entry_id = _uuid(entry["id"], f"causal journal entries[{i}].id")
        txid = _uuid(entry["transaction_id"], f"causal journal entries[{i}].transaction_id")
        pair = (entry_id, V1._string(entry["entry_date"], f"causal journal entries[{i}].entry_date"))
        causal_ids.add(entry_id)
        entries_by_pair[pair] = entry
        entries_by_tx.setdefault(txid, []).append(entry)
    for key, claim in claims_by_key.items():
        txid = _uuid(claim["transaction_id"], f"claim[{key}].transaction_id")
        ledger_key = f"workflow-{txid}-ledger"
        item = ledger_by_key.get(ledger_key)
        if item is not None:
            pair = (_uuid(item["journal_id"], f"ledger idempotency[{ledger_key}].journal_id"), item["journal_entry_date"])
            if pair[1] != claim["booking_date"]:
                contract_errors.append(f"ledger idempotency date for {ledger_key} differs from transaction booking date")
            entry = entries_by_pair.get(pair)
            if entry is None:
                contract_errors.append(f"ledger idempotency pointer for {ledger_key} has no journal header")
            else:
                if V1._string(entry["entry_date"], f"journal[{pair[0]}].entry_date") != claim["booking_date"]:
                    contract_errors.append(f"journal date for {ledger_key} differs from transaction booking date")
                if _uuid(entry["transaction_id"], f"journal[{pair[0]}].transaction_id") != txid:
                    contract_errors.append(f"ledger idempotency pointer for {ledger_key} maps to another transaction")
    for txid in all_run_ids:
        tx_entries = entries_by_tx.get(txid, [])
        if len(tx_entries) > 1:
            contract_errors.append(f"transaction {txid} has {len(tx_entries)} causal journals; duplicate effects are forbidden")

    window_entries = _list(ledger["window_journal_entries"], "window journal entries", ("id", "entry_date"))
    causal_pairs = set(entries_by_pair)
    start = V1._timestamp(expected["started_at"], "expected.started_at")
    end = V1._timestamp(expected["ended_at"], "expected.ended_at")
    ambient_entries: list[dict[str, Any]] = []
    census_errors: list[str] = []
    window_by_pair: dict[tuple[str, str], dict[str, Any]] = {}
    for i, entry in enumerate(window_entries):
        _keys(entry, {"id", "entry_date", "transaction_id", "status", "reversal_of", "created_at", "synthetic"}, f"window journal entries[{i}]")
        pair = (_uuid(entry["id"], f"window journal entries[{i}].id"), V1._string(entry["entry_date"], f"window journal entries[{i}].entry_date"))
        txid = _uuid(entry["transaction_id"], f"window journal entries[{i}].transaction_id")
        created = V1._timestamp(entry["created_at"], f"window journal entries[{i}].created_at")
        if not start <= created <= end:
            raise EvidenceError(f"window census contains journal {pair[0]} outside declared time window")
        window_by_pair[pair] = entry
        if pair in causal_pairs:
            if entry != entries_by_pair[pair]:
                census_errors.append(f"window journal header {pair[0]} differs from its causal snapshot row")
            continue
        if txid in all_run_ids:
            contract_errors.append(f"run transaction {txid} has a window journal missing from causal closure")
        ambient_entries.append(entry)

    for pair, entry in entries_by_pair.items():
        created = V1._timestamp(entry["created_at"], f"causal journal[{pair[0]}].created_at")
        if start <= created <= end and pair not in window_by_pair:
            census_errors.append(f"in-window causal journal {pair[0]} is missing from the independent window census")

    linked_ids = set(causal_ids)
    linked_ids.update(_uuid(item["journal_id"], "ledger idempotency journal_id") for item in ledger_by_key.values())
    orphan_lines = _list(ledger["orphan_journal_lines"], "orphan journal lines", ("id",))
    scoped_orphans: list[dict[str, Any]] = []
    for i, item in enumerate(orphan_lines):
        _keys(item, {"id", "journal_id"}, f"orphan journal lines[{i}]")
        _uuid(item["id"], f"orphan journal lines[{i}].id")
        if _uuid(item["journal_id"], f"orphan journal lines[{i}].journal_id") in linked_ids:
            scoped_orphans.append(item)
    # Validate the independent line census and compare it with causal lines for in-window headers.
    causal_lines = _list(ledger["journal_lines"], "causal journal lines", ("id",))
    causal_lines_by_journal: dict[str, list[dict[str, Any]]] = {}
    line_fields = {
        "id", "journal_id", "side", "amount", "currency_code", "base_amount", "base_currency",
        "gl_account_id", "sub_account_id",
    }
    for i, line in enumerate(causal_lines):
        _keys(line, line_fields, f"causal journal lines[{i}]")
        journal_id = _uuid(line["journal_id"], f"causal journal lines[{i}].journal_id")
        causal_lines_by_journal.setdefault(journal_id, []).append(line)

    window_lines = _list(ledger["window_journal_lines"], "window journal lines", ("id",))
    window_lines_by_journal: dict[str, list[dict[str, Any]]] = {}
    window_header_ids = {pair[0] for pair in window_by_pair}
    for i, line in enumerate(window_lines):
        _keys(line, line_fields, f"window journal lines[{i}]")
        journal_id = _uuid(line["journal_id"], f"window journal lines[{i}].journal_id")
        if journal_id not in window_header_ids:
            census_errors.append(f"window journal line {line['id']} has no header in the window census")
        window_lines_by_journal.setdefault(journal_id, []).append(line)

    for pair, entry in entries_by_pair.items():
        created = V1._timestamp(entry["created_at"], f"causal journal[{pair[0]}].created_at")
        if not start <= created <= end or pair not in window_by_pair:
            continue
        journal_id = pair[0]
        causal_for_id = sorted(causal_lines_by_journal.get(journal_id, []), key=lambda row: row["id"])
        window_for_id = sorted(window_lines_by_journal.get(journal_id, []), key=lambda row: row["id"])
        if causal_for_id != window_for_id:
            census_errors.append(f"window journal lines for {journal_id} differ from the causal snapshot")

    v1_ledger = {
        "schema_version": 1, "run_id": run_id, "profile_id": PROFILE_ID, "profile_version": PROFILE_VERSION,
        "source": "ledger-service", "captured_at": ledger["captured_at"],
        "journal_entries": entries, "journal_lines": ledger["journal_lines"],
        "gl_accounts": ledger["gl_accounts"], "orphan_journal_lines": scoped_orphans,
    }
    v1_transactions = {
        "schema_version": 1, "run_id": run_id, "profile_id": PROFILE_ID, "profile_version": PROFILE_VERSION,
        "source": "transaction-service", "captured_at": transactions["captured_at"], "rows": rows,
    }
    linked = V1.verify_documents(
        v1_expected, v1_transactions, v1_ledger, hashes, now=now,
        max_snapshot_age_seconds=max_snapshot_age_seconds,
        max_snapshot_skew_seconds=max_snapshot_skew_seconds,
    )
    linked_status = "fail" if contract_errors or linked["status"] != "pass" else "pass"
    ambient_status = "unresolved" if ambient_entries or orphan_lines or census_errors else "clear"
    status = "fail" if linked_status == "fail" else ("inconclusive" if ambient_status != "clear" else "pass")
    failures = contract_errors + linked["failures"]
    return {
        "schema_version": SCHEMA_VERSION,
        "oracle": "internal-czk-transfer-db-ledger-v2",
        "run_id": run_id,
        "profile_id": PROFILE_ID,
        "profile_version": PROFILE_VERSION,
        "scope": "internal-czk-transfer-pilot",
        "status": status,
        "linked_run_status": linked_status,
        "ambient_census_status": ambient_status,
        "qualification": "not-established-by-this-oracle",
        "attestation_boundary": "offline-artifact-integrity-only; signature_and_workflow_provenance_not_verified",
        "counts": {
            "intended_instructions": len(intended),
            "observed_api_ids": len(observed_ids),
            "transaction_claims": len(claims),
            "transaction_rows": len(rows),
            "linked_journal_entries": len(entries),
            "linked_journal_lines": len(ledger["journal_lines"]),
            "window_journal_entries": len(window_entries),
            "unattributed_window_entries": len(ambient_entries),
            "window_journal_lines": len(window_lines),
            "whole_table_orphan_journal_lines": len(orphan_lines),
        },
        "linked_failures": failures,
        "ambient_unattributed_entries": [
            {"id": item["id"], "entry_date": item["entry_date"], "transaction_id": item["transaction_id"]}
            for item in ambient_entries
        ],
        "ambient_census_issues": census_errors,
        "linked_v1_financial_result": linked,
        "derived_from": hashes,
        "evaluated_at": (now or datetime.now(timezone.utc)).astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected", required=True, type=Path)
    parser.add_argument("--transactions", required=True, type=Path)
    parser.add_argument("--ledger", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--max-snapshot-age-seconds", type=int, default=V1.MAX_SNAPSHOT_AGE_SECONDS)
    parser.add_argument("--max-snapshot-skew-seconds", type=int, default=V1.MAX_SNAPSHOT_SKEW_SECONDS)
    args = parser.parse_args()
    paths = {"expected": args.expected, "transactions": args.transactions, "ledger": args.ledger}
    hashes: dict[str, str] = {}
    try:
        expected, hashes["expected"] = V1._load_json(args.expected)
        transactions, hashes["transactions"] = V1._load_json(args.transactions)
        ledger, hashes["ledger"] = V1._load_json(args.ledger)
        result = verify_documents(
            expected, transactions, ledger, hashes,
            max_snapshot_age_seconds=args.max_snapshot_age_seconds,
            max_snapshot_skew_seconds=args.max_snapshot_skew_seconds,
        )
    except (EvidenceError, OSError, KeyError, TypeError, AssertionError) as exc:
        for name, path in paths.items():
            if name not in hashes:
                try:
                    hashes[name] = hashlib.sha256(path.read_bytes()).hexdigest()
                except OSError:
                    pass
        result = {
            "schema_version": SCHEMA_VERSION, "oracle": "internal-czk-transfer-db-ledger-v2",
            "profile_id": PROFILE_ID, "profile_version": PROFILE_VERSION,
            "status": "fail", "linked_run_status": "fail", "ambient_census_status": "unresolved",
            "qualification": "not-established-by-this-oracle", "derived_from": hashes,
            "linked_failures": [str(exc)],
        }
    rendered = json.dumps(result, sort_keys=True, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    else:
        sys.stdout.write(rendered)
    return 0 if result["status"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
