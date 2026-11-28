#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

"""ADR-0326 phase b: closed, unique, append-only fleet error-code catalogue.

Every production enum implementing libs-domain ErrorCode is a subject. Current codes must match
CODE/CATEGORY/RETRYABLE/TITLE in error-code-catalogue-baseline.tsv, and every published row must
still exist. The separate duplicate-owner baseline records only the legacy shared wire codes; a
new owner or disagreeing metadata fails. --write-baseline is the explicit initial/reviewed rewrite
path; normal CI never rewrites either file.
"""

from __future__ import annotations

import argparse
import pathlib
import sys

import gatelib
from error_code_catalogue import (
    baseline_lines,
    duplicate_owner_lines,
    evaluate,
    load_baseline,
    load_duplicate_owners,
    scan,
)

BASELINE = ".github/scripts/error-code-catalogue-baseline.tsv"
DUPLICATES = ".github/scripts/error-code-duplicate-owners.tsv"
BASELINE_HEADER = """# ADR-0326 append-only error-code catalogue. Generated from production ErrorCode enums.
# Schema: CODE<TAB>CATEGORY<TAB>RETRYABLE<TAB>TITLE
# Never remove or edit a published row. Add a new code in the same change as its enum.
"""
DUPLICATES_HEADER = """# Legacy duplicate wire-code owners (ADR-0326 phase b debt, issue #11610).
# Schema: CODE<TAB>MODULE<TAB>ENUM
# A new duplicate owner fails. Remove a row only when that owner disappears.
"""


def run(root: pathlib.Path, write_baseline: bool = False, list_only: bool = False) -> int:
    try:
        records, subjects = scan(root)
        gatelib.subjects(subjects, "Kotlin src/main files scanned")
        if list_only:
            for line in baseline_lines(records):
                print(line)
            return 0
        if write_baseline:
            (root / BASELINE).write_text(BASELINE_HEADER + "\n".join(baseline_lines(records)) + "\n", encoding="utf-8")
            (root / DUPLICATES).write_text(
                DUPLICATES_HEADER + "\n".join(duplicate_owner_lines(records)) + "\n", encoding="utf-8"
            )
            print(f"WROTE {len(baseline_lines(records))} codes and {len(duplicate_owner_lines(records))} legacy duplicate-owner rows")
            return 0
        errors = evaluate(records, load_baseline(root), load_duplicate_owners(root))
    except (OSError, ValueError) as exc:
        print(f"::error::error-code catalogue could not be evaluated: {exc}", file=sys.stderr)
        return 2
    for error in errors:
        print(f"::error::{error}")
    if errors:
        print(f"FAIL: {len(errors)} error-code catalogue finding(s); see ADR-0326 and {BASELINE}")
        return 1
    print(f"OK: {len(records)} enum entries, {len({record.code for record in records})} unique published codes")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--write-baseline", action="store_true")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        from test_error_code_catalogue import run_self_test

        return run_self_test()
    return run(pathlib.Path(args.root).resolve(), write_baseline=args.write_baseline, list_only=args.list)


if __name__ == "__main__":
    raise SystemExit(main())
