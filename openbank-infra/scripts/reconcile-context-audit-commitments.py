#!/usr/bin/env python3
"""Read-only comparison of mature Context commitments with the fleet audit store.

Use separate, read-only libpq service definitions for the two databases. No database
password or row content is accepted on the command line or written to disk.
"""

import argparse
import re
import subprocess
import sys
import uuid
from datetime import datetime, timedelta, timezone

SERVICE_NAME = re.compile(r"[A-Za-z0-9_-]+\Z")
ROW = re.compile(r"([0-9a-f-]{36})\t([0-9a-f]{64})\t(READ|DISCLOSURE)\Z")


def fetch(
    service: str, sql: str, variables: dict[str, str] | None = None
) -> dict[tuple[str, str], str]:
    if not SERVICE_NAME.fullmatch(service):
        raise ValueError("libpq service name must contain only letters, digits, _ or -")
    command = [
        "psql",
        f"service={service}",
        "-X",
        "-q",
        "-A",
        "-t",
        "-F",
        "\t",
        "-v",
        "ON_ERROR_STOP=1",
    ]
    for name, value in (variables or {}).items():
        command += ["-v", f"{name}={value}"]
    result = subprocess.run(
        command, input=sql, text=True, capture_output=True, check=True
    )
    rows: dict[tuple[str, str], str] = {}
    for line in result.stdout.splitlines():
        match = ROW.fullmatch(line)
        if match is None:
            raise ValueError("unexpected database result shape")
        event_id, commitment, kind = match.groups()
        if str(uuid.UUID(event_id)) != event_id:
            raise ValueError("noncanonical commitment ID")
        key = (kind, event_id)
        if key in rows:
            raise ValueError("duplicate commitment ID")
        rows[key] = commitment
    return rows


CONTEXT_SQL = """
BEGIN READ ONLY;
SET LOCAL statement_timeout TO '5s';
SET LOCAL openbank.bank_scope TO :'bank';
SELECT audit_id, commitment, 'READ'
FROM context_audit_commitment_outbox
WHERE bank_scope = :'bank' AND occurred_at < :'end'::timestamptz
  AND occurred_at >= :'start'::timestamptz
UNION ALL
SELECT disclosure_id, commitment, 'DISCLOSURE'
FROM context_disclosure_commitment_outbox
WHERE bank_scope = :'bank' AND occurred_at < :'end'::timestamptz
  AND occurred_at >= :'start'::timestamptz
LIMIT 10001;
COMMIT;
"""

AUDIT_SQL = """
BEGIN READ ONLY;
SET LOCAL statement_timeout TO '5s';
SELECT entry_id, payload::jsonb ->> 'commitment',
       CASE event_type WHEN 'CONTEXT_READ_AUDIT_COMMITTED' THEN 'READ' ELSE 'DISCLOSURE' END
FROM audit_entries
WHERE source_service = 'context-service'
  AND event_type IN ('CONTEXT_READ_AUDIT_COMMITTED', 'CONTEXT_DISCLOSURE_COMMITTED')
  AND entry_id IN (/* IDS */);
COMMIT;
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--context-service", required=True, help="read-only libpq service name"
    )
    parser.add_argument(
        "--audit-service", required=True, help="read-only libpq service name"
    )
    parser.add_argument("--bank-scope", required=True)
    args = parser.parse_args()
    end = datetime.now(timezone.utc) - timedelta(minutes=15)
    window = {"start": (end - timedelta(hours=1)).isoformat(), "end": end.isoformat()}
    try:
        local = fetch(
            args.context_service, CONTEXT_SQL, {**window, "bank": args.bank_scope}
        )
        if len(local) > 10000:
            print(
                "reconciliation inconclusive: sample exceeds 10000 commitments",
                file=sys.stderr,
            )
            return 3
        if not local:
            print(
                "reconciliation inconclusive: no local commitments in the sampled hour",
                file=sys.stderr,
            )
            return 3
        central = {}
        identifiers = sorted({event_id for _, event_id in local})
        for offset in range(0, len(identifiers), 500):
            # UUIDs were canonicalized before interpolation. The unique entry_id index
            # bounds central work to this bank's local sample rather than a fleet scan.
            values = ", ".join(
                f"'{event_id}'::uuid" for event_id in identifiers[offset : offset + 500]
            )
            central.update(
                fetch(args.audit_service, AUDIT_SQL.replace("/* IDS */", values))
            )
    except (ValueError, FileNotFoundError, subprocess.CalledProcessError) as error:
        print(f"reconciliation unavailable: {type(error).__name__}", file=sys.stderr)
        return 2
    missing = local.keys() - central.keys()
    mismatch = {
        key for key in local.keys() & central.keys() if local[key] != central[key]
    }
    # The fleet audit store may contain other banks; only local IDs are assessed.
    print(
        f"window=[{window['start']},{window['end']}) local={len(local)} "
        f"matched={len(local) - len(missing) - len(mismatch)} "
        f"missing={len(missing)} mismatch={len(mismatch)}"
    )
    return 1 if missing or mismatch else 0


if __name__ == "__main__":
    sys.exit(main())
