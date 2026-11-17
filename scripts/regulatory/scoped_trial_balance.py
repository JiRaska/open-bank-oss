#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Produce FINREP pilot inputs from an approved, independently staged journal export."""
import argparse
from collections import defaultdict
import hashlib
import hmac
import json
from datetime import date
from pathlib import Path
import tempfile
import time

MAX_LINE = 4096
MAX_GL_KEYS = 50_000
ACCOUNT_TYPES = {"ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE", "OFF_BALANCE_SHEET"}


def money(cents):
    sign = "-" if cents < 0 else ""
    units, remainder = divmod(abs(cents), 100)
    return f"{sign}{units}.{remainder:02d}"


def produce(manifest_file, approved_digest, journal_file, destination, max_records_per_second=None):
    if max_records_per_second is not None and max_records_per_second < 1:
        raise ValueError("Replay rate must be positive")
    with manifest_file.open("rb") as manifest_stream:
        raw = manifest_stream.read(1024 * 1024 + 1)
    if len(raw) > 1024 * 1024 or not hmac.compare_digest(hashlib.sha256(raw).hexdigest(), approved_digest):
        raise ValueError("Manifest does not match approved evidence")
    manifest = json.loads(raw)
    if manifest["version"] != 1:
        raise ValueError("Unsupported journal export version")
    as_of = date.fromisoformat(manifest["asOf"])
    scopes = {item["scope"]["contextId"]: item for item in manifest["contexts"]}
    if not scopes or len(scopes) != len(manifest["contexts"]):
        raise ValueError("Reporting context IDs must be unique and explicit")
    totals = {key: [0, 0, 0] for key in scopes}
    balances = defaultdict(int)
    journal_digest = hashlib.sha256()
    sequence = 0
    # Limit each small burst independently so a stalled worker cannot accumulate credit.
    pace_batch = min(256, max_records_per_second or 256)
    batch_started = time.monotonic()
    with journal_file.open("rb") as entries:
        while line := entries.readline(MAX_LINE + 1):
            if len(line) > MAX_LINE or not line.endswith(b"\n"):
                raise ValueError("Invalid journal record boundary")
            journal_digest.update(line)
            entry = json.loads(line)
            sequence += 1
            if entry["sequence"] != sequence:
                raise ValueError("Journal sequence contains a gap or duplicate")
            context = entry["contextId"]
            if context not in scopes or entry["currency"] != "CZK":
                raise ValueError("Missing approved attribution or unsupported currency")
            scope = scopes[context]["scope"]
            attribution_fields = ("bookingCompanyId", "bookingMode", "bookingBranchId")
            if any(field in entry for field in attribution_fields):
                if not all(field in entry for field in attribution_fields) or (
                    entry["bookingCompanyId"], entry["bookingMode"], entry["bookingBranchId"]
                ) != (scope["legalEntityId"], scope["mode"], scope["establishmentId"]):
                    raise ValueError("Journal booking attribution differs from approved scope")
            code, account_type, amount = entry["code"], entry["accountType"], entry["netCents"]
            if not isinstance(code, str) or not code or len(code) > 64 or account_type not in ACCOUNT_TYPES:
                raise ValueError("Invalid GL classification")
            if type(amount) is not int or abs(amount) > 10**18:
                raise ValueError("Invalid signed minor-unit amount")
            totals[context][0] += 1
            totals[context][1] += max(0, amount)
            totals[context][2] += max(0, -amount)
            balances[context, code, account_type] += amount
            if len(balances) > MAX_GL_KEYS:
                raise ValueError("GL key budget exceeded")
            if max_records_per_second and sequence % pace_batch == 0:
                deadline = batch_started + pace_batch / max_records_per_second
                time.sleep(max(0, deadline - time.monotonic()))
                batch_started = time.monotonic()
    if journal_digest.hexdigest() != manifest["journalSha256"]:
        raise ValueError("Export does not match approved bytes")
    lines = defaultdict(list)
    for (context, code, account_type), net in sorted(balances.items()):
        lines[context].append(dict(code=code, accountType=account_type, net=money(net), currency="CZK"))
    for context, (count, debit, credit) in totals.items():
        expected = scopes[context]
        if (count, debit, credit) != (expected["records"], expected["debitCents"], expected["creditCents"]):
            raise ValueError("Scope does not reconcile to approved controls")
        if not count or debit != credit:
            raise ValueError("Scope has no evidence or fails double entry")
    if destination.exists():
        raise FileExistsError(destination)
    with tempfile.TemporaryDirectory(dir=destination.parent) as staging:
        output = Path(staging) / "snapshots"
        output.mkdir(mode=0o700)
        index = {}
        for number, (context, approved) in enumerate(sorted(scopes.items())):
            snapshot = dict(schemaVersion=1, scope=approved["scope"], asOf=as_of.isoformat(),
                            closeEvidenceId=approved["closeEvidenceId"],
                            trialBalance=dict(lines=lines[context], ledgerReportsBalanced=True))
            content = json.dumps(snapshot, sort_keys=True, separators=(",", ":")).encode()
            filename = f"{number}.json"
            (output / filename).write_bytes(content)
            index[context] = dict(file=filename, sha256=hashlib.sha256(content).hexdigest())
        (output / "index.json").write_text(json.dumps(index, sort_keys=True), encoding="utf-8")
        output.rename(destination)
    return index


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("approved_sha256")
    parser.add_argument("journal", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--max-records-per-second", type=int, default=50_000)
    args = parser.parse_args()
    produce(args.manifest, args.approved_sha256, args.journal, args.destination, args.max_records_per_second)


if __name__ == "__main__":
    main()
