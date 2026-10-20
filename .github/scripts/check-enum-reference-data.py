#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Enum reference-data ratchet: business vocabulary hard-coded as a Kotlin `enum class` (rules.yaml:
# enum_reference_data).
#
# THE DEFECT
#   An enum is the right tool for a CLOSED vocabulary the code branches on (a state machine, a
#   protocol code). It is the wrong tool for reference data a bank changes by decision: turnover
#   bands, notice periods, risk scales, rate indices. Two shapes give that away mechanically, and
#   this gate checks only those two, because both are decidable without judgement:
#
#   (a) NUMERIC-ENCODED VALUES — the value NAME carries a number that is really a parameter:
#       `UP_TO_30K`, `OVER_500K`, `DAYS_7`, `PCT_25_TO_50`, `DPD_1_30`, `AGE_18_25`. The threshold
#       is a business decision living in an identifier; changing it is a code change, a migration
#       of every persisted value and a breaking API change, and the number cannot be read by
#       anything that is not the compiler.
#   (b) VALUE-SET DUPLICATES ACROSS MODULES — the same (>= 80 % overlapping, >= 3 values) set
#       declared as a separate enum in two different services. Each copy drifts on its own: the
#       AML risk scale exists as four incompatible scales today, and the benchmark rate index as
#       three enums that already disagree on members. One vocabulary, one owner.
#
#   Deliberately NOT checked: tenors (`PRIBOR_3M` names an instrument, not a threshold), protocol
#   versions (`PACS_008`, `TLS_1_3`), and single-module duplicates (one owner already). The audit
#   that motivated this (2026-10-20, ~46 clear cases in 674 enums) found the remaining cases need a
#   human to decide, and a gate that cries wolf about correct code is worth less than nothing.
#
# WHY A RATCHET
#   The remediation (move the vocabulary into a reference-data table or one shared owner) is a
#   per-cluster design decision with API and migration consequences. Today's hits are BASELINED,
#   each against its tracking issue; a NEW hit fails. A baseline entry that no longer matches
#   anything is reported as STALE and fails too, so the list cannot rot in either direction.
#
# EXIT CODES
#   0  no new occurrences, no stale baseline entries
#   1  a new occurrence, or a stale baseline entry
#   2  the check could not run (tree not found) or the baseline is malformed
#
# Run:  python3 .github/scripts/check-enum-reference-data.py [--root .] [--self-test] [--list]

import argparse
import pathlib
import re
import sys
import tempfile

import gatelib

BASELINE_FILE = ".github/scripts/enum-reference-data-baseline.txt"

OVERLAP = 0.8
MIN_VALUES = 3

# Numeric-encoded value names. Each alternative names a THRESHOLD/RANGE idiom, not merely a digit:
# a digit alone matches protocol codes and tenors, which are identities, not parameters.
NUMERIC_VALUE = re.compile(
    r"""^(?:
        (?:UP_TO|UPTO|OVER|ABOVE|BELOW|UNDER|LESS_THAN|MORE_THAN|AT_LEAST|AT_MOST|MAX|MIN)_\d
      | .*\d+_(?:TO|PLUS)(?:_|$)
      | .*\d+[KM]_TO_
      | (?:DAYS?|WEEKS?|MONTHS?|YEARS?|HOURS?|MINUTES?)_\d+(?:_|$)
      | .*_?\d+_(?:DAYS?|WEEKS?|MONTHS?|YEARS?|HOURS?|MINUTES?)$
      | (?:PCT|PERCENT|AGE|DPD|LTV|DTI)_\d
      | .*\d+_(?:PCT|PERCENT)$
    )""",
    re.VERBOSE,
)

# Lifecycle, severity and periodicity words every domain reuses independently. A cross-module overlap
# made ONLY of these is not evidence of a copied vocabulary (measured 2026-10-20: without this set the
# duplicate half reported 146 pairs, ~60 % of them unrelated status machines sharing
# APPROVED/PROPOSED/REJECTED). Same-name copies are still reported, see findings().
GENERIC_VALUES = frozenset(
    """
    APPROVED PROPOSED REJECTED PENDING OPEN CLOSED RESOLVED ACTIVE INACTIVE EXPIRED REVOKED SUSPENDED
    CANCELLED CANCELED COMPLETED FAILED DRAFT PUBLISHED RETIRED ARCHIVED CONFIRMED RELEASED RESERVED
    COMMITTED DECLINED SIGNED SETTLED POSTED REVERSED SUCCEEDED SUCCESS FAILURE IN_PROGRESS NEW CREATED
    DELETED UPDATED SUBMITTED ACCEPTED UNKNOWN NONE OTHER ALL DAY WEEK MONTH QUARTER YEAR DAILY WEEKLY
    MONTHLY QUARTERLY ANNUALLY YEARLY ONCE LOW MEDIUM HIGH CRITICAL INFO WARNING ERROR DIAGNOSED
    UNDER_REVIEW ESCALATED REVIEW SCHEDULED
    """.split()
)

ENUM_HEAD = re.compile(r"\benum\s+class\s+(\w+)")
IDENT = re.compile(r"^[A-Z][A-Z0-9_]*$")


def strip_comments(src: str) -> str:
    src = re.sub(r"/\*.*?\*/", " ", src, flags=re.S)
    src = re.sub(r"//[^\n]*", " ", src)
    return re.sub(r'"(?:\\.|[^"\\])*"', '""', src)


def enum_entries(src: str, start: int) -> list[str]:
    """Entries of the enum whose body opens at the first `{` after `start` (constructor parens skipped)."""
    i, depth = start, 0
    while i < len(src):
        c = src[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        elif c == "{" and depth == 0:
            break
        i += 1
    else:
        return []
    i += 1
    body_start, depth = i, 0
    while i < len(src):
        c = src[i]
        if c in "({[":
            depth += 1
        elif c in ")}]":
            if depth == 0:
                break
            depth -= 1
        elif c == ";" and depth == 0:
            break
        i += 1
    body = src[body_start:i]
    # drop nested argument lists / bodies so commas inside them do not split entries
    flat, depth = [], 0
    for c in body:
        if c in "({[":
            depth += 1
        elif c in ")}]":
            depth -= 1
        elif depth == 0:
            flat.append(c)
    out = []
    for part in "".join(flat).split(","):
        tok = re.sub(r"@\w+(?:\.\w+)*", " ", part).split()
        if tok and IDENT.match(tok[-1]):
            out.append(tok[-1])
    return out


def scan_sources(files) -> list[tuple[str, str, str, list[str]]]:
    """[(module, path, enum, values)] for every enum class in the given (module, path, text)."""
    found = []
    for module, path, text in files:
        src = strip_comments(text)
        for m in ENUM_HEAD.finditer(src):
            values = enum_entries(src, m.end())
            if values:
                found.append((module, path, m.group(1), values))
    return found


def collect(root: pathlib.Path):
    files = []
    for path in sorted(root.glob("openbank-*/**/src/main/**/*.kt")):
        rel = path.relative_to(root).as_posix()
        if "/build/" in rel or "/generated" in rel.lower():
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        if "enum class" not in text:
            continue
        files.append((rel.split("/", 1)[0], rel, text))
    return files


def findings(enums) -> set[str]:
    """Stable finding keys: `numeric <module> <Enum> <VALUE>` and `duplicate <module> <Enum>`.

    A duplicate is keyed per ENUM, not per pair, so N copies cost N baseline lines rather than
    N*(N-1)/2, and a new copy in one more module is exactly one new key.
    """
    out = set()
    for module, _path, name, values in enums:
        for v in values:
            if NUMERIC_VALUE.match(v):
                out.add(f"numeric {module} {name} {v}")
    sets = [(m, n, frozenset(v)) for m, _p, n, v in enums if len(set(v)) >= MIN_VALUES]
    for i, (ma, na, va) in enumerate(sets):
        for mb, nb, vb in sets[i + 1 :]:
            if ma == mb:
                continue
            shared = va & vb
            if len(shared) / min(len(va), len(vb)) < OVERLAP:
                continue
            # A lifecycle (PENDING/APPROVED/REJECTED) recurring in two services is coincidence, not a
            # shared vocabulary -- unless the enum is a verbatim copy under the same name.
            if not (shared - GENERIC_VALUES) and na != nb:
                continue
            out.add(f"duplicate {ma} {na}")
            out.add(f"duplicate {mb} {nb}")
    return out


def load_baseline(path: pathlib.Path) -> dict[str, str]:
    """key -> issue. Line format: `<finding key>  # #<issue>`. Raises ValueError when malformed."""
    base = {}
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, sep, issue = line.partition("#")
        issue = issue.strip()
        if not sep or not re.fullmatch(r"#?\d+.*", issue):
            raise ValueError(f"{path}:{n}: entry without a tracking issue: {line!r}")
        key = " ".join(key.split())
        if key in base:
            raise ValueError(f"{path}:{n}: duplicate entry {key!r}")
        base[key] = issue
    return base


def evaluate(current: set[str], baseline: dict[str, str]) -> tuple[list[str], list[str]]:
    return sorted(current - baseline.keys()), sorted(baseline.keys() - current)


def run(root: pathlib.Path, baseline_path: pathlib.Path, list_only=False) -> int:
    enums = scan_sources(collect(root))
    current = findings(enums)
    if list_only:
        for k in sorted(current):
            print(k)
        return 0
    try:
        baseline = load_baseline(baseline_path)
    except (OSError, ValueError) as e:
        print(f"ERROR: baseline unusable: {e}", file=sys.stderr)
        return 2
    new, stale = evaluate(current, baseline)
    gatelib.subjects(len(enums), "enum classes scanned")
    for k in new:
        print(f"NEW: {k}")
    for k in stale:
        print(f"STALE baseline entry (no longer matches; remove it): {k}  # {baseline[k]}")
    if new:
        print(
            "\nAn enum value encoding a number (threshold/band/period) or an enum duplicating another "
            "module's value set is reference data in code (rules.yaml: enum_reference_data). Model it as "
            "data, or reuse the owning module's type. If it truly is a closed vocabulary, baseline it in "
            f"{BASELINE_FILE} with a tracking issue."
        )
    if new or stale:
        return 1
    print(f"OK: {len(current)} finding(s), all baselined; {len(enums)} enum classes scanned")
    return 0


def self_test() -> int:
    good = """
        enum class Status { ACTIVE, CLOSED, FROZEN }
        enum class Tenor(val months: Int) { PRIBOR_1M(1), PRIBOR_3M(3), PACS_008(0); fun x() = 1 }
    """
    bad_numeric = """
        enum class Turnover { UP_TO_30K, FROM_30K_TO_100K, OVER_500K }
        enum class Notice { DAYS_7, DAYS_30 }
        enum class Band(val lo: Int) { PCT_25_TO_50(25), DPD_1_30(1) }
    """
    dup_a = "enum class RiskLevel { LOW, MEDIUM, HIGH, VERY_HIGH }"
    dup_b = "enum class AmlRisk {\n  @Deprecated(\"x\") LOW,\n  MEDIUM, // c\n  HIGH,\n  CRITICAL,\n}"
    idx_a = "enum class RateIndex { CZEONIA, PRIBOR_3M, ESTR, EURIBOR_3M }"
    idx_b = "enum class LoanRateIndex {\n  CZEONIA,\n  PRIBOR_3M,\n  ESTR,\n  EURIBOR_3M,\n}"
    failures = []

    def check(label, cond):
        if not cond:
            failures.append(label)

    # known-negative: closed vocabulary, tenors, protocol codes, same-module duplicates
    neg = findings(scan_sources([("svc-a", "a.kt", good), ("svc-a", "b.kt", dup_a), ("svc-a", "c.kt", dup_b)]))
    check(f"known-negative must be clean, got {sorted(neg)}", neg == set())
    # known-positive numeric
    pos = findings(scan_sources([("svc-a", "a.kt", bad_numeric)]))
    for v in ("UP_TO_30K", "FROM_30K_TO_100K", "OVER_500K", "DAYS_7", "DAYS_30", "PCT_25_TO_50", "DPD_1_30"):
        check(f"numeric {v} not flagged", any(k.endswith(" " + v) for k in pos))
    # known-positive cross-module duplicate of domain values (multi-line, trailing comma)
    dup = findings(scan_sources([("svc-a", "a.kt", idx_a), ("svc-b", "b.kt", idx_b)]))
    check(f"cross-module duplicate not flagged: {dup}", dup == {"duplicate svc-a RateIndex", "duplicate svc-b LoanRateIndex"})
    # known-negative: differently named enums sharing only generic words (annotations, comments)
    gen = findings(scan_sources([("svc-a", "a.kt", dup_a), ("svc-b", "b.kt", dup_b)]))
    check(f"generic-only overlap must not flag: {gen}", gen == set())
    # known-positive: verbatim same-name copy flags even when its values are generic
    same = findings(scan_sources([("svc-a", "a.kt", dup_a), ("svc-b", "b.kt", dup_a)]))
    check(f"same-name copy not flagged: {same}", same == {"duplicate svc-a RiskLevel", "duplicate svc-b RiskLevel"})
    # below-threshold overlap is not a duplicate
    near = findings(scan_sources([("svc-a", "a.kt", "enum class X { A, B, C, D, E }"), ("svc-b", "b.kt", "enum class Y { A, B, C, F, G }")]))
    check(f"60% overlap must not flag: {near}", near == set())
    # ratchet: new fails, baselined passes, stale reported
    cur = {"numeric svc-a Notice DAYS_7"}
    check("new not reported", evaluate(cur, {}) == (sorted(cur), []))
    check("baselined reported", evaluate(cur, {"numeric svc-a Notice DAYS_7": "#1"}) == ([], []))
    check("stale not reported", evaluate(set(), {"numeric svc-a Notice DAYS_7": "#1"}) == ([], ["numeric svc-a Notice DAYS_7"]))
    # end-to-end through run(): an injected enum on a real tree must exit 1, its baseline 0, stale 1
    with tempfile.TemporaryDirectory() as d:
        root = pathlib.Path(d)
        f = root / "openbank-x-service/src/main/kotlin/T.kt"
        f.parent.mkdir(parents=True)
        f.write_text("enum class Notice { DAYS_7, DAYS_30, NONE }\n")
        (root / "openbank-x-service/src/test/kotlin").mkdir(parents=True)
        (root / "openbank-x-service/src/test/kotlin/U.kt").write_text("enum class Z { OVER_1K, UP_TO_2K, A }\n")
        bl = root / "bl.txt"
        bl.write_text("")
        check("injected enum must fail", run(root, bl) == 1)
        bl.write_text("numeric openbank-x-service Notice DAYS_7  # #1\nnumeric openbank-x-service Notice DAYS_30  # #1\n")
        check("baselined tree must pass (src/test ignored)", run(root, bl) == 0)
        bl.write_text(bl.read_text() + "numeric openbank-x-service Gone OVER_1K  # #1\n")
        check("stale entry must fail", run(root, bl) == 1)
        bl.write_text("numeric openbank-x-service Notice DAYS_7\n")
        check("entry without issue must exit 2", run(root, bl) == 2)
    for f in failures:
        print(f"SELF-TEST FAIL: {f}")
    print("self-test:", "FAIL" if failures else "PASS")
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--baseline", default=None)
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--list", action="store_true", help="print current findings and exit 0")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    root = pathlib.Path(a.root)
    if not root.is_dir() or not any(root.glob("openbank-*")):
        print(f"ERROR: no openbank-* tree under {root}", file=sys.stderr)
        return 2
    return run(root, pathlib.Path(a.baseline) if a.baseline else root / BASELINE_FILE, a.list)


if __name__ == "__main__":
    sys.exit(main())
