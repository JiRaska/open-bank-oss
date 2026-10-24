#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Pin the disruption-budget SHAPE of the Karpenter `default` NodePool (issue #13726, item 3).

#13704 gave the spot `default` pool (which carries most CNPG pods) three protections, and nothing
enforced any of them -- a later edit that drops or loosens one reads as an ordinary tidy-up:

  1. a catch-all budget (no schedule, no reasons) of at most ONE node, for every reason;
  2. a `Drifted` budget of 0 nodes on a schedule, so AMI/kubelet drift rolls only inside a
     maintenance window (the window is the complement of the budget's duration);
  3. the overnight freeze: a scheduled budget with no reasons and 0 nodes.

Karpenter applies the most restrictive matching budget, so the invariant also forbids ANY budget
that names a larger number of nodes (e.g. "50%"): a loose entry is harmless today and live the
day the tight one is removed. Companion of check-stateful-not-on-spot.py (which owns the stateful
pool); the HCL is read with the same brace-matching approach, not a parser.

Usage:
    check-default-nodepool-budget.py              # gate
    check-default-nodepool-budget.py --self-test  # prove the gate can fail
"""
from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path

import gatelib

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
TF = Path("openbank-infra/aws/envs/sandbox-platform/main.tf")
HEADER = 'resource "kubectl_manifest" "nodepool_default"'
MAX_NODES = 1
MAX_DRIFT_WINDOW_H = 4  # 24h - duration of the Drifted-0 budget


def matching(text: str, i: int, open_c: str, close_c: str) -> int:
    depth = 0
    for k in range(i, len(text)):
        if text[k] == open_c:
            depth += 1
        elif text[k] == close_c:
            depth -= 1
            if depth == 0:
                return k
    return -1


def strip_comments(s: str) -> str:
    return re.sub(r"(?m)(#|//).*$", "", s)


def budgets(tf: str) -> list[dict] | None:
    i = tf.find(HEADER)
    if i < 0:
        return None
    j = tf.find("{", i)
    end = matching(tf, j, "{", "}")
    pool = strip_comments(tf[j:end + 1])
    m = re.search(r"\bbudgets\s*=\s*\[", pool)
    if not m:
        return None
    lo = m.end() - 1
    hi = matching(pool, lo, "[", "]")
    body = pool[lo + 1:hi]
    out, k = [], 0
    while True:
        a = body.find("{", k)
        if a < 0:
            return out
        b = matching(body, a, "{", "}")
        entry = body[a + 1:b]
        d = {key: val for key, val in re.findall(r'(\w+)\s*=\s*("[^"]*")', entry)}
        d = {key: val.strip('"') for key, val in d.items()}
        rm = re.search(r"reasons\s*=\s*\[([^\]]*)\]", entry)
        d["reasons"] = re.findall(r'"([^"]+)"', rm.group(1)) if rm else []
        out.append(d)
        k = b + 1


def nodes_value(n: str) -> float | None:
    if re.fullmatch(r"\d+", n):
        return float(n)
    if re.fullmatch(r"0+%", n):
        return 0.0
    return None  # any non-zero percentage is unbounded as far as this gate is concerned


def hours(d: str) -> float | None:
    m = re.fullmatch(r"(\d+)h(?:(\d+)m)?", d or "")
    return int(m.group(1)) + int(m.group(2) or 0) / 60 if m else None


def check_text(tf: str) -> tuple[list[str], int]:
    bs = budgets(tf)
    if bs is None:
        return [f"{TF}: no `nodepool_default` NodePool with a `budgets` list"], 0
    f: list[str] = []
    for b in bs:
        v = nodes_value(b.get("nodes", ""))
        if v is None or v > MAX_NODES:
            f.append(f"{TF}: default NodePool budget {b} allows more than {MAX_NODES} node at once")
    if not any(not b.get("schedule") and not b["reasons"] and nodes_value(b.get("nodes", "")) is not None
               for b in bs):
        f.append(f"{TF}: default NodePool has no catch-all budget (no schedule, no reasons) of "
                 f"<= {MAX_NODES} node")
    drift = [b for b in bs if b["reasons"] == ["Drifted"] and b.get("schedule")
             and nodes_value(b.get("nodes", "")) == 0]
    if not drift:
        f.append(f"{TF}: default NodePool has no scheduled `Drifted` budget with nodes = 0 -- drift would "
                 "roll at any hour")
    else:
        h = hours(drift[0].get("duration", ""))
        if h is None or 24 - h > MAX_DRIFT_WINDOW_H or h > 24:
            f.append(f"{TF}: Drifted-0 budget duration {drift[0].get('duration')!r} leaves a drift window "
                     f"longer than {MAX_DRIFT_WINDOW_H}h (or is unparseable)")
    if not any(b.get("schedule") and not b["reasons"] and nodes_value(b.get("nodes", "")) == 0 for b in bs):
        f.append(f"{TF}: default NodePool lost its freeze (scheduled budget, no reasons, nodes 0)")
    return f, len(bs)


def check(repo: Path) -> tuple[list[str], int]:
    p = repo / TF
    if not p.is_file():
        return [f"{TF}: file not found"], 0
    return check_text(p.read_text())


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    findings, n = check(REPO)
    gatelib.subjects(n, "default NodePool budgets examined")
    for x in findings:
        print(f"::error::{x}")
    if findings:
        print(f"FAIL: {len(findings)} finding(s)")
        return 1
    print(f"OK: default NodePool budgets pinned ({n} entries: <=1 node, Drifted windowed, freeze present)")
    return 0


def self_test() -> int:
    real = (REPO / TF).read_text()
    ok = True
    base, n = check_text(real)
    if base or n < 3:
        print(f"SELF-TEST FAIL: the real file must pass, got {base} ({n} budgets)")
        return 1
    print("self-test ok: current file passes")

    def sub(pat: str, rep: str, text: str = real) -> str:
        new, c = re.subn(pat, rep, text, count=1)
        if c != 1 or new == text:
            raise SystemExit(f"SELF-TEST FAIL: mutation {pat!r} did not apply -- the file changed shape")
        return new

    cases = {
        "catch-all budget removed": (sub(r'\n\s*\{ nodes = "1" \},(?=\n\s*# #11304)', ""), "no catch-all"),
        "catch-all raised to 50%": (sub(r'\{ nodes = "1" \},(?=\n\s*# #11304)', '{ nodes = "50%" },'), "more than"),
        "Drifted nodes raised to 5": (sub(r'nodes = "1", reasons = \["Drifted"\]', 'nodes = "5", reasons = ["Drifted"]'),
                                       "more than"),
        "schedule (Drifted 0) budget removed": (sub(r'\n\s*\{ schedule = "0 9 \* \* \*"[^\n]*\},', ""),
                                                 "no scheduled `Drifted`"),
        "Drifted window widened to 12h": (sub(r'duration = "22h"', 'duration = "12h"'), "drift window"),
        "freeze removed": (sub(r'\n\s*\{ schedule = "0 20 \* \* \*"[^\n]*\},', ""), "lost its freeze"),
        "pool removed": (sub(r'"nodepool_default"', '"nodepool_other"'), "no `nodepool_default`"),
    }
    for name, (mut, want) in cases.items():
        got, _ = check_text(mut)
        if not any(want in g for g in got):
            print(f"SELF-TEST FAIL: {name}: expected {want!r}, got {got}")
            ok = False
        else:
            print(f"self-test ok: {name}")
    with tempfile.TemporaryDirectory() as td:
        got, _ = check(Path(td))
        if not got:
            print("SELF-TEST FAIL: a missing file must be a finding")
            ok = False
    print("SELF-TEST PASS" if ok else "SELF-TEST FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
