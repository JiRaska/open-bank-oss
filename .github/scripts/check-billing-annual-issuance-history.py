#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compare a complete historical annual-summary event export with durable issuance keys.

Both inputs are operator-held, untracked CSV files with columns account_id,
calendar_year,source_event_id. The historical file must come from an independently
verified complete archive, including events whose billing outbox row was purged.
This tool reads files only. It emits aggregate counts, never identities or paths.
"""

from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path
from uuid import UUID

FIELDS = ("account_id", "calendar_year", "source_event_id")


def read_rows(path: Path, year: int, *, allow_replay: bool) -> tuple[dict[tuple[str, int], UUID], int]:
    records: dict[tuple[str, int], UUID] = {}
    event_keys: dict[UUID, tuple[str, int]] = {}
    replay_rows = 0
    with path.open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.DictReader(stream, strict=True)
        if reader.fieldnames != list(FIELDS):
            raise ValueError("invalid columns")
        for row in reader:
            if None in row or any(row[field] is None for field in FIELDS):
                raise ValueError("invalid row shape")
            account = row["account_id"]
            if not account or account.strip() != account or len(account) > 64:
                raise ValueError("invalid account identity")
            raw_year = row["calendar_year"]
            if not raw_year.isascii() or not raw_year.isdecimal() or int(raw_year) != year:
                raise ValueError("out-of-scope year")
            event_id = UUID(row["source_event_id"])
            if str(event_id) != row["source_event_id"]:
                raise ValueError("non-canonical event identity")
            key = (account, year)
            previous = records.get(key)
            if previous is not None:
                if not allow_replay or previous != event_id:
                    raise ValueError("conflicting or duplicate issuance identity")
                replay_rows += 1
            previous_key = event_keys.get(event_id)
            if previous_key is not None and previous_key != key:
                raise ValueError("event identity reused across accounts")
            records[key] = event_id
            event_keys[event_id] = key
    return records, replay_rows


def compare(history: Path, issuance: Path, year: int, expected_events: int) -> tuple[int, int, int, int, int]:
    historical, replays = read_rows(history, year, allow_replay=True)
    registry, _ = read_rows(issuance, year, allow_replay=False)
    if len(historical) != expected_events:
        raise ValueError("archive count differs from independent manifest")
    missing = sum(key not in registry for key in historical)
    event_conflicts = sum(
        key in registry and registry[key] != event_id for key, event_id in historical.items()
    )
    archive_gaps = sum(key not in historical for key in registry)
    return len(historical), len(registry), replays, missing, event_conflicts + archive_gaps


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--historical-events", required=True, type=Path)
    parser.add_argument("--issuance-keys", required=True, type=Path)
    parser.add_argument("--year", required=True, type=int)
    parser.add_argument("--expected-events", required=True, type=int,
                        help="independently verified unique-event count for this year")
    args = parser.parse_args()
    if not 1 <= args.year <= 9999 or args.expected_events < 0:
        print("preflight failed: invalid scope", file=sys.stderr)
        return 2
    try:
        historical, issued, replays, missing, conflicting_or_unarchived = compare(
            args.historical_events, args.issuance_keys, args.year, args.expected_events
        )
    except (OSError, UnicodeError, csv.Error, ValueError, KeyError):
        print("preflight failed: invalid, incomplete, or unreadable input", file=sys.stderr)
        return 2
    print(f"historical_events={historical} issuance_keys={issued} replay_rows={replays} "
          f"missing_keys={missing} conflicting_or_unarchived={conflicting_or_unarchived}")
    if missing or conflicting_or_unarchived:
        print("preflight failed: reconciliation mismatch", file=sys.stderr)
        return 1
    print("preflight matched supplied archive; archive completeness requires independent proof")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
