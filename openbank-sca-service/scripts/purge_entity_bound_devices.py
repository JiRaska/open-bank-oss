#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Inventory, and on explicit request purge, device credentials enrolled to NON-natural persons.

#10281 item 1. Before the edge bound SCA to the human under `X-Acting-For`, a person's device key
could be enrolled to the COMPANY party. `recordDecision` checks `device.partyId ==
challenge.partyId`, so such a key can still approve any challenge raised for the company. From
sca-service 0.16 on, enrolment to a non-natural person is refused; the rows written before that are
what this script finds.

The two facts live in two databases (sca: `sca_enrolled_devices`; party: `parties.party_type`), so
the join is done here, read-only, over two `psql` connections. Nothing is written unless `--apply`
is given, and `--apply` additionally demands `--expect-count` equal to what the dry run reported,
so the purge that runs is the one a reviewer approved — never a larger one discovered later.

Usage (dry run — the default, and the only mode to run without a reviewed change record):

    SCA_DSN=postgresql://...openbank_sca PARTY_DSN=postgresql://...openbank_party \\
        python3 openbank-sca-service/scripts/purge_entity_bound_devices.py > inventory.json

Purge (after review of inventory.json; see docs/05-operations "Entity-bound device credentials"):

    ... purge_entity_bound_devices.py --apply --expect-count 3 --archive purged.json

Output carries device id, party id, party type, algorithm and enrolment time — never a name,
e-mail or public key. A party the register does not know is reported as type `UNKNOWN` and is
treated as non-natural (a device key must belong to a known person).
"""

import argparse
import json
import os
import subprocess
import sys

NATURAL_PERSON_TYPES = {"INDIVIDUAL", "SOLE_TRADER"}


def psql(dsn: str, sql: str) -> list[list[str]]:
    """Run one statement; rows as lists of strings. `ON_ERROR_STOP` so a failure is an exit code."""
    out = subprocess.run(
        ["psql", dsn, "-X", "-q", "-A", "-t", "-F", "\t", "-v", "ON_ERROR_STOP=1", "-c", sql],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return [line.split("\t") for line in out.splitlines() if line]


def uuid_list(ids: list[str]) -> str:
    # ids come from a uuid column; the cast rejects anything else, so this is not an injection path.
    return ",".join(f"'{i}'::uuid" for i in ids)


def inventory(sca_dsn: str, party_dsn: str) -> list[dict]:
    devices = psql(
        sca_dsn,
        "select id, party_id, algorithm, created_at from sca_enrolled_devices order by created_at",
    )
    party_ids = sorted({d[1] for d in devices})
    types: dict[str, str] = {}
    if party_ids:
        for pid, ptype in psql(
            party_dsn, f"select party_id, party_type from parties where party_id in ({uuid_list(party_ids)})"
        ):
            types[pid] = ptype
    found = []
    for dev_id, party_id, algorithm, created_at in devices:
        ptype = types.get(party_id, "UNKNOWN")
        if ptype not in NATURAL_PERSON_TYPES:
            found.append(
                {
                    "deviceId": dev_id,
                    "partyId": party_id,
                    "partyType": ptype,
                    "algorithm": algorithm,
                    "enrolledAt": created_at,
                }
            )
    return found


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--apply", action="store_true", help="delete the inventoried rows (default: dry run)")
    ap.add_argument("--expect-count", type=int, help="required with --apply: the reviewed inventory size")
    ap.add_argument("--archive", help="required with --apply: file to write the purged rows to first")
    args = ap.parse_args()

    sca_dsn, party_dsn = os.environ.get("SCA_DSN"), os.environ.get("PARTY_DSN")
    if not sca_dsn or not party_dsn:
        print("SCA_DSN and PARTY_DSN must both be set", file=sys.stderr)
        return 2

    found = inventory(sca_dsn, party_dsn)
    report = {"mode": "apply" if args.apply else "dry-run", "count": len(found), "devices": found}

    if not args.apply:
        json.dump(report, sys.stdout, indent=2)
        print()
        return 0

    if args.expect_count is None or not args.archive:
        print("--apply requires --expect-count and --archive", file=sys.stderr)
        return 2
    if len(found) != args.expect_count:
        print(
            f"inventory is {len(found)} rows, reviewed count was {args.expect_count}: refusing. "
            "Re-run the dry run and have the new inventory reviewed.",
            file=sys.stderr,
        )
        return 3
    with open(args.archive, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2)
    if found:
        ids = uuid_list([d["deviceId"] for d in found])
        # One statement, so it is one transaction; RETURNING proves exactly what went.
        deleted = psql(sca_dsn, f"delete from sca_enrolled_devices where id in ({ids}) returning id")
        if len(deleted) != len(found):
            print(f"deleted {len(deleted)} of {len(found)} rows — investigate before re-running", file=sys.stderr)
            return 4
    print(f"purged {len(found)} entity-bound device credential(s); archive: {args.archive}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
