#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# A label description over 100 characters breaks Label sync, and nothing here noticed (#4192).
#
# GitHub caps a label description at 100 characters. `.github/labels.yml` is applied by the Label
# sync workflow, which fails the whole apply on the first over-long entry — so ONE label added
# with a slightly wordy description turns `main` red on a push-triggered workflow that is addressed
# to nobody. This has now happened THREE times, each fixed by shortening the string and each
# leaving the next occurrence exactly as reachable as before.
#
# The limit is invisible from every angle a contributor uses: the YAML is valid, yamllint is
# clean, the file reads fine, and the failure surfaces only after the merge, in a workflow whose
# red nobody is paged for. That is the whole argument for a gate rather than a fourth fix.
#
# Deliberately NOT checking name length or colour format: neither has ever broken here, and a
# guard that asserts things no one has got wrong is unfalsified padding.

from __future__ import annotations

import argparse
import pathlib
import sys

import yaml

LIMIT = 100
LABELS_FILE = ".github/labels.yml"


def over_limit(doc) -> list[tuple[str, int]]:
    """(name, length) for every label whose description exceeds GitHub's cap.

    Takes the PARSED document rather than a path so the self-test can feed it shapes that never
    touch disk — including the two that would otherwise crash a naive reader: a bare string in the
    list, and an entry with no description at all.
    """
    items = doc if isinstance(doc, list) else (doc or {}).get("labels", [])
    out: list[tuple[str, int]] = []
    for entry in items or []:
        if not isinstance(entry, dict):
            continue  # a bare string is a name-only label; it has no description to be too long
        desc = entry.get("description") or ""
        if len(desc) > LIMIT:
            out.append((str(entry.get("name", "(unnamed)")), len(desc)))
    return out


def self_test() -> int:
    fails = 0

    def expect(name, got, want):
        nonlocal fails
        if got == want:
            print(f"  ok   {name}")
        else:
            print(f"  FAIL {name}: want {want}, got {got}")
            fails = 1

    long_desc = "x" * (LIMIT + 1)
    expect("a description one character over the cap is flagged",
           over_limit([{"name": "a", "description": long_desc}]), [("a", LIMIT + 1)])
    expect("a description exactly at the cap is fine",
           over_limit([{"name": "a", "description": "x" * LIMIT}]), [])
    expect("a label with no description is fine",
           over_limit([{"name": "a", "color": "fff"}]), [])
    expect("a null description is fine, not a crash",
           over_limit([{"name": "a", "description": None}]), [])
    expect("a bare string entry is skipped, not a crash",
           over_limit(["just-a-name"]), [])
    expect("the real #4192 shape is flagged by name",
           [n for n, _ in over_limit([
               {"name": "ok", "description": "short"},
               {"name": "main-red", "description": long_desc},
           ])], ["main-red"])
    # A mapping wrapper is the other shape the file could take.
    expect("a labels: mapping is read too",
           over_limit({"labels": [{"name": "a", "description": long_desc}]}), [("a", LIMIT + 1)])

    if fails:
        print("check-label-descriptions: self-test FAIL")
        return 1
    print("check-label-descriptions: self-test PASS")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("root", nargs="?", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    path = pathlib.Path(args.root) / LABELS_FILE
    if not path.is_file():
        print(f"::error::{LABELS_FILE} not found — this gate is about that file and cannot run.")
        return 1

    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    bad = over_limit(doc)
    for name, length in bad:
        print(
            f"::error::{LABELS_FILE}: label '{name}' has a {length}-character description; GitHub "
            f"caps it at {LIMIT}. Label sync fails the whole apply on the first over-long entry, "
            f"so this turns main red on a workflow nobody is paged for (#4192)."
        )
    total = len(doc if isinstance(doc, list) else (doc or {}).get("labels", []) or [])
    print(
        f"check-label-descriptions: {total} label(s) checked, {len(bad)} over the "
        f"{LIMIT}-character cap."
    )
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
