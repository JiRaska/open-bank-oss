#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
"""Derive an internal-transfer pilot result from independently extracted database rows."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1
RUN_ID_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}\Z")
UUID_RE = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\Z")
DECIMAL_RE = re.compile(r"(?:0|[1-9][0-9]*)(?:\.[0-9]+)?\Z")
RUN_KEY_PREFIX = "capacity-pilot:"
PROFILE_ID = "cz-current-account-5m-v1"
PROFILE_VERSION = 1
CZK_DEPOSIT_CONTROL_GL_ID = "a0000000-0000-0000-0000-000000000002"
MAX_SNAPSHOT_AGE_SECONDS = 86_400
MAX_SNAPSHOT_SKEW_SECONDS = 300


class EvidenceError(ValueError):
    """An input artifact is malformed or does not support this pilot oracle."""


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise EvidenceError(f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def _load_json(path: Path) -> tuple[dict[str, Any], str]:
    raw = path.read_bytes()
    try:
        document = json.loads(raw, object_pairs_hook=_reject_duplicate_keys)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise EvidenceError(f"{path}: invalid JSON: {exc}") from exc
    if not isinstance(document, dict):
        raise EvidenceError(f"{path}: top-level JSON value must be an object")
    return document, hashlib.sha256(raw).hexdigest()


def _keys(document: dict[str, Any], expected: set[str], label: str) -> None:
    if set(document) != expected:
        missing = sorted(expected - set(document))
        extra = sorted(set(document) - expected)
        raise EvidenceError(f"{label} fields mismatch; missing={missing}, extra={extra}")


def _string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise EvidenceError(f"{label} must be a non-empty string")
    return value


def _uuid(value: Any, label: str, *, nullable: bool = False) -> str | None:
    if nullable and value is None:
        return None
    result = _string(value, label)
    if not UUID_RE.fullmatch(result):
        raise EvidenceError(f"{label} must be a UUID")
    return result.lower()


def _amount(value: Any, label: str) -> Decimal:
    if not isinstance(value, str) or len(value) > 64 or not DECIMAL_RE.fullmatch(value):
        raise EvidenceError(f"{label} must be a plain decimal string")
    try:
        amount = Decimal(value)
    except InvalidOperation as exc:
        raise EvidenceError(f"{label} is not a decimal") from exc
    if not amount.is_finite() or amount <= 0:
        raise EvidenceError(f"{label} must be finite and positive")
    parts = amount.as_tuple()
    digits = list(parts.digits)
    exponent = parts.exponent
    while len(digits) > 1 and digits[-1] == 0:
        digits.pop()
        exponent += 1
    integer_digits = max(len(digits) + exponent, 0)
    if integer_digits > 14:
        raise EvidenceError(f"{label} exceeds transactions.amount NUMERIC(20,6) range")
    if exponent < -2:
        raise EvidenceError(f"{label} is not an exact CZK minor-unit amount")
    return amount


def _minor_units(amount: Decimal) -> int:
    """Convert a validated CZK Decimal to haléř without context-rounded arithmetic."""
    sign, digits, exponent = amount.as_tuple()
    digits = list(digits)
    while len(digits) > 1 and digits[-1] == 0:
        digits = digits[:-1]
        exponent += 1
    coefficient = int("".join(str(digit) for digit in digits))
    shift = exponent + 2
    if shift < 0:
        raise EvidenceError("amount is not an exact CZK minor-unit value")
    minor = coefficient * 10**shift
    return -minor if sign else minor


def _timestamp(value: Any, label: str) -> datetime:
    text = _string(value, label)
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError as exc:
        raise EvidenceError(f"{label} must be an ISO-8601 timestamp") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise EvidenceError(f"{label} must include a timezone")
    return parsed.astimezone(timezone.utc)


def _snapshot(document: dict[str, Any], source: str, run_id: str) -> datetime:
    expected_fields = (
        {"schema_version", "run_id", "profile_id", "profile_version", "source", "captured_at", "rows"}
        if source == "transaction-service"
        else {
            "schema_version", "run_id", "profile_id", "profile_version", "source", "captured_at",
            "journal_entries", "journal_lines", "gl_accounts", "orphan_journal_lines",
        }
    )
    _keys(document, expected_fields, source)
    if type(document["schema_version"]) is not int or document["schema_version"] != SCHEMA_VERSION:
        raise EvidenceError(f"{source} snapshot schema_version must be {SCHEMA_VERSION}")
    if document["run_id"] != run_id:
        raise EvidenceError(f"{source} snapshot run_id does not match expected instructions")
    if (document["profile_id"] != PROFILE_ID or type(document["profile_version"]) is not int
            or document["profile_version"] != PROFILE_VERSION):
        raise EvidenceError(f"{source} snapshot profile binding does not match {PROFILE_ID} version {PROFILE_VERSION}")
    if document["source"] != source:
        raise EvidenceError(f"snapshot source must be {source}")
    captured = _timestamp(document["captured_at"], f"{source}.captured_at")
    return captured


def _instruction_rows(expected: dict[str, Any], run_id: str) -> dict[str, dict[str, Any]]:
    _keys(expected, {
        "schema_version", "run_id", "profile_id", "profile_version", "scope", "started_at", "ended_at", "instructions",
    }, "expected")
    if (type(expected["schema_version"]) is not int or expected["schema_version"] != SCHEMA_VERSION
            or expected["run_id"] != run_id or expected["profile_id"] != PROFILE_ID
            or type(expected["profile_version"]) is not int or expected["profile_version"] != PROFILE_VERSION):
        raise EvidenceError("expected schema_version, run_id, or profile binding is invalid")
    if expected["scope"] != "internal-czk-transfer-pilot":
        raise EvidenceError("this verifier only supports internal-czk-transfer-pilot")
    instructions = expected["instructions"]
    if not isinstance(instructions, list) or not instructions:
        raise EvidenceError("expected.instructions must be a non-empty list")

    rows: dict[str, dict[str, Any]] = {}
    instruction_ids: set[str] = set()
    prefix = f"{RUN_KEY_PREFIX}{run_id}:"
    for index, item in enumerate(instructions):
        label = f"expected.instructions[{index}]"
        if not isinstance(item, dict):
            raise EvidenceError(f"{label} must be an object")
        _keys(item, {
            "instruction_id", "idempotency_key", "type", "amount", "currency_code",
            "source_account_id", "target_account_id",
        }, label)
        instruction_id = _string(item["instruction_id"], f"{label}.instruction_id")
        key = _string(item["idempotency_key"], f"{label}.idempotency_key")
        if instruction_id in instruction_ids:
            raise EvidenceError(f"duplicate intended instruction_id: {instruction_id}")
        if key in rows:
            raise EvidenceError(f"duplicate intended idempotency_key: {key}")
        if not key.startswith(prefix):
            raise EvidenceError(f"{label}.idempotency_key must start with {prefix!r}")
        if key != f"{prefix}{instruction_id}" or len(key) > 100:
            raise EvidenceError(f"{label}.idempotency_key must be the run prefix plus instruction_id and fit varchar(100)")
        if item["type"] != "TRANSFER" or item["currency_code"] != "CZK":
            raise EvidenceError("pilot supports only TRANSFER in CZK")
        source_id = _uuid(item["source_account_id"], f"{label}.source_account_id")
        target_id = _uuid(item["target_account_id"], f"{label}.target_account_id")
        if source_id == target_id:
            raise EvidenceError(f"{label} must transfer between distinct accounts")
        rows[key] = {
            "instruction_id": instruction_id,
            "idempotency_key": key,
            "type": "TRANSFER",
            "amount": _amount(item["amount"], f"{label}.amount"),
            "currency_code": "CZK",
            "source_account_id": source_id,
            "target_account_id": target_id,
        }
        instruction_ids.add(instruction_id)
    return rows


def _unique_rows(rows: Any, label: str, identity: tuple[str, ...]) -> list[dict[str, Any]]:
    if not isinstance(rows, list):
        raise EvidenceError(f"{label} must be a list")
    seen: set[tuple[str, ...]] = set()
    result: list[dict[str, Any]] = []
    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            raise EvidenceError(f"{label}[{index}] must be an object")
        parts = tuple(_string(row.get(field), f"{label}[{index}].{field}") for field in identity)
        if parts in seen:
            raise EvidenceError(f"duplicate snapshot row in {label}: {parts}")
        seen.add(parts)
        result.append(row)
    return result


def verify_documents(
    expected: dict[str, Any],
    transactions: dict[str, Any],
    ledger: dict[str, Any],
    hashes: dict[str, str],
    *,
    now: datetime | None = None,
    max_snapshot_age_seconds: int = MAX_SNAPSHOT_AGE_SECONDS,
    max_snapshot_skew_seconds: int = MAX_SNAPSHOT_SKEW_SECONDS,
) -> dict[str, Any]:
    run_id = _string(expected.get("run_id"), "expected.run_id")
    if not RUN_ID_RE.fullmatch(run_id):
        raise EvidenceError("expected.run_id has invalid characters")
    intended = _instruction_rows(expected, run_id)
    started = _timestamp(expected["started_at"], "expected.started_at")
    ended = _timestamp(expected["ended_at"], "expected.ended_at")
    current = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    if ended <= started or ended > current + timedelta(seconds=60):
        raise EvidenceError("run timestamps are reversed or ended in the future")
    if (type(max_snapshot_age_seconds) is not int or type(max_snapshot_skew_seconds) is not int
            or max_snapshot_age_seconds < 0 or max_snapshot_skew_seconds < 0):
        raise EvidenceError("snapshot age and skew limits must be non-negative")
    if (max_snapshot_age_seconds > MAX_SNAPSHOT_AGE_SECONDS
            or max_snapshot_skew_seconds > MAX_SNAPSHOT_SKEW_SECONDS):
        raise EvidenceError("snapshot age and skew limits cannot exceed the oracle's fixed maximums")

    tx_captured = _snapshot(transactions, "transaction-service", run_id)
    ledger_captured = _snapshot(ledger, "ledger-service", run_id)
    for source, captured in (("transaction-service", tx_captured), ("ledger-service", ledger_captured)):
        if captured < ended:
            raise EvidenceError(f"{source} snapshot predates the run end")
        age = (current - captured).total_seconds()
        if age < -60 or age > max_snapshot_age_seconds:
            raise EvidenceError(f"{source} snapshot is stale or future-dated")
    if abs((tx_captured - ledger_captured).total_seconds()) > max_snapshot_skew_seconds:
        raise EvidenceError("transaction and ledger snapshot timestamps exceed allowed skew")

    errors: list[str] = []
    tx_rows = _unique_rows(transactions["rows"], "transaction rows", ("id", "booking_date"))
    expected_keys = set(intended)
    by_key: dict[str, list[dict[str, Any]]] = defaultdict(list)
    observed_ids: set[str] = set()
    for index, row in enumerate(tx_rows):
        label = f"transaction rows[{index}]"
        key = _string(row.get("idempotency_key"), f"{label}.idempotency_key")
        tx_id = _uuid(row.get("id"), f"{label}.id")
        if not key.startswith(f"{RUN_KEY_PREFIX}{run_id}:"):
            errors.append(f"transaction snapshot contains out-of-run key {key}")
            continue
        by_key[key].append(row)
        if tx_id in observed_ids:
            errors.append(f"transaction id {tx_id} is reused across run rows")
        observed_ids.add(tx_id)
    for key in sorted(expected_keys - set(by_key)):
        errors.append(f"missing transaction row for idempotency key {key}")
    for key in sorted(set(by_key) - expected_keys):
        errors.append(f"unknown run-scoped transaction row for idempotency key {key}")

    transaction_by_key: dict[str, dict[str, Any]] = {}
    txid_to_key: dict[str, str] = {}
    for key in sorted(expected_keys & set(by_key)):
        rows = by_key[key]
        if len(rows) != 1:
            errors.append(f"idempotency key {key} has {len(rows)} transaction rows, expected exactly one")
            continue
        row = rows[0]
        _keys(row, {
            "id", "booking_date", "idempotency_key", "type", "status", "amount", "currency_code",
            "source_account_id", "target_account_id", "reversal_of",
        }, f"transaction[{key}]")
        txid = _uuid(row.get("id"), f"transaction[{key}].id")
        if txid is None:
            continue
        transaction_by_key[key] = row
        txid_to_key[txid] = key
        expected_row = intended[key]
        for field in ("type", "status", "currency_code"):
            actual = _string(row.get(field), f"transaction[{key}].{field}")
            required = "COMPLETED" if field == "status" else expected_row[field]
            if actual != required:
                errors.append(f"transaction {key} has {field}={actual}; expected {required}")
        actual_amount = _amount(row.get("amount"), f"transaction[{key}].amount")
        if _minor_units(actual_amount) != _minor_units(expected_row["amount"]):
            errors.append(f"transaction {key} amount differs from intended amount")
        for field in ("source_account_id", "target_account_id"):
            actual_id = _uuid(row.get(field), f"transaction[{key}].{field}", nullable=True)
            if actual_id != expected_row[field]:
                errors.append(f"transaction {key} {field} differs from intended account")
        if "reversal_of" not in row:
            raise EvidenceError("transaction snapshot lacks reversal_of; extraction contract mismatch")
        if row["reversal_of"] is not None:
            errors.append(f"transaction {key} is a reversal; reversal journeys are unsupported by this pilot")

    entry_rows = _unique_rows(ledger["journal_entries"], "journal entries", ("id", "entry_date"))
    line_rows = _unique_rows(ledger["journal_lines"], "journal lines", ("id",))
    account_rows = _unique_rows(ledger["gl_accounts"], "GL accounts", ("id",))
    control_accounts = []
    for index, account in enumerate(account_rows):
        _keys(account, {"id", "code", "currency_code", "is_enabled"}, f"GL accounts[{index}]")
        account_id = _uuid(account["id"], f"GL accounts[{index}].id")
        if (account_id == CZK_DEPOSIT_CONTROL_GL_ID and account["code"] == "2100"
                and account["currency_code"] == "CZK" and account["is_enabled"] is True):
            control_accounts.append(account_id)
    if len(control_accounts) != 1:
        errors.append("ledger snapshot must contain the V3 CZK deposit-control GL account (2100, stable ID ...0002), enabled")
    control_account_id = control_accounts[0] if len(control_accounts) == 1 else None
    orphan_lines = ledger["orphan_journal_lines"]
    if not isinstance(orphan_lines, list):
        raise EvidenceError("ledger.orphan_journal_lines must be a list")
    if orphan_lines:
        errors.append(f"ledger snapshot contains {len(orphan_lines)} orphan journal lines")
    entries_by_tx: dict[str, list[dict[str, Any]]] = defaultdict(list)
    entries_by_id: dict[str, dict[str, Any]] = {}
    all_run_tx_ids = set(txid_to_key)
    for index, row in enumerate(entry_rows):
        label = f"journal entries[{index}]"
        _keys(row, {
            "id", "entry_date", "transaction_id", "status", "reversal_of", "created_at", "synthetic",
        }, label)
        entry_id = _uuid(row.get("id"), f"{label}.id")
        tx_id = _uuid(row.get("transaction_id"), f"{label}.transaction_id")
        created_at = _timestamp(row.get("created_at"), f"{label}.created_at")
        if not started <= created_at <= ended:
            errors.append(f"journal entry {entry_id} lies outside the declared run window")
        if tx_id not in all_run_tx_ids:
            errors.append(f"journal entry {entry_id} has unknown transaction_id {tx_id}")
        else:
            entries_by_tx[tx_id].append(row)
        if entry_id in entries_by_id:
            errors.append(f"journal entry id {entry_id} appears in multiple partitions")
        entries_by_id[entry_id or ""] = row
        if "reversal_of" not in row:
            raise EvidenceError("ledger snapshot lacks journal_entries.reversal_of")
        if row["reversal_of"] is not None:
            errors.append(f"journal entry {entry_id} is a reversal; reversal journeys are unsupported by this pilot")
        if row["synthetic"] is not True:
            errors.append(f"journal entry {entry_id} is not marked synthetic")

    lines_by_entry: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for index, row in enumerate(line_rows):
        label = f"journal lines[{index}]"
        _keys(row, {
            "id", "journal_id", "side", "amount", "currency_code", "base_amount", "base_currency",
            "gl_account_id", "sub_account_id",
        }, label)
        entry_id = _uuid(row.get("journal_id"), f"{label}.journal_id")
        if entry_id not in entries_by_id:
            errors.append(f"journal line {row.get('id')} references an unknown journal entry")
            continue
        lines_by_entry[entry_id].append(row)

    for key in sorted(expected_keys):
        row = transaction_by_key.get(key)
        if row is None:
            continue
        txid = _uuid(row.get("id"), f"transaction[{key}].id")
        if txid is None:
            continue
        entries = entries_by_tx.get(txid, [])
        if len(entries) != 1:
            errors.append(f"transaction {key} has {len(entries)} journal entries, expected exactly one")
            continue
        entry = entries[0]
        entry_id = _uuid(entry.get("id"), f"journal entry for {key}.id")
        if entry.get("status") != "POSTED":
            errors.append(f"transaction {key} ledger status is {entry.get('status')!r}, expected POSTED")
        instruction = intended[key]
        lines = lines_by_entry.get(entry_id or "", [])
        if len(lines) != 2:
            errors.append(f"internal transfer {key} has {len(lines)} journal lines, expected exactly two")
            continue
        totals: dict[str, dict[str, int]] = defaultdict(lambda: {"D": 0, "C": 0})
        expected_legs = {
            ("D", instruction["source_account_id"]),
            ("C", instruction["target_account_id"]),
        }
        actual_legs: set[tuple[str, str]] = set()
        gl_accounts: set[str] = set()
        for line_index, line in enumerate(lines):
            label = f"journal line for {key}[{line_index}]"
            side = _string(line.get("side"), f"{label}.side")
            if side not in {"D", "C"}:
                errors.append(f"{label} side must be D or C")
                continue
            currency = _string(line.get("currency_code"), f"{label}.currency_code")
            amount = _amount(line.get("amount"), f"{label}.amount")
            base_currency = _string(line.get("base_currency"), f"{label}.base_currency")
            base_amount = _amount(line.get("base_amount"), f"{label}.base_amount")
            account_id = _uuid(line.get("sub_account_id"), f"{label}.sub_account_id")
            gl_account_id = _uuid(line.get("gl_account_id"), f"{label}.gl_account_id")
            if account_id is None or gl_account_id is None:
                continue
            actual_legs.add((side, account_id))
            gl_accounts.add(gl_account_id)
            totals[currency][side] += _minor_units(amount)
            if gl_account_id != control_account_id:
                errors.append(f"{label} does not use the enabled CZK deposit-control GL account")
            if (currency != instruction["currency_code"]
                    or _minor_units(amount) != _minor_units(instruction["amount"])):
                errors.append(f"{label} amount/currency differs from intended instruction")
            if (base_currency != instruction["currency_code"]
                    or _minor_units(base_amount) != _minor_units(instruction["amount"])):
                errors.append(f"{label} base amount/currency differs from intended CZK instruction")
        if actual_legs != expected_legs:
            errors.append(f"journal {entry_id} customer debit/credit accounts do not match source and target")
        if len(gl_accounts) != 1:
            errors.append(f"journal {entry_id} transfer legs do not share one deposit-control GL account")
        if not totals or any(values["D"] != values["C"] for values in totals.values()):
            errors.append(f"journal {entry_id} is not balanced per currency")

    result = {
        "schema_version": SCHEMA_VERSION,
        "oracle": "internal-czk-transfer-db-ledger-v1",
        "run_id": run_id,
        "profile_id": PROFILE_ID,
        "profile_version": PROFILE_VERSION,
        "scope": "internal-czk-transfer-pilot",
        "status": "pass" if not errors else "fail",
        "attestation_boundary": "offline-artifact-integrity-only; signature_and_workflow_provenance_not_verified",
        "snapshot_policy": {
            "maximum_age_seconds": max_snapshot_age_seconds,
            "maximum_age_cap_seconds": MAX_SNAPSHOT_AGE_SECONDS,
            "maximum_cross_source_skew_seconds": max_snapshot_skew_seconds,
            "maximum_cross_source_skew_cap_seconds": MAX_SNAPSHOT_SKEW_SECONDS,
        },
        "derived_from": {
            "expected_instructions_sha256": hashes["expected"],
            "transaction_snapshot_sha256": hashes["transactions"],
            "ledger_snapshot_sha256": hashes["ledger"],
        },
        "counts": {
            "intended_instructions": len(intended),
            "transaction_rows": len(tx_rows),
            "matched_transaction_rows": len(transaction_by_key),
            "journal_entries_in_run_window": len(entry_rows),
            "journal_lines": len(line_rows),
            "gl_accounts": len(account_rows),
        },
        "failures": errors,
        "evaluated_at": current.isoformat().replace("+00:00", "Z"),
    }
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected", required=True, type=Path, help="run-scoped intended instruction JSON")
    parser.add_argument("--transactions", required=True, type=Path, help="read-only transaction DB snapshot JSON")
    parser.add_argument("--ledger", required=True, type=Path, help="read-only ledger DB snapshot JSON")
    parser.add_argument("--output", type=Path, help="write derived result JSON to this path (default: stdout)")
    parser.add_argument("--max-snapshot-age-seconds", type=int, default=86_400)
    parser.add_argument("--max-snapshot-skew-seconds", type=int, default=300)
    args = parser.parse_args()

    paths = {"expected": args.expected, "transactions": args.transactions, "ledger": args.ledger}
    hashes: dict[str, str] = {}
    try:
        expected, expected_hash = _load_json(args.expected)
        transactions, transactions_hash = _load_json(args.transactions)
        ledger, ledger_hash = _load_json(args.ledger)
        hashes = {"expected": expected_hash, "transactions": transactions_hash, "ledger": ledger_hash}
        result = verify_documents(
            expected,
            transactions,
            ledger,
            hashes,
            max_snapshot_age_seconds=args.max_snapshot_age_seconds,
            max_snapshot_skew_seconds=args.max_snapshot_skew_seconds,
        )
    except (EvidenceError, OSError) as exc:
        for name, path in paths.items():
            if name not in hashes:
                try:
                    hashes[name] = hashlib.sha256(path.read_bytes()).hexdigest()
                except OSError:
                    pass
        result = {
            "schema_version": SCHEMA_VERSION,
            "oracle": "internal-czk-transfer-db-ledger-v1",
            "profile_id": PROFILE_ID,
            "profile_version": PROFILE_VERSION,
            "status": "fail",
            "attestation_boundary": "offline-artifact-integrity-only; signature_and_workflow_provenance_not_verified",
            "snapshot_policy": {
                "maximum_age_seconds": args.max_snapshot_age_seconds,
                "maximum_age_cap_seconds": MAX_SNAPSHOT_AGE_SECONDS,
                "maximum_cross_source_skew_seconds": args.max_snapshot_skew_seconds,
                "maximum_cross_source_skew_cap_seconds": MAX_SNAPSHOT_SKEW_SECONDS,
            },
            "derived_from": hashes,
            "failures": [str(exc)],
        }

    rendered = json.dumps(result, sort_keys=True, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    else:
        sys.stdout.write(rendered)
    return 0 if result["status"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
