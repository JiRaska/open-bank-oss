#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# The ClickHouse warehouse has TWO copies of its DDL and no runner joining them.
#
# openbank-analytics-sink owns no OLTP database and uses no Flyway (ADR-0022), so its schema lives as
# source-controlled `.sql` resources applied by an operator, and — for a cluster built from scratch —
# as the `clickhouse-init` ConfigMap the container runs from /docker-entrypoint-initdb.d on FIRST
# BOOT ONLY. Nothing reconciles the two. A migration added to the resources and not to the ConfigMap
# is invisible in every direction a person normally checks: the file is in git, the sandbox has the
# object (an operator applied it), CI is green, and the gap only surfaces the day a warehouse is
# rebuilt and a view its readers depend on is simply not there.
#
# That is not hypothetical. V4__screen_feedback_context.sql shipped 2026-07 and never reached the
# ConfigMap, so a fresh warehouse would have had `gold_screen_feedback` without os_version / locale /
# theme / session_id and no `gold_screen_feedback_context` at all — found while landing #4511 and
# fixed in the same PR.
#
# WHAT THIS CHECKS. Objects, not bytes: every `openbank_analytics.<object>` created by a
# `clickhouse/V*.sql` resource must also be created somewhere in the ConfigMap, and vice versa.
# Comparing text would fail on indentation and force the two to be byte-identical, which they are not
# and need not be; comparing the created object set is the property that actually matters ("would a
# fresh cluster have this?"). Both directions are errors — a ConfigMap object with no resource behind
# it is DDL with no source of record.
#
# WHAT IT DOES NOT CHECK: that the two definitions of an object AGREE. A caller depending on a
# column added by a redefinition still needs its own assertion (see customer-360.test.ts).
#
#   python3 .github/scripts/check-clickhouse-ddl-in-configmap.py --root .
#   python3 .github/scripts/check-clickhouse-ddl-in-configmap.py --self-test

import argparse
import re
import sys
import tempfile
from pathlib import Path

SQL_GLOB = "openbank-analytics-sink/src/main/resources/clickhouse/V*.sql"
CONFIGMAP = "openbank-infra/gitops/components/analytics/clickhouse-init-configmap.yaml"

# CREATE [OR REPLACE] {VIEW|MATERIALIZED VIEW|TABLE|DICTIONARY} [IF NOT EXISTS] openbank_analytics.x
CREATE_RE = re.compile(
    r"CREATE\s+(?:OR\s+REPLACE\s+)?(?:MATERIALIZED\s+)?(?:VIEW|TABLE|DICTIONARY)\s+"
    r"(?:IF\s+NOT\s+EXISTS\s+)?openbank_analytics\.([A-Za-z_][A-Za-z0-9_]*)",
    re.IGNORECASE,
)


def objects(text: str) -> set[str]:
    """Objects a chunk of DDL creates. Comment lines are stripped first: every one of these files
    quotes its own statements in prose, and a commented example would otherwise count as created."""
    live = "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("--"))
    return {m.lower() for m in CREATE_RE.findall(live)}


def check(root: Path) -> list[str]:
    errors: list[str] = []
    sql_files = sorted(root.glob(SQL_GLOB))
    cm_path = root / CONFIGMAP

    # A probe that finds no subject must say so, not pass. Both inputs are required to exist: this
    # gate reporting OK because a path moved is the failure mode it is meant to prevent elsewhere.
    if not sql_files:
        return [f"no ClickHouse DDL found at {SQL_GLOB} — the gate cannot have checked anything"]
    if not cm_path.is_file():
        return [f"{CONFIGMAP} not found — the gate cannot have checked anything"]

    cm_objects = objects(cm_path.read_text())
    if not cm_objects:
        return [f"{CONFIGMAP} declares no openbank_analytics objects — parse or path drift"]

    seen: dict[str, str] = {}
    for f in sql_files:
        for obj in objects(f.read_text()):
            seen.setdefault(obj, f.name)
            if obj not in cm_objects:
                errors.append(
                    f"{f.name} creates openbank_analytics.{obj}, which the init ConfigMap never "
                    f"creates — a warehouse built from scratch would not have it"
                )

    for obj in sorted(cm_objects - set(seen)):
        errors.append(
            f"the init ConfigMap creates openbank_analytics.{obj}, which no clickhouse/V*.sql "
            f"resource creates — DDL with no source of record"
        )

    return errors


SELF_TEST_CM = """apiVersion: v1
data:
  01-a.sql: |
    CREATE TABLE IF NOT EXISTS openbank_analytics.bronze_events (x String) ENGINE = Log;
kind: ConfigMap
"""


def self_test() -> int:
    """Falsify the gate: it must FLAG a resource object missing from the ConfigMap, and must PASS
    when the two agree. A gate that has only ever passed is unfalsified."""
    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        sqldir = root / "openbank-analytics-sink/src/main/resources/clickhouse"
        sqldir.mkdir(parents=True)
        (root / CONFIGMAP).parent.mkdir(parents=True)
        (root / CONFIGMAP).write_text(SELF_TEST_CM)
        (sqldir / "V1__a.sql").write_text(
            "CREATE TABLE IF NOT EXISTS openbank_analytics.bronze_events (x String) ENGINE = Log;\n"
        )

        # known-negative: the sets agree.
        errs = check(root)
        if errs:
            print(f"SELF-TEST FAIL: clean tree flagged: {errs}")
            ok = False

        # known-positive A: a resource object the ConfigMap does not create (the V4 case).
        (sqldir / "V2__b.sql").write_text(
            "-- CREATE VIEW openbank_analytics.commented_out AS SELECT 1;\n"
            "CREATE OR REPLACE VIEW openbank_analytics.silver_party_accounts AS SELECT 1;\n"
        )
        errs = check(root)
        if not any("silver_party_accounts" in e for e in errs):
            print("SELF-TEST FAIL: a resource object missing from the ConfigMap was not flagged")
            ok = False
        if any("commented_out" in e for e in errs):
            print("SELF-TEST FAIL: a commented-out CREATE was counted as created")
            ok = False

        # known-positive B: a ConfigMap object with no resource behind it.
        (root / CONFIGMAP).write_text(
            SELF_TEST_CM.replace(
                "kind: ConfigMap",
                "  02-b.sql: |\n"
                "    CREATE OR REPLACE VIEW openbank_analytics.silver_party_accounts AS SELECT 1;\n"
                "  03-c.sql: |\n"
                "    CREATE VIEW IF NOT EXISTS openbank_analytics.orphan_view AS SELECT 1;\n"
                "kind: ConfigMap",
            )
        )
        errs = check(root)
        if not any("orphan_view" in e for e in errs):
            print("SELF-TEST FAIL: a ConfigMap object with no resource was not flagged")
            ok = False

        # known-positive C: an absent subject must fail, never read as clean.
        (sqldir / "V1__a.sql").unlink()
        (sqldir / "V2__b.sql").unlink()
        if not check(root):
            print("SELF-TEST FAIL: an empty subject set reported clean")
            ok = False

    print("SELF-TEST PASS" if ok else "SELF-TEST FAILED")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    errors = check(Path(args.root))
    for e in errors:
        print(f"::error::{e}")
    if errors:
        print(
            f"\n{len(errors)} ClickHouse DDL parity problem(s). Add the migration's statements to "
            f"{CONFIGMAP} (one `NN-<name>.sql` key per resource, applied in key order on first boot)."
        )
        return 1
    print("OK: every clickhouse/V*.sql object is created by the init ConfigMap, and vice versa.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
