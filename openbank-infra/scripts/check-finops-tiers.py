#!/usr/bin/env python3
"""FinOps workload-tier declared-side validator (ADR-0057).

Validates the `finops_tiers` block in rules.yaml on the DECLARED side — the half
that needs no cluster metrics. The measured side (declared-vs-observed drift) is the
classifier (ADR-0057 / ADR-0054 phase 2); until it ships, the `finops-tier-drift`
gate is `enforced: advisory` in rules.yaml, so THIS check is advisory too: it prints
findings and always exits 0, never blocking a PR. Flip to blocking when the policy's
`enforced:` flips to `block`.

Declared-side checks (deterministic, no cloud creds):
  1. every declared tier value is one of the tiers defined in finops_tiers.tiers
  2. every declared service key is a real `openbank-*` service directory in the repo
  3. no money_path service is declared BELOW T0 (demotion needs an ADR-0030 threat
     model + 2 approvals — it must not be silently set in rules.yaml)
  4. coverage report: declared vs total services (informational)

stdlib only — runs in PR CI with no cloud credentials, mirroring
check-version-lifecycle.py. The YAML is parsed with targeted line scanning (the same
pragmatic approach the version-lifecycle gate uses) rather than a yaml dependency.

Modes:
  (default)   advisory -> print findings, ALWAYS exit 0 (honours enforced: advisory)
  --report             -> markdown report to stdout for the weekly audit; first line
                          is `FINOPS_TIERS_FINDING=0|1` so the workflow can open/update
                          a tracking issue.
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
RULES = REPO / "openbank-libs" / "governance" / "rules.yaml"


def _read_rules() -> str:
    return RULES.read_text(encoding="utf-8")


def _top_level_block(text: str, key: str) -> list[str]:
    """Return the lines of a top-level `key:` block (the key line plus all more-
    indented lines beneath it), stopping at the next column-0 key."""
    lines = text.splitlines()
    out: list[str] = []
    inside = False
    for line in lines:
        if not inside:
            if re.match(rf"^{re.escape(key)}:\s*$", line):
                inside = True
            continue
        # a new column-0, non-comment, non-blank line ends the block
        if line and not line[0].isspace() and not line.lstrip().startswith("#"):
            break
        out.append(line)
    return out


def parse_money_path_services(text: str) -> set[str]:
    out: set[str] = set()
    for line in _top_level_block(text, "money_path_services"):
        m = re.match(r"\s*-\s*([A-Za-z0-9_-]+)\s*(#.*)?$", line)
        if m:
            out.add(m.group(1))
    return out


def parse_finops_tiers(text: str) -> tuple[set[str], dict[str, str], str]:
    """Return (tier_names, declared{service: tier}, enforced)."""
    block = _top_level_block(text, "finops_tiers")
    tier_names: set[str] = set()
    declared: dict[str, str] = {}
    enforced = ""

    # tiers: sub-block keys are indented 4 spaces (T0:, T1:, ...) under `  tiers:`
    in_tiers = False
    in_declared = False
    for line in block:
        # section toggles (2-space indent keys under finops_tiers)
        if re.match(r"^  tiers:\s*$", line):
            in_tiers, in_declared = True, False
            continue
        if re.match(r"^  declared:\s*$", line):
            in_tiers, in_declared = False, True
            continue
        if re.match(r"^  [A-Za-z0-9_]+:", line) and not line.startswith("    "):
            # any other 2-space key ends both sub-sections
            in_tiers = in_declared = False
            m = re.match(r"^  enforced:\s*([A-Za-z_]+)", line)
            if m:
                enforced = m.group(1)
            continue
        if in_tiers:
            m = re.match(r"^    ([A-Za-z0-9]+):\s*(#.*)?$", line)
            if m:
                tier_names.add(m.group(1))
        elif in_declared:
            m = re.match(r"^    ([A-Za-z0-9_-]+):\s*([A-Za-z0-9]+)\s*(#.*)?$", line)
            if m:
                declared[m.group(1)] = m.group(2)
    return tier_names, declared, enforced


def service_dirs() -> set[str]:
    return {
        p.name
        for p in REPO.iterdir()
        if p.is_dir() and p.name.startswith("openbank-") and (p / "src" / "main").exists()
    }


def count_subjects(declared, money_path_services) -> int:
    """How many services this check actually reasons about.

    Union, not sum: a service that is both explicitly declared and money-path is one subject.
    Extracted so the self-test drives the SHIPPED counter rather than a copy of it.
    """
    return len(set(declared) | set(money_path_services))


def evaluate() -> tuple[list[str], list[str], int]:
    """Return (errors, info, subject_count)."""
    text = _read_rules()
    money_path = parse_money_path_services(text)
    tiers, declared, enforced = parse_finops_tiers(text)
    services = service_dirs()

    errors: list[str] = []
    info: list[str] = []

    if not tiers:
        errors.append("finops_tiers.tiers is empty or unparseable — expected T0..T3.")
    # lowest (most-scaled) tiers, in order; T0 is always-on
    below_t0 = {t for t in tiers if t != "T0"}

    for svc, tier in sorted(declared.items()):
        if tier not in tiers:
            errors.append(f"{svc}: declared tier '{tier}' is not a defined tier {sorted(tiers)}.")
        if svc not in services:
            errors.append(f"{svc}: declared in finops_tiers but is not an openbank-* service directory.")
        if svc in money_path and tier in below_t0:
            errors.append(
                f"{svc}: money-path service declared '{tier}' (below T0). Demoting a money-path "
                f"service requires an ADR-0030 threat model + 2 approvals — it must not be set here."
            )

    if enforced not in ("advisory", "block"):
        errors.append(f"finops_tiers.enforced is '{enforced or '(missing)'}' — expected advisory|block.")

    # money-path inherits T0 via the baseline; union (not sum) so a service that is
    # both explicitly declared and money-path is counted once.
    classified = count_subjects(declared, money_path & services)
    total = len(services)
    info.append(
        f"tier coverage: {classified}/{total} services classified "
        f"({len(declared)} explicitly declared, {len(money_path & services)} money-path -> T0 baseline, "
        f"{total - classified} unclassified pending the classifier)."
    )
    info.append(f"declared: {declared or '{}'}")
    info.append(f"gate enforced: {enforced}")
    return errors, info, classified


def self_test() -> int:
    """Falsify the SUBJECT COUNT, which is the only thing this gate can go red on.

    The checker is advisory by rules.yaml (`finops_tiers.enforced: advisory`) and returns 0 for
    every finding, so registering it in gates.yaml buys exactly one thing: run-gates' subject
    floor, which fails when the declared set collapses. That makes the count — not the findings
    — the load-bearing output, and a counter that cannot be wrong is a floor that cannot fire.

    Measured 2026-09-05, which is why this exists: 3 of 68 services carry an explicit tier, and
    the script exits 0 whether that number is 3 or 0. Nothing anywhere would have noticed the
    difference.

    So: drive count_subjects() over fixtures with a KNOWN answer, including the empty one.
    """
    cases = [
        ("three declared, none money-path", {"a": "T2", "b": "T1", "c": "T0"}, set(), 3),
        ("declared and money-path union, not sum", {"a": "T0", "b": "T1"}, {"a", "z"}, 3),
        ("nothing declared, money-path only", {}, {"x", "y"}, 2),
        ("the collapse case: nothing at all", {}, set(), 0),
    ]
    bad = []
    for label, declared, money_path, want in cases:
        got = count_subjects(declared, money_path)
        mark = "ok  " if got == want else "FAIL"
        print(f"  {mark} {label}: want {want}, got {got}")
        if got != want:
            bad.append(label)
    # A counter that ignores its input would pass every case above by returning a constant, so
    # the cases deliberately span 0..3 and disagree with each other.
    if len({c[3] for c in cases}) < 3:
        bad.append("the fixtures no longer span enough distinct counts to catch a constant")
    if bad:
        print(f"\n::error::check-finops-tiers self-test: {', '.join(bad)}")
        return 1
    print("\nself-test ok: the subject count is falsifiable (4 cases, spanning 0..3)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", action="store_true", help="markdown report for the weekly audit")
    ap.add_argument("--self-test", action="store_true", dest="self_test",
                    help="prove the subject count can be wrong (see self_test)")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    errors, info, subject_count = evaluate()

    if args.report:
        finding = 1 if errors else 0
        print(f"FINOPS_TIERS_FINDING={finding}")
        print("## FinOps workload-tier declared-side audit (ADR-0057)\n")
        for line in info:
            print(f"- {line}")
        if errors:
            print("\n### Findings\n")
            for e in errors:
                print(f"- ⚠️ {e}")
        else:
            print("\nNo declared-side findings.")
        return 0

    print("FinOps workload-tier validator (ADR-0057, declared side) — advisory\n")
    for line in info:
        print(f"  {line}")
    # The load-bearing output. This checker returns 0 for every finding (advisory per
    # rules.yaml), so run-gates' `min_subjects` floor is the only way it can go red — and the
    # floor can only read this line. Measured 2026-09-05: 26 of 68 services classified, of
    # which just 3 explicitly declared; the script exited 0 and would have exited 0 at zero.
    print(f"SUBJECTS={subject_count}  # services with a tier (declared + money-path baseline)")
    if errors:
        print("\nFindings (advisory — not blocking until the classifier flips the gate to block):")
        for e in errors:
            print(f"  ⚠️ {e}")
    else:
        print("\n  OK — no declared-side findings.")
    # Advisory: honour rules.yaml `finops_tiers.enforced: advisory` — never block a PR.
    return 0


if __name__ == "__main__":
    sys.exit(main())
