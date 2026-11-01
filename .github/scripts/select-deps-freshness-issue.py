#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Select the standing dependency report from open deps-freshness issues."""

from __future__ import annotations

import argparse
import json
import sys

TITLE = "Dependency freshness report"


def select_issue(issues: object) -> int | None:
    if not isinstance(issues, list):
        raise ValueError("expected an issue list")
    matches = []
    for issue in issues:
        if not isinstance(issue, dict) or not isinstance(issue.get("title"), str):
            raise ValueError("invalid issue in list")
        if issue["title"] == TITLE:
            number = issue.get("number")
            if not isinstance(number, int) or number <= 0:
                raise ValueError("standing issue has invalid number")
            matches.append(number)
    if len(matches) > 1:
        raise ValueError("multiple standing dependency freshness reports")
    return matches[0] if matches else None


def self_test() -> None:
    unrelated = {"number": 12132, "title": "OpenBao upgrade"}
    standing = {"number": 8720, "title": TITLE}
    assert select_issue([unrelated, standing]) == 8720
    assert select_issue([unrelated]) is None
    assert select_issue([]) is None
    for bad in ([standing, dict(standing, number=9000)], {"title": TITLE},
                [{"number": 8720, "title": TITLE}, {"number": 1}]):
        try:
            select_issue(bad)
        except ValueError:
            continue
        raise AssertionError(f"unsafe issue selection accepted: {bad!r}")
    print("deps-freshness issue selector: all cases passed")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    try:
        if args.self_test:
            self_test()
        else:
            number = select_issue(json.load(sys.stdin))
            if number is not None:
                print(number)
    except (ValueError, AssertionError) as exc:
        print(f"::error::{exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
