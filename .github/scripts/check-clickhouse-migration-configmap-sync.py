#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Every ClickHouse migration in the source-of-record directory is represented in the boot ConfigMap.

WHY THIS GATE EXISTS
    #7645. The ClickHouse warehouse has no migration mechanism: its schema is applied by a
    ConfigMap mounted into /docker-entrypoint-initdb.d, and ClickHouse only executes those scripts
    when the data directory is EMPTY. So any schema change made after the cluster's first boot is
    inert on the live cluster (a genuine gap this gate does not close — see the ADR/issue for the
    idempotent-apply-Job direction) and is real only for the NEXT fresh boot: a rebuilt cluster, a
    new environment, disaster recovery. Nothing scans the migration directory to keep the ConfigMap
    in step with it, so a migration can be merged, reviewed and believed-deployed while a fresh
    boot would never see it. That happened three times before this gate existed: V9's
    synthetic_provenance migration (adds a `synthetic` column and filters synthetic events out of
    every baseline aggregate) and V10's credit_funnel were both merged and both absent from the
    ConfigMap, so a rebuilt warehouse would still mix synthetic/test events into every real
    aggregate the admin cockpit reads.

WHAT "IN SYNC" MEANS HERE
    The two sides use different numbering schemes on purpose (Flyway `V<n>__name.sql` in the source
    directory; the ConfigMap uses a plain `<nn>-kebab-name.sql` key because it is not applied by
    Flyway at all — the header comment in 01-bronze-silver.sql explains why). So this gate does NOT
    compare filenames or keys as strings; it compares the migration NUMBER extracted from each side
    (the `V<n>` prefix vs the `<nn>-` key prefix) as a set of integers. In sync means: the set of
    numbers in the source directory equals the set of numbers represented as ConfigMap keys — same
    count AND same specific numbers, not just a matching count (a ConfigMap that has 11 keys but is
    missing V9 and carries two of some other number would pass a bare count check and fail this
    one). A duplicate number within the source directory itself (two files claiming the same `V<n>`,
    the exact defect #7645 reported) is also flagged directly, independent of the ConfigMap
    comparison, because it is invalid regardless of what the ConfigMap says.

WHAT IT DOES NOT DO
    It does not check that a migration's SQL body was faithfully copied into its ConfigMap key
    (that would need a semantic SQL diff this gate does not attempt) — only that a key exists for
    every migration number. It also does not build or run the idempotent-apply-Job the issue
    describes as the real fix for "every change after first boot is inert" — that is real
    infrastructure work needing cluster access this gate cannot exercise, and is explicitly left for
    a follow-up.

USAGE
    check-clickhouse-migration-configmap-sync.py [--enforce] [--self-test]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gatelib

REPO = Path(__file__).resolve().parents[2]
MIGRATIONS_DIR = REPO / "openbank-analytics-sink/src/main/resources/clickhouse"
CONFIGMAP_PATH = (
    REPO / "openbank-infra/gitops/components/analytics/clickhouse-init-configmap.yaml"
)

MIGRATION_RE = re.compile(r"^V(\d+)__.+\.sql$")
CONFIGMAP_KEY_RE = re.compile(r"^\s{2}(\d+)-[a-z0-9-]+\.sql:\s*\|?\s*$")


def migration_numbers(migrations_dir: Path) -> dict[int, list[str]]:
    """Migration number -> list of filenames claiming it (>1 entry is a naming collision)."""
    numbers: dict[int, list[str]] = {}
    if not migrations_dir.is_dir():
        return numbers
    for path in sorted(migrations_dir.glob("V*.sql")):
        m = MIGRATION_RE.match(path.name)
        if not m:
            continue
        numbers.setdefault(int(m.group(1)), []).append(path.name)
    return numbers


def configmap_numbers(text: str) -> dict[int, str]:
    """ConfigMap key number -> key name, for keys that are actually IN `data`.

    WHY THIS PARSES YAML RATHER THAN LINES (#8893). The line regex matches any two-space-indented
    `<nn>-name.sql:` key ANYWHERE in the document, and this file puts `data` first and
    `kind`/`metadata` last. A migration appended to the end of the file therefore lands under
    `metadata:` — a place ClickHouse never reads — while looking correct in a diff and satisfying
    the old parser. Measured: a key mis-nested exactly that way left this gate and its DDL sibling
    both green, which is the failure this gate exists to prevent, one level up.

    The line scan is kept as a SECOND question, not a replacement: a DDL-shaped key outside `data`
    is reported explicitly rather than merely being absent, because "you put it in the wrong
    block" and "you forgot it" need different fixes.
    """
    numbers: dict[int, str] = {}
    for key in _data_keys(text):
        m = CONFIGMAP_KEY_RE.match(f"  {key}:")
        if m:
            numbers[int(m.group(1))] = key
    return numbers


def _data_keys(text: str) -> list[str]:
    """Keys of the ConfigMap's `data` mapping. Empty when the document will not parse."""
    try:
        doc = yaml.safe_load(text)
    except yaml.YAMLError:
        return []
    if not isinstance(doc, dict):
        return []
    data = doc.get("data")
    return sorted(data.keys()) if isinstance(data, dict) else []


def misplaced_ddl_keys(text: str) -> list[str]:
    """DDL-shaped keys that parse into some block OTHER than `data` — the mis-nesting above."""
    try:
        doc = yaml.safe_load(text)
    except yaml.YAMLError:
        return []
    if not isinstance(doc, dict):
        return []
    misplaced: list[str] = []
    for block, value in doc.items():
        if block == "data" or not isinstance(value, dict):
            continue
        for key in value:
            if isinstance(key, str) and CONFIGMAP_KEY_RE.match(f"  {key}:"):
                misplaced.append(f"{block}.{key}")
    return sorted(misplaced)


def findings(repo: Path) -> tuple[list[str], int]:
    out: list[str] = []

    mig_numbers = migration_numbers(MIGRATIONS_DIR)
    total = len(mig_numbers)

    for number, names in sorted(mig_numbers.items()):
        if len(names) > 1:
            out.append(
                f"V{number} is claimed by {len(names)} files ({', '.join(sorted(names))}) — "
                "renumber so each migration number is unique"
            )

    if not CONFIGMAP_PATH.is_file():
        out.append(f"ConfigMap not found at {CONFIGMAP_PATH.relative_to(repo)}")
        return out, total

    cm_text = gatelib.read_text(CONFIGMAP_PATH)
    cm_numbers = configmap_numbers(cm_text)

    for key in misplaced_ddl_keys(cm_text):
        out.append(
            f"{key} is a DDL key outside `data` in {CONFIGMAP_PATH.relative_to(repo)} — "
            "ClickHouse only runs keys under `data`, so this migration would never be applied "
            "on a fresh boot despite looking present in the file"
        )

    missing = sorted(set(mig_numbers) - set(cm_numbers))
    for number in missing:
        names = ", ".join(sorted(mig_numbers[number]))
        out.append(
            f"V{number} ({names}) has no corresponding key in "
            f"{CONFIGMAP_PATH.relative_to(repo)} — a freshly booted ClickHouse cluster "
            "will never apply it"
        )

    extra = sorted(set(cm_numbers) - set(mig_numbers))
    for number in extra:
        out.append(
            f"ConfigMap key '{cm_numbers[number]}' (number {number}) has no matching "
            f"V{number}__*.sql migration in {MIGRATIONS_DIR.relative_to(repo)} — "
            "an orphan key, or the migration was renamed/removed without updating the ConfigMap"
        )

    return out, total


# --- self-test --------------------------------------------------------------------------------


def self_test() -> int:
    failed = 0

    def check(name: str, mig: dict[int, list[str]], cm: dict[int, str], expect_clean: bool) -> None:
        nonlocal failed
        problems: list[str] = []
        for number, names in sorted(mig.items()):
            if len(names) > 1:
                problems.append(f"V{number} collision")
        missing = sorted(set(mig) - set(cm))
        problems += [f"missing V{n}" for n in missing]
        extra = sorted(set(cm) - set(mig))
        problems += [f"extra key {n}" for n in extra]
        is_clean = not problems
        if is_clean != expect_clean:
            print(f"SELF-TEST FAIL: {name} (expected clean={expect_clean}, got clean={is_clean}: {problems})")
            failed += 1
        else:
            print(f"self-test ok: {name}")

    # A synced pair: three migrations, three ConfigMap keys, numbers line up despite the
    # different naming schemes (V<n>__ vs <nn>-kebab).
    synced_mig = {1: ["V1__a.sql"], 2: ["V2__b.sql"], 3: ["V3__c.sql"]}
    synced_cm = {1: "01-a.sql", 2: "02-b.sql", 3: "03-c.sql"}
    check("synced source dir and ConfigMap pass", synced_mig, synced_cm, True)

    # The exact #7645 shape: a migration merged (V3) with no ConfigMap key for it.
    out_of_sync_mig = {1: ["V1__a.sql"], 2: ["V2__b.sql"], 3: ["V3__c.sql"]}
    out_of_sync_cm = {1: "01-a.sql", 2: "02-b.sql"}
    check("a migration with no ConfigMap key fails", out_of_sync_mig, out_of_sync_cm, False)

    # A duplicate V-number in the source directory itself (the literal naming collision #7645
    # reported: V9__party_credit_profile.sql and V9__synthetic_provenance.sql).
    duplicate_mig = {1: ["V1__a.sql"], 9: ["V9__x.sql", "V9__y.sql"]}
    duplicate_cm = {1: "01-a.sql", 9: "09-x.sql"}
    check("a duplicate migration number fails even if the ConfigMap has a key for it", duplicate_mig, duplicate_cm, False)

    # Same COUNT on both sides but the wrong numbers — proves this isn't just len(mig) == len(cm).
    same_count_wrong_numbers_mig = {1: ["V1__a.sql"], 2: ["V2__b.sql"]}
    same_count_wrong_numbers_cm = {1: "01-a.sql", 5: "05-z.sql"}
    check("matching counts with mismatched numbers still fails", same_count_wrong_numbers_mig, same_count_wrong_numbers_cm, False)

    # An orphan ConfigMap key with no migration behind it.
    orphan_mig = {1: ["V1__a.sql"]}
    orphan_cm = {1: "01-a.sql", 2: "02-ghost.sql"}
    check("an orphan ConfigMap key fails", orphan_mig, orphan_cm, False)

    # --- regex-level checks against real syntax shapes -----------------------------------------
    for name, fname, expect in (
        ("a real Flyway filename matches", "V10__credit_funnel.sql", 10),
        ("a non-migration file is ignored", "README.md", None),
    ):
        m = MIGRATION_RE.match(fname)
        got = int(m.group(1)) if m else None
        if got != expect:
            print(f"SELF-TEST FAIL: {name} (expected {expect}, got {got})")
            failed += 1
        else:
            print(f"self-test ok: {name}")

    cm_sample = (
        "apiVersion: v1\n"
        "data:\n"
        "  09-synthetic-provenance.sql: |\n"
        "    -- some sql\n"
        "kind: ConfigMap\n"
        "metadata:\n"
        "  name: clickhouse-init\n"
    )
    got_numbers = configmap_numbers(cm_sample)
    if got_numbers != {9: "09-synthetic-provenance.sql"}:
        print(f"SELF-TEST FAIL: configmap_numbers parse (got {got_numbers})")
        failed += 1
    else:
        print("self-test ok: a key under `data` is counted")

    # The known-positive this gate was blind to before #8893: same key, wrong block.
    misnested = cm_sample.replace(
        "  name: clickhouse-init\n",
        "  name: clickhouse-init\n  14-credit-lifecycle.sql: |\n    -- some sql\n",
    )
    if configmap_numbers(misnested) != {9: "09-synthetic-provenance.sql"}:
        print("SELF-TEST FAIL: a key under `metadata` must NOT count as present")
        failed += 1
    elif misplaced_ddl_keys(misnested) != ["metadata.14-credit-lifecycle.sql"]:
        print(f"SELF-TEST FAIL: mis-nested key not reported (got {misplaced_ddl_keys(misnested)})")
        failed += 1
    else:
        print("self-test ok: a DDL key outside `data` is both uncounted and reported")

    if misplaced_ddl_keys(cm_sample):
        print("SELF-TEST FAIL: a correct document must report no misplaced keys")
        failed += 1
    else:
        print("self-test ok: a correct document reports no misplaced keys")

    # --- against the live repo: prove the actual fix is in sync --------------------------------
    live_out, live_total = findings(REPO)
    if live_out:
        print(f"SELF-TEST FAIL: live repo is not in sync: {live_out}")
        failed += 1
    else:
        print(f"self-test ok: live repo in sync ({live_total} migrations, {live_total} ConfigMap keys)")

    return failed


def main() -> int:
    if "--self-test" in sys.argv:
        return 1 if self_test() else 0
    out, total = findings(REPO)
    gatelib.subjects(total, "ClickHouse migrations")
    if out:
        print(f"{len(out)} ClickHouse migration/ConfigMap drift finding(s):")
        for f in out:
            print(f"  {f}")
        return 1 if "--enforce" in sys.argv else 0
    print(f"clean: {total} ClickHouse migrations, every one has a matching ConfigMap key")
    return 0


if __name__ == "__main__":
    sys.exit(main())
