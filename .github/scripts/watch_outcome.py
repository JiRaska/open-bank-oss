#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""One outcome vocabulary for every watchdog: CLEAN | FOUND | SKIPPED | UNREADABLE.

WHY THIS EXISTS
---------------
Measured over the scheduled runs of 2026-11-09..14: `auto-deploy red watch` was red on 20 of
20 ticks, `Service build stall watch` on 11 of 20 and `Pact verification reconcile` on most of
its ticks. Not one of those reds was a finding. Every one was the watch failing to READ
(a too-short page, a 409 from a cancel GitHub was not ready for, a run index that moved
mid-scan). Since a red watch had come to mean "the watch is flaky", a red run would not
have meant "found something" either. And the lane auto-deploy red watch exists to guard was
genuinely red the whole week.

The defect is that one boolean (the job conclusion) carried three answers. A watch now names
its answer explicitly:

  CLEAN       looked, nothing wrong                     job green
  FOUND       looked, something wrong, escalated        job green (the ISSUE is the signal)
  SKIPPED     deliberately did not act this tick        job green, says why
  UNREADABLE  could not establish an answer             job RED

So a red watch run means one thing only: the watch is blind. The standing-red sentinel
(standing-red-sentinel.py) turns a watch that stays red into an issue, so a blind watch is
escalated as well.

Usage (shell):   python3 .github/scripts/watch_outcome.py FOUND "lane red 3/3"
Usage (python):  from watch_outcome import emit; emit("CLEAN", "nothing stuck")
Exit status mirrors the outcome: 1 for UNREADABLE, 0 otherwise.
Gate `watch-outcome-declared` (check-watch-outcome.py) requires every scheduled watch to emit.
"""

from __future__ import annotations

import os
import sys

OUTCOMES = ("CLEAN", "FOUND", "SKIPPED", "UNREADABLE")


def emit(outcome: str, detail: str = "") -> int:
    if outcome not in OUTCOMES:
        raise ValueError(f"unknown watch outcome {outcome!r}; expected one of {OUTCOMES}")
    detail = " ".join(detail.split())[:300]
    level = "error" if outcome == "UNREADABLE" else "notice"
    print(f"::{level} title=watch-outcome::{outcome} {detail}".rstrip())
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(f"**watch-outcome: {outcome}** {detail}\n\n")
    return 1 if outcome == "UNREADABLE" else 0


def main(argv: list[str]) -> int:
    if len(argv) < 2 or argv[1] not in OUTCOMES:
        print(f"usage: {argv[0]} {{{'|'.join(OUTCOMES)}}} [detail...]", file=sys.stderr)
        return 2
    return emit(argv[1], " ".join(argv[2:]))


if __name__ == "__main__":
    sys.exit(main(sys.argv))
