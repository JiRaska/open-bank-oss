#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Derive a release's end-of-support from SECURITY.md's policy — never declare it by hand (#9881).

SECURITY.md, "Support period and end-of-support (per released component)":

  * beta (0.x): supported 12 months from the release date, OR 90 days after the release that
    supersedes it — whichever is later;
  * from a component's first 1.x release: 5 years from that release's date (CRA Art. 13(8)).

and it states that end-of-support is DATA derived mechanically from those rules against the
release date, to be carried in the evidence bundle. This module is that derivation.

Two things it is careful about:

1. THE RELEASE DATE, NOT THE BUILD TIME. build-release-evidence.sh stamps `built_at` with the
   current time. For the release-please job that is minutes after the release; for
   backfill-release-evidence.yml it can be months later. Deriving from `built_at` would silently
   push every backfilled release's end-of-support into the future. The caller passes the tagged
   commit's date.

2. SAYING WHAT IS NOT YET KNOWN. For a 0.x release the "90 days after supersession" half depends
   on a release that does not exist at build time. The derivation therefore publishes the
   12-month FLOOR and marks the date `open_ended`, rather than inventing the later bound.
"""
from __future__ import annotations

import datetime as dt
import json
import sys

BETA_MONTHS = 12
BETA_AFTER_SUPERSEDED_DAYS = 90
PRODUCTION_YEARS = 5


def _add_months(d: dt.date, months: int) -> dt.date:
    y, m = divmod(d.month - 1 + months, 12)
    year, month = d.year + y, m + 1
    # Clamp to the month's last day: 2026-02-29 does not exist, a leap-day release maps to Feb 28.
    for day in (d.day, 30, 29, 28):
        try:
            return dt.date(year, month, min(d.day, day))
        except ValueError:
            continue
    raise ValueError(d)


def derive(version: str, released_on: dt.date) -> dict:
    parts = version.lstrip("v").split(".")
    if len(parts) < 2 or not all(p.isdigit() for p in parts[:2]):
        raise ValueError(f"not a semantic version: {version!r}")
    major = int(parts[0])
    if major >= 1:
        return {
            "policy": "production",
            "released_on": released_on.isoformat(),
            "end_of_support": _add_months(released_on, PRODUCTION_YEARS * 12).isoformat(),
            "open_ended": False,
            "rule": f"{PRODUCTION_YEARS} years from the release date (SECURITY.md; CRA Art. 13(8))",
        }
    return {
        "policy": "beta",
        "released_on": released_on.isoformat(),
        "end_of_support_floor": _add_months(released_on, BETA_MONTHS).isoformat(),
        "open_ended": True,
        "rule": (f"{BETA_MONTHS} months from the release date, or {BETA_AFTER_SUPERSEDED_DAYS} days after "
                 f"the superseding release, whichever is later (SECURITY.md) — the later bound depends on "
                 f"a release that does not exist yet, so only the floor is determined"),
    }


def self_test() -> int:
    d = dt.date
    cases = [
        (("1.22.0", d(2026, 9, 13)), {"policy": "production", "end_of_support": "2031-09-13", "open_ended": False}),
        (("0.39.0", d(2026, 9, 13)), {"policy": "beta", "end_of_support_floor": "2027-09-13", "open_ended": True}),
        (("v2.0.1", d(2026, 1, 31)), {"policy": "production", "end_of_support": "2031-01-31"}),
        (("0.1.0", d(2024, 2, 29)), {"end_of_support_floor": "2025-02-28"}),   # leap day clamps
        (("0.9.3", d(2026, 12, 31)), {"end_of_support_floor": "2027-12-31"}),  # year rollover
    ]
    bad = 0
    for (ver, when), want in cases:
        got = derive(ver, when)
        ok = all(got.get(k) == v for k, v in want.items())
        # A beta release must never carry a determined end_of_support: that is the invented bound.
        if got["policy"] == "beta" and "end_of_support" in got:
            ok = False
        print(f"  {'ok ' if ok else 'BAD'} {ver} released {when}: {got}")
        bad += 0 if ok else 1
    for junk in ("latest", "1", ""):
        try:
            derive(junk, d(2026, 1, 1))
            print(f"  BAD {junk!r} accepted as a version")
            bad += 1
        except ValueError:
            print(f"  ok  {junk!r} rejected")
    print(f"SUBJECTS={len(cases) + 3}")
    print("release support-period self-test: " + ("PASS" if not bad else f"FAIL ({bad})"))
    return 1 if bad else 0


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        sys.exit(self_test())
    if len(sys.argv) != 3:
        sys.exit("usage: release_support_period.py <version> <YYYY-MM-DD> | --self-test")
    print(json.dumps(derive(sys.argv[1], dt.date.fromisoformat(sys.argv[2]))))
