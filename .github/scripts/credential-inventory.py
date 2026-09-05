#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Inventory of long-lived credentials, with deadlines (ADR-0279 WS4 #18).

WHY. The OpenBao migration (ADR-0099) made DB passwords dynamic (`database/creds/…`,
24h TTL) — but every OIDC client secret and API key projected by an ExternalSecret from
a static `secret/data/…` kv path is a long-lived credential with no expiry anywhere in
the system that serves it. Measured 2026-09-04: 113 gitops files touch ExternalSecrets;
the fleet had no single list of which of them hold a static credential and by when it
must be rotated or migrated. This script is that list, plus the ratchet that stops the
list from growing silently.

CONVENTION INTRODUCED (and enforced on NEW manifests only):
  * an ExternalSecret whose remoteRef points at a STATIC kv path (not `database/creds/…`
    or another dynamic mount) must carry metadata annotation
    `openbank.io/rotation-deadline: YYYY-MM-DD` — the date by which the credential is
    rotated or the reference migrates to a dynamic mount;
  * dynamic references may annotate `openbank.io/rotation: dynamic` for the reader's
    benefit, but the path prefix already proves it, so it is not required.

MODES:
  --inventory           full scan, markdown table + counts (the weekly standing issue)
  --enforce-new BASE    diff-scoped gate: a NEW file declaring a static ExternalSecret
                        without the deadline annotation fails
  --self-test           offline fixtures

Existing undeclared statics are DEBT rendered by --inventory, not PR-blocking findings —
same staged shape as every ratchet in this repo.
"""

from __future__ import annotations

import argparse
import datetime as dt
import re
import subprocess
import sys
from pathlib import Path

GITOPS = Path("openbank-infra/gitops")
DYNAMIC_KEY = re.compile(r"database/creds/|(^|/)creds/[a-z0-9-]+-db-vault-role")
DEADLINE = "openbank.io/rotation-deadline"
DYNAMIC_ANN = "openbank.io/rotation"
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

DOC_SPLIT = re.compile(r"^---\s*$", re.M)


class Cred:
    def __init__(self, doc: str, file: Path):
        self.file = file
        self.name = _field(doc, r"name:\s*([a-z0-9.-]+)") or "?"
        self.namespace = _field(doc, r"namespace:\s*([a-z0-9.-]+)") or "?"
        self.keys = re.findall(r"key:\s*([A-Za-z0-9_./-]+)", doc)
        self.dynamic = any(DYNAMIC_KEY.search(k) for k in self.keys)
        ann_block = re.search(r"annotations:\n((?:\s{4,}[^\n]*\n?)*)", doc)
        self.annotations = ann_block.group(1) if ann_block else ""
        self.deadline = _field(self.annotations, re.escape(DEADLINE) + r':\s*"?([^"\n]+)"?')

    @property
    def static(self) -> bool:
        return bool(self.keys) and not self.dynamic


def _field(text: str, pattern: str) -> str | None:
    m = re.search(pattern, text)
    return m.group(1).strip() if m else None


def scan(root: Path) -> list[Cred]:
    out: list[Cred] = []
    for f in sorted(root.rglob("*.yaml")):
        if GITOPS.parts[0] not in f.parts and str(GITOPS) not in str(f):
            continue
        text = f.read_text()
        for doc in DOC_SPLIT.split(text):
            if re.search(r"^kind:\s*ExternalSecret\s*$", doc, re.M):
                out.append(Cred(doc, f))
    return out


def validate_deadline(c: Cred, today: dt.date) -> str | None:
    """None when OK, else the violation text."""
    if c.deadline is None:
        return f"missing annotation `{DEADLINE}`"
    if not DATE_RE.match(c.deadline):
        return f"`{DEADLINE}: {c.deadline}` is not YYYY-MM-DD"
    if dt.date.fromisoformat(c.deadline) < today:
        return f"`{DEADLINE}: {c.deadline}` is in the past — rotate or re-date with a reason"
    return None


def inventory(root: Path, today: dt.date) -> tuple[str, int]:
    creds = scan(root)
    statics = [c for c in creds if c.static]
    declared = [c for c in statics if c.deadline and not validate_deadline(c, today)]
    overdue = [c for c in statics if c.deadline and validate_deadline(c, today) and "past" in (validate_deadline(c, today) or "")]
    undeclared = [c for c in statics if not c.deadline]
    lines = [
        "# Long-lived credential inventory", "",
        f"ExternalSecrets: **{len(creds)}** total — {len(statics)} static (long-lived), "
        f"{len(creds) - len(statics)} dynamic (`database/creds/…`, ADR-0099).", "",
        f"Static credentials with a valid rotation deadline: **{len(declared)}**; "
        f"overdue: **{len(overdue)}**; undeclared (debt): **{len(undeclared)}**.", "",
        "## Static credentials", "",
        "| credential | namespace | backend key(s) | rotation deadline |", "|---|---|---|---|",
    ]
    for c in sorted(statics, key=lambda c: (c.namespace, c.name)):
        dl = c.deadline or "— **UNDECLARED**"
        lines.append(f"| `{c.name}` | {c.namespace} | {', '.join(f'`{k}`' for k in c.keys)} | {dl} |")
    lines += ["", "_Maintained by credential-inventory.yml (ADR-0279 #18). Do not edit by hand. "
              "New static ExternalSecrets must carry `openbank.io/rotation-deadline` — enforced "
              "by the `credential-deadline-ratchet` gate._"]
    print(f"credential-inventory: {len(creds)} ExternalSecrets, {len(statics)} static, "
          f"{len(declared)} with deadline, {len(overdue)} overdue, {len(undeclared)} undeclared")
    return "\n".join(lines) + "\n", 1 if overdue else 0


def enforce_new(base: str, today: dt.date) -> int:
    out = subprocess.run(["git", "diff", "--name-only", "--diff-filter=A", f"{base}...HEAD"],
                         capture_output=True, text=True, check=True)
    bad = 0
    for name in out.stdout.splitlines():
        f = Path(name)
        if not f.exists() or f.suffix != ".yaml":
            continue
        for doc in DOC_SPLIT.split(f.read_text()):
            if not re.search(r"^kind:\s*ExternalSecret\s*$", doc, re.M):
                continue
            c = Cred(doc, f)
            if not c.static:
                continue
            violation = validate_deadline(c, today)
            if violation:
                print(f"::error::{name}: new static ExternalSecret '{c.name}' {violation}. "
                      f"Rotate-by dates are the only thing between a long-lived credential and "
                      f"an immortal one (ADR-0279 #18).")
                bad += 1
    print(f"credential-deadline-ratchet: {bad} finding(s)")
    return 1 if bad else 0


def self_test() -> int:
    bad = 0
    today = dt.date(2026, 9, 4)
    doc_static = (
        "kind: ExternalSecret\nmetadata:\n  name: foo-oidc\n  namespace: foo\n"
        "spec:\n  target:\n    name: foo-oidc\n  data:\n  - remoteRef:\n      key: account-service\n")
    c = Cred(doc_static, Path("x.yaml"))
    if not c.static or c.dynamic:
        print("self-test FAIL: static classification"); bad += 1
    if validate_deadline(c, today) is None:
        print("self-test FAIL: missing deadline must be a violation"); bad += 1
    doc_dyn = doc_static.replace("key: account-service", "key: database/creds/foo-db-vault-role")
    if Cred(doc_dyn, Path("x.yaml")).static:
        print("self-test FAIL: dynamic path classified as static"); bad += 1
    doc_ok = doc_static.replace("metadata:\n",
                                f'metadata:\n  annotations:\n    {DEADLINE}: "2027-01-01"\n')
    if validate_deadline(Cred(doc_ok, Path("x.yaml")), today) is not None:
        print("self-test FAIL: valid future deadline flagged"); bad += 1
    doc_past = doc_ok.replace("2027-01-01", "2026-01-01")
    v = validate_deadline(Cred(doc_past, Path("x.yaml")), today)
    if v is None or "past" not in v:
        print("self-test FAIL: past deadline not caught"); bad += 1
    doc_bad = doc_ok.replace('"2027-01-01"', '"next quarter"')
    if validate_deadline(Cred(doc_bad, Path("x.yaml")), today) is None:
        print("self-test FAIL: non-date deadline accepted"); bad += 1
    print("credential-inventory self-test: " + ("clean" if not bad else f"{bad} failure(s)"))
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--inventory", action="store_true")
    ap.add_argument("--md", help="write the inventory markdown here")
    ap.add_argument("--enforce-new", metavar="BASE")
    ap.add_argument("--today", default=dt.date.today().isoformat(),
                    help="YYYY-MM-DD override (deterministic tests; no bare clock reads in CI)")
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    today = dt.date.fromisoformat(args.today)
    if args.enforce_new:
        return enforce_new(args.enforce_new, today)
    if args.inventory:
        md, rc = inventory(Path(args.root), today)
        if args.md:
            Path(args.md).write_text(md)
        else:
            print(md)
        return rc
    ap.error("one of --inventory / --enforce-new / --self-test is required")


if __name__ == "__main__":
    sys.exit(main())
