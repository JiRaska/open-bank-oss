#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Every `<service>/governance/requirements.yaml` row must be well-formed, and a BUILT row must
point at evidence that exists.

WHY THIS EXISTS
---------------
ADR-0344's functional baseline catalogue named what a treasury system needs, as prose, with no
link from an item to the code that delivers it. Learning which of its items existed took a hand
audit of 38 of them. A requirements file turns each item into a row — id, text, status, evidence —
and this checker makes the cheapest lie impossible: a row claiming BUILT whose evidence is absent
or does not resolve.

EVIDENCE FORMS
  path/to/file           a repo-relative path that must exist
  openapi:/api/v1/...     a path key in the service's src/main/resources/openapi.yaml
  test:ClassName          a ClassName.kt/.java/.ts(x) file under the service's src/test (or e2e)

WHAT IT REPORTS
  * schema errors and a BUILT row with no or unresolvable evidence      (finding)
  * a PARTIAL row whose evidence does not resolve                         (finding)
  * with --diff-base: a PR changing <service>/src/main without touching its
    requirements.yaml                                                     (note, never a finding)

ADVISORY FIRST (ADR-0144): exit 0 unless --enforce.

Usage:  check-requirements-traceability.py --root . [--diff-base <sha>] [--enforce]
        check-requirements-traceability.py --self-test
"""

from __future__ import annotations

import argparse
import pathlib
import subprocess
import sys
import tempfile

import yaml

STATUSES = {"BUILT", "PARTIAL", "MISSING"}
KEYS = {"id", "text", "status", "evidence"}


def openapi_paths(service: pathlib.Path) -> set[str]:
    spec = service / "src/main/resources/openapi.yaml"
    if not spec.exists():
        return set()
    return set((yaml.safe_load(spec.read_text(encoding="utf-8")) or {}).get("paths", {}) or {})


def test_exists(service: pathlib.Path, cls: str) -> bool:
    for sub in ("src/test", "e2e"):
        base = service / sub
        if base.is_dir() and any(base.rglob(f"{cls}.*")):
            return True
    return False


def resolve(root: pathlib.Path, service: pathlib.Path, ev: str, spec: set[str]) -> bool:
    if ev.startswith("openapi:"):
        return ev[len("openapi:"):] in spec
    if ev.startswith("test:"):
        return test_exists(service, ev[len("test:"):])
    return (root / ev).exists()


def check_file(root: pathlib.Path, req: pathlib.Path) -> tuple[list[str], int]:
    service = req.parent.parent
    rel = req.relative_to(root)
    try:
        doc = yaml.safe_load(req.read_text(encoding="utf-8")) or {}
    except yaml.YAMLError as e:
        return [f"{rel}: not parseable YAML: {e}"], 0
    rows = doc.get("requirements")
    if not isinstance(rows, list) or not rows:
        return [f"{rel}: `requirements:` must be a non-empty list"], 0
    spec = openapi_paths(service)
    findings, seen = [], set()
    for i, row in enumerate(rows):
        where = f"{rel}[{i}]"
        if not isinstance(row, dict):
            findings.append(f"{where}: entry is not a mapping")
            continue
        missing, extra = KEYS - row.keys(), row.keys() - KEYS
        if missing or extra:
            findings.append(f"{where}: keys must be exactly {sorted(KEYS)} (missing {sorted(missing)}, extra {sorted(extra)})")
            continue
        rid, status, evidence = row["id"], row["status"], row["evidence"]
        where = f"{rel} {rid}"
        if not isinstance(rid, str) or not rid or rid in seen:
            findings.append(f"{where}: id must be a unique non-empty string")
        seen.add(rid)
        if not isinstance(row["text"], str) or not row["text"].strip():
            findings.append(f"{where}: text must be a non-empty string")
        if status not in STATUSES:
            findings.append(f"{where}: status {status!r} not in {sorted(STATUSES)}")
            continue
        if not isinstance(evidence, list) or not all(isinstance(e, str) and e for e in evidence):
            findings.append(f"{where}: evidence must be a list of non-empty strings")
            continue
        if status == "BUILT" and not evidence:
            findings.append(f"{where}: BUILT with no evidence")
        for ev in evidence:
            if not resolve(root, service, ev, spec):
                findings.append(f"{where}: {status} evidence does not resolve: {ev}")
    return findings, len(rows)


def touched_without_update(root: pathlib.Path, reqs: list[pathlib.Path], diff_base: str) -> list[str]:
    changed = subprocess.run(["git", "-C", str(root), "diff", "--name-only", f"{diff_base}...HEAD"],
                             check=True, capture_output=True, text=True).stdout.splitlines()
    notes = []
    for req in reqs:
        svc = str(req.parent.parent.relative_to(root))
        own = str(req.relative_to(root))
        if any(c.startswith(f"{svc}/src/main/") for c in changed) and own not in changed:
            notes.append(f"{svc}/src/main changed but {own} was not touched — "
                         f"if a requirement's status or evidence moved, update its row")
    return notes


def run(root: pathlib.Path, diff_base: str | None) -> tuple[list[str], list[str], int]:
    reqs = sorted(root.glob("*/governance/requirements.yaml"))
    findings, subjects = [], 0
    for req in reqs:
        f, n = check_file(root, req)
        findings += f
        subjects += n
    notes = touched_without_update(root, reqs, diff_base) if diff_base else []
    return findings, notes, subjects


def self_test() -> int:
    fails = 0
    with tempfile.TemporaryDirectory() as t:
        root = pathlib.Path(t)
        svc = root / "openbank-x-service"
        (svc / "governance").mkdir(parents=True)
        (svc / "src/main/resources").mkdir(parents=True)
        (svc / "src/test/kotlin").mkdir(parents=True)
        (svc / "src/main/Real.kt").write_text("x")
        (svc / "src/test/kotlin/RealTest.kt").write_text("x")
        (svc / "src/main/resources/openapi.yaml").write_text("paths:\n  /api/v1/x: {}\n")
        good = ("requirements:\n"
                "  - {id: X-1, text: t, status: BUILT, evidence: [openbank-x-service/src/main/Real.kt, "
                "'openapi:/api/v1/x', 'test:RealTest']}\n"
                "  - {id: X-2, text: t, status: MISSING, evidence: []}\n")
        cases = [
            ("well-formed BUILT/MISSING", good, False),
            ("BUILT with a missing file", good.replace("Real.kt,", "Gone.kt,"), True),
            ("BUILT with an unknown openapi path", good.replace("/api/v1/x'", "/api/v1/y'"), True),
            ("BUILT with an unknown test class", good.replace("RealTest", "GoneTest"), True),
            ("BUILT with empty evidence", good + "  - {id: X-3, text: t, status: BUILT, evidence: []}\n", True),
            ("bad status", good.replace("MISSING", "DONE"), True),
            ("duplicate id", good.replace("X-2", "X-1"), True),
            ("missing key", good.replace(", evidence: []}", "}"), True),
        ]
        for name, body, must_fail in cases:
            (svc / "governance/requirements.yaml").write_text(body)
            findings, _, _ = run(root, None)
            got = bool(findings)
            ok = got == must_fail
            fails += 0 if ok else 1
            print(f"  [{'ok' if ok else 'FAIL'}] {name}: {'red' if got else 'green'}"
                  + ("" if ok else f" {findings}"))
        # the advisory note: a src/main change without the file is noted; touching the file clears it
        (svc / "governance/requirements.yaml").write_text(good)
        g = lambda *a: subprocess.run(["git", "-C", t, *a], check=True, capture_output=True)  # noqa: E731
        g("init", "-q", "-b", "main")
        g("config", "user.email", "t@t")
        g("config", "user.name", "t")
        g("add", ".")
        g("commit", "-qm", "base")
        base = subprocess.run(["git", "-C", t, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
        (svc / "src/main/Real.kt").write_text("y")
        g("commit", "-qam", "code only")
        _, notes, _ = run(root, base)
        if len(notes) != 1:
            print(f"  [FAIL] src/main change without requirements.yaml not noted: {notes}")
            fails += 1
        (svc / "governance/requirements.yaml").write_text(good + "# touched\n")
        g("commit", "-qam", "and the file")
        _, notes, _ = run(root, base)
        if notes:
            print(f"  [FAIL] note raised although requirements.yaml was touched: {notes}")
            fails += 1
    if fails == 0:
        print("check-requirements-traceability self-test: ok (7 known-positives red, 1 known-negative green, note both ways)")
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--diff-base")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()
    findings, notes, subjects = run(pathlib.Path(a.root), a.diff_base or None)
    for n in notes:
        print(f"::notice::{n}")
    for f in findings:
        print(f"::warning::{f}")
    print(f"SUBJECTS={subjects}")
    print(f"check-requirements-traceability: {subjects} requirement row(s), {len(findings)} finding(s)")
    return 1 if (findings and a.enforce) else 0


if __name__ == "__main__":
    sys.exit(main())
