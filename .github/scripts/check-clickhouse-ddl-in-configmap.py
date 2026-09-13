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
# It also checks the two things the object set CANNOT express, because a migration that only
# REDEFINES existing objects moves no object into the set and is therefore invisible to a set
# comparison by construction:
#
#   COLUMN PARITY  — every `ALTER TABLE openbank_analytics.<t> ADD COLUMN <c>` in a resource must be
#   accounted for in the ConfigMap's own definition of that table (in its CREATE TABLE body, or by
#   the same ALTER). A fresh cluster otherwise lacks the column entirely, and the sink's
#   `FORMAT JSONEachRow` insert names it: ClickHouse 24.8 defaults `input_format_skip_unknown_fields`
#   to 1, so the value is DROPPED rather than rejected — no error anywhere.
#
#   DEFINITION AGREEMENT — for an object created on both sides, the LAST definition in resource-
#   version order must match the LAST definition in ConfigMap key order, compared on SQL with
#   comments stripped and whitespace collapsed (not byte-identical: the two are indented
#   differently, and forcing bytes to match is what the object-set comparison was avoiding).
#
# That second property is what V9__synthetic_provenance.sql needed and did not have: it adds
# `bronze_events.synthetic` and re-cuts silver_current_state / silver_history / silver_as_of /
# gold_daily_event_volume with `WHERE synthetic = 0`, creating no new object, so the parity check
# was green for the whole time none of it had reached a fresh cluster (issue #7645).
#
# WHAT IT STILL DOES NOT CHECK: that either copy matches the LIVE warehouse. Nothing in this repo
# executes either artefact against a running cluster — the ConfigMap runs from
# /docker-entrypoint-initdb.d on an EMPTY data dir only, and the resources are applied by hand.
# This gate makes the two committed artefacts agree; it cannot make the cluster agree with them.
#
#   python3 .github/scripts/check-clickhouse-ddl-in-configmap.py --root .
#   python3 .github/scripts/check-clickhouse-ddl-in-configmap.py --self-test

import argparse
import re
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib  # import after the path insert — checkers run as scripts from the repo root

SQL_GLOB = "openbank-analytics-sink/src/main/resources/clickhouse/V*.sql"
CONFIGMAP = "openbank-infra/gitops/components/analytics/clickhouse-init-configmap.yaml"

# CREATE [OR REPLACE] {VIEW|MATERIALIZED VIEW|TABLE|DICTIONARY} [IF NOT EXISTS] openbank_analytics.x
CREATE_RE = re.compile(
    r"CREATE\s+(?:OR\s+REPLACE\s+)?(?:MATERIALIZED\s+)?(?:VIEW|TABLE|DICTIONARY)\s+"
    r"(?:IF\s+NOT\s+EXISTS\s+)?openbank_analytics\.([A-Za-z_][A-Za-z0-9_]*)",
    re.IGNORECASE,
)


# The whole CREATE statement, up to its terminating semicolon — used for definition agreement.
CREATE_STMT_RE = re.compile(
    r"(CREATE\s+(?:OR\s+REPLACE\s+)?(?:MATERIALIZED\s+)?(?:VIEW|TABLE|DICTIONARY)\s+"
    r"(?:IF\s+NOT\s+EXISTS\s+)?openbank_analytics\.([A-Za-z_][A-Za-z0-9_]*)\b.*?;)",
    re.IGNORECASE | re.DOTALL,
)

# ALTER TABLE openbank_analytics.<table> ADD COLUMN [IF NOT EXISTS] <column>
ALTER_ADD_RE = re.compile(
    r"ALTER\s+TABLE\s+openbank_analytics\.([A-Za-z_][A-Za-z0-9_]*)\s+"
    r"ADD\s+COLUMN\s+(?:IF\s+NOT\s+EXISTS\s+)?([A-Za-z_][A-Za-z0-9_]*)",
    re.IGNORECASE,
)

VERSION_RE = re.compile(r"^V(\d+)__")


def live_sql(text: str) -> str:
    """DDL with comment lines removed: every one of these files quotes its own statements in prose,
    and a commented example would otherwise count as real DDL."""
    return "\n".join(l for l in text.splitlines() if not l.lstrip().startswith("--"))


def objects(text: str) -> set[str]:
    """Objects a chunk of DDL creates."""
    return {m.lower() for m in CREATE_RE.findall(live_sql(text))}


def normalize(stmt: str) -> str:
    """A CREATE statement reduced to what it MEANS to ClickHouse: comments already gone, whitespace
    collapsed, case folded. The two artefacts are indented differently on purpose, so comparing
    bytes would fail on formatting and force them to be copies rather than to agree."""
    return re.sub(r"\s+", " ", stmt).strip().lower()


def last_definitions(chunks: list[str]) -> dict[str, str]:
    """The definition of each object that WINS, given chunks in application order. A later
    CREATE OR REPLACE supersedes an earlier one, so only the last one describes the end state."""
    out: dict[str, str] = {}
    for chunk in chunks:
        for stmt, obj in CREATE_STMT_RE.findall(live_sql(chunk)):
            out[obj.lower()] = normalize(stmt)
    return out


def added_columns(text: str) -> set[tuple[str, str]]:
    """(table, column) pairs a chunk of DDL adds by ALTER."""
    return {(t.lower(), c.lower()) for t, c in ALTER_ADD_RE.findall(live_sql(text))}


def resource_order(path: Path) -> tuple[int, str]:
    """Resources apply in migration-version order, ties broken by name. Two files may legitimately
    claim one version (V9 does today); where they touch disjoint objects the tie is immaterial, and
    where they do not, the name order is at least deterministic rather than filesystem-dependent."""
    m = VERSION_RE.match(path.name)
    return (int(m.group(1)) if m else 0, path.name)


def check(root: Path) -> tuple[list[str], int]:
    """Returns (errors, objects examined). The count is the gate's subject floor: a broken glob or a
    regex that stops matching leaves the verdict clean, and only the count can say so."""
    errors: list[str] = []
    sql_files = sorted(root.glob(SQL_GLOB), key=resource_order)
    cm_path = root / CONFIGMAP

    # A probe that finds no subject must say so, not pass. Both inputs are required to exist: this
    # gate reporting OK because a path moved is the failure mode it is meant to prevent elsewhere.
    if not sql_files:
        return ([f"no ClickHouse DDL found at {SQL_GLOB} — the gate cannot have checked anything"], 0)
    if not cm_path.is_file():
        return ([f"{CONFIGMAP} not found — the gate cannot have checked anything"], 0)

    cm_objects = objects(cm_path.read_text())
    if not cm_objects:
        return ([f"{CONFIGMAP} declares no openbank_analytics objects — parse or path drift"], 0)

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

    # COLUMN PARITY. An ALTER adds no object, so the comparison above cannot see it at all.
    cm_text = cm_path.read_text()
    cm_live = live_sql(cm_text)
    cm_added = added_columns(cm_text)
    for f in sql_files:
        for table, column in sorted(added_columns(f.read_text())):
            if (table, column) in cm_added:
                continue
            # Accept the column being declared in the ConfigMap's CREATE TABLE body instead — that is
            # the better shape for a cluster built from nothing, and it is equally correct.
            body = next(
                (
                    stmt
                    for stmt, obj in CREATE_STMT_RE.findall(cm_live)
                    if obj.lower() == table and stmt.lstrip().upper().startswith("CREATE TABLE")
                ),
                "",
            )
            if re.search(rf"(?<![A-Za-z0-9_]){re.escape(column)}(?![A-Za-z0-9_])", body, re.IGNORECASE):
                continue
            errors.append(
                f"{f.name} adds column openbank_analytics.{table}.{column}, which the init ConfigMap "
                f"neither declares on {table} nor adds — a warehouse built from scratch would not "
                f"have it, and an insert naming it is silently dropped, not rejected"
            )

    # DEFINITION AGREEMENT. A migration that only redefines existing objects is invisible above.
    res_last = last_definitions([f.read_text() for f in sql_files])
    cm_last = last_definitions([cm_text])
    for obj in sorted(set(res_last) & set(cm_last)):
        if res_last[obj] != cm_last[obj]:
            errors.append(
                f"openbank_analytics.{obj} is defined differently by the clickhouse/V*.sql resources "
                f"and by the init ConfigMap — the last resource to define it has not reached the "
                f"ConfigMap, so a fresh warehouse would build an older version of this object"
            )

    return (errors, len(set(seen) | cm_objects))


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
        errs, _ = check(root)
        if errs:
            print(f"SELF-TEST FAIL: clean tree flagged: {errs}")
            ok = False

        # known-positive A: a resource object the ConfigMap does not create (the V4 case).
        (sqldir / "V2__b.sql").write_text(
            "-- CREATE VIEW openbank_analytics.commented_out AS SELECT 1;\n"
            "CREATE OR REPLACE VIEW openbank_analytics.silver_party_accounts AS SELECT 1;\n"
        )
        errs, _ = check(root)
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
        errs, _ = check(root)
        if not any("orphan_view" in e for e in errs):
            print("SELF-TEST FAIL: a ConfigMap object with no resource was not flagged")
            ok = False

        # known-positive C: an absent subject must fail, never read as clean.
        (sqldir / "V1__a.sql").unlink()
        (sqldir / "V2__b.sql").unlink()
        if not check(root)[0]:
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

    errors, examined = check(Path(args.root))
    # Printed on BOTH paths: a gate that found its corpus and then failed on it must not also read
    # as having lost it.
    gatelib.subjects(examined, "openbank_analytics objects across the DDL resources + ConfigMap")
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
