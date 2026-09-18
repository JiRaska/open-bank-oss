#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compare sorted, minimized Context and fleet Audit commitment exports."""

from __future__ import annotations

import argparse
import csv
import heapq
import json
import re
import sys
from contextlib import ExitStack
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, TextIO
from uuid import UUID


COMMITMENT = re.compile(r"[0-9a-f]{64}\Z")
HEADERS = ["audit_id", "commitment"]


@dataclass(frozen=True)
class Row:
    audit_id: str
    commitment: str


def rows(source: TextIO, label: str) -> Iterator[Row]:
    reader = csv.DictReader(source)
    if reader.fieldnames != HEADERS:
        raise ValueError(f"{label}: expected only the audit_id,commitment columns")
    previous: str | None = None
    for line, record in enumerate(reader, start=2):
        if None in record or any(value is None for value in record.values()):
            raise ValueError(f"{label}:{line}: malformed CSV row")
        raw_id = record["audit_id"]
        raw_commitment = record["commitment"]
        try:
            audit_id = str(UUID(raw_id))
        except (ValueError, AttributeError) as exc:
            raise ValueError(f"{label}:{line}: invalid audit ID") from exc
        if raw_id != audit_id:
            raise ValueError(f"{label}:{line}: audit ID must be canonical lowercase UUID")
        if not COMMITMENT.fullmatch(raw_commitment):
            raise ValueError(f"{label}:{line}: invalid SHA-256 commitment")
        if previous is not None and audit_id <= previous:
            raise ValueError(f"{label}:{line}: input must be sorted with unique audit IDs")
        previous = audit_id
        yield Row(audit_id, raw_commitment)


def unique_rows(source: Iterator[Row]) -> Iterator[Row]:
    previous: str | None = None
    for row in source:
        if previous is not None and row.audit_id <= previous:
            raise ValueError("context: audit ID duplicated across bank-scoped exports")
        previous = row.audit_id
        yield row


def compare(context: Iterator[Row], fleet: Iterator[Row], sample_limit: int) -> dict:
    result = {
        "context_rows": 0,
        "fleet_rows": 0,
        "matched": 0,
        "missing_in_fleet": 0,
        "missing_in_context": 0,
        "commitment_mismatch": 0,
        "samples": [],
    }
    local = next(context, None)
    central = next(fleet, None)
    while local is not None or central is not None:
        if central is None or (local is not None and local.audit_id < central.audit_id):
            result["context_rows"] += 1
            result["missing_in_fleet"] += 1
            sample(result, sample_limit, "missing_in_fleet", local.audit_id)
            local = next(context, None)
        elif local is None or central.audit_id < local.audit_id:
            result["fleet_rows"] += 1
            result["missing_in_context"] += 1
            sample(result, sample_limit, "missing_in_context", central.audit_id)
            central = next(fleet, None)
        else:
            result["context_rows"] += 1
            result["fleet_rows"] += 1
            if local.commitment == central.commitment:
                result["matched"] += 1
            else:
                result["commitment_mismatch"] += 1
                sample(result, sample_limit, "commitment_mismatch", local.audit_id)
            local = next(context, None)
            central = next(fleet, None)
    if result["context_rows"] == 0 and result["fleet_rows"] == 0:
        result["status"] = "EMPTY"
    elif any(result[key] for key in ("missing_in_fleet", "missing_in_context", "commitment_mismatch")):
        result["status"] = "DIFFERENCE"
    else:
        result["status"] = "MATCH"
    return result


def sample(result: dict, limit: int, kind: str, audit_id: str) -> None:
    if len(result["samples"]) < limit:
        result["samples"].append({"kind": kind, "audit_id": audit_id})


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--context", type=Path, required=True, action="append",
        help="sorted Context outbox CSV; repeat once for each bank scope",
    )
    parser.add_argument("--fleet", type=Path, required=True, help="sorted fleet Audit CSV")
    parser.add_argument("--samples", type=int, default=10, help="maximum random audit IDs to print")
    args = parser.parse_args()
    if args.samples < 0 or args.samples > 100:
        parser.error("--samples must be between 0 and 100")
    try:
        with ExitStack() as stack:
            context_files = [stack.enter_context(path.open(newline="", encoding="utf-8")) for path in args.context]
            fleet_file = stack.enter_context(args.fleet.open(newline="", encoding="utf-8"))
            context_rows = heapq.merge(
                *(rows(stream, f"context:{path.name}") for path, stream in zip(args.context, context_files)),
                key=lambda row: row.audit_id,
            )
            result = compare(unique_rows(context_rows), rows(fleet_file, "fleet"), args.samples)
    except (OSError, ValueError) as exc:
        print(f"reconciliation failed: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, sort_keys=True))
    if result["status"] == "EMPTY":
        return 2
    return 0 if result["status"] == "MATCH" else 1


if __name__ == "__main__":
    raise SystemExit(main())
