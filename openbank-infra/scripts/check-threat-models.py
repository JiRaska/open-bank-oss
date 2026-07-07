#!/usr/bin/env python3
"""Threat-model coverage gate for money-path services (ADR-0030 D2).

Enforces `rules.yaml: money_path_services`: every money-path bounded context MUST
ship a structured threat model at `docs/threat-models/<service>.md`. A design-phase
STRIDE/DFD review is mandatory for the services that move money (ledger, payments,
SCA, …); this gate makes that a *technical* fact, not etiquette, and ratchets it —
adding a money-path service or deleting its threat model fails CI.

A file counts as a real threat model only if it is non-trivial AND mentions the
STRIDE method (or its categories) — so an empty stub can't satisfy the gate.

stdlib only — runs in PR CI with no cloud credentials.

Modes:
  (default)   gate     -> print findings, exit 1 if any money-path service lacks
                          a valid threat model.
  --report             -> markdown report to stdout (exit 0); first line is
                          `THREATMODEL_FINDING=0|1` for an audit workflow.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
RULES = REPO / "openbank-libs" / "governance" / "rules.yaml"
TM_DIR = REPO / "docs" / "threat-models"

MIN_BYTES = 500  # a real STRIDE/DFD model is well past this; a stub is not.
# STRIDE categories — at least one must appear, so the doc is a real threat model
# and not just a placeholder heading.
STRIDE = re.compile(
    r"\bSTRIDE\b|\bspoofing\b|\btampering\b|\brepudiation\b|"
    r"information disclosure|\bdenial of service\b|elevation of privilege",
    re.IGNORECASE,
)


def money_path_services() -> list[str]:
    """Parse the `money_path_services:` YAML list without a yaml dependency."""
    if not RULES.exists():
        return []
    out: list[str] = []
    in_block = False
    for line in RULES.read_text(encoding="utf-8").splitlines():
        if re.match(r"^money_path_services:\s*$", line):
            in_block = True
            continue
        if in_block:
            # Trailing `# comment` allowed — entries carry ADR references inline.
            m = re.match(r"^\s+-\s+(\S+)\s*(?:#.*)?$", line)
            if m:
                out.append(m.group(1))
            elif line.strip() and not line.startswith((" ", "\t")):
                break  # next top-level key ends the block
    return out


def evaluate(service: str) -> tuple[str, str]:
    """Return (status, detail) where status in {ok, missing, stub}."""
    path = TM_DIR / f"{service}.md"
    if not path.exists():
        return "missing", "no docs/threat-models/%s.md" % service
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as e:
        return "missing", f"unreadable: {e}"
    if len(text.encode("utf-8")) < MIN_BYTES:
        return "stub", f"only {len(text)} chars (< {MIN_BYTES} — looks like a stub)"
    if not STRIDE.search(text):
        return "stub", "no STRIDE/threat categories found — not a structured model"
    return "ok", f"{len(text)} chars"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", action="store_true", help="markdown report, exit 0")
    args = ap.parse_args()

    services = money_path_services()
    results = [(s, *evaluate(s)) for s in services]
    bad = [(s, st, d) for (s, st, d) in results if st != "ok"]

    if args.report:
        print(f"THREATMODEL_FINDING={1 if bad else 0}")
        print("\n## Threat-model coverage — money-path (ADR-0030 D2)\n")
        print(f"- money-path services: **{len(services)}**")
        print(f"- with a valid threat model: **{len(services) - len(bad)}**")
        print(f"- gaps: **{len(bad)}**\n")
        if bad:
            print("| service | status | detail |")
            print("|---|---|---|")
            for s, st, d in bad:
                print(f"| `{s}` | {st} | {d} |")
        else:
            print("All money-path services carry a structured threat model. ✅")
        return 0

    print(f"Threat-model gate (ADR-0030 D2): {len(services)} money-path services")
    for s, st, d in results:
        mark = "OK " if st == "ok" else "!! "
        print(f"  {mark}{s}: {st} ({d})")
    if bad:
        print(
            f"\nFAIL: {len(bad)} money-path service(s) without a valid threat model.\n"
            "Add docs/threat-models/<service>.md (STRIDE/DFD) — ADR-0030 D2, rules.yaml.",
            file=sys.stderr,
        )
        return 1
    print(f"\nOK: all {len(services)} money-path services have a structured threat model.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
