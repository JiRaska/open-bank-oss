#!/usr/bin/env python3
"""FinOps workload-tier declared-side validator (ADR-0057).

Validates the `finops_tiers` block in rules.yaml on the DECLARED side — the half
that needs no cluster metrics. The measured side (declared-vs-observed drift) is the
classifier (ADR-0057 / ADR-0054 phase 2); until it ships, the `finops-tier-drift`
gate is `enforced: advisory` in rules.yaml.

That advisory setting governs the POLICY finding only — a money-path service declared below T0.
It never governed whether `rules.yaml` parses, whether a declared tier exists, or whether a
declared key names a real service. Those are config bugs, they do not improve when the classifier
ships, and they now fail under every setting. Until #9678 this file made no such distinction and
exited 0 against all of them, including a rules.yaml it could not parse — measured, three
rejectable inputs, three zeros.

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
  (default)   config bugs exit 1; a policy finding exits 0 while finops_tiers.enforced
              is `advisory`, and 1 once it says `block`
  --enforce   also fail on the policy finding, without waiting for the rules.yaml flip
  --self-test prove the check can fail: 7 cases, both directions
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


def evaluate() -> tuple[list[str], list[str]]:
    """Return (errors, info). Kept for callers that do not care which kind a finding is."""
    config, policy, info, _ = evaluate_split()
    return config + policy, info


def evaluate_split() -> tuple[list[str], list[str], list[str], int]:
    """Return (config_errors, policy_findings, info, declared_subject_count).

    The split is the point. `finops_tiers.enforced: advisory` is a statement about the DRIFT
    gate — declared-versus-observed, which needs the classifier that has not shipped. It was
    never a statement about whether `rules.yaml` parses, whether a declared tier exists, or
    whether a declared key names a real service. Those are config bugs: nothing about them
    improves when the classifier lands, and a typo'd tier is wrong under every policy setting.
    Conflating the two is how this check came to exit 0 against a malformed rules.yaml (#9678).
    """
    text = _read_rules()
    money_path = parse_money_path_services(text)
    tiers, declared, enforced = parse_finops_tiers(text)
    services = service_dirs()

    config: list[str] = []
    policy: list[str] = []
    info: list[str] = []

    if not tiers:
        config.append("finops_tiers.tiers is empty or unparseable — expected T0..T3.")
    # lowest (most-scaled) tiers, in order; T0 is always-on
    below_t0 = {t for t in tiers if t != "T0"}

    for svc, tier in sorted(declared.items()):
        if tier not in tiers:
            config.append(f"{svc}: declared tier '{tier}' is not a defined tier {sorted(tiers)}.")
        if svc not in services:
            config.append(f"{svc}: declared in finops_tiers but is not an openbank-* service directory.")
        if svc in money_path and tier in below_t0:
            policy.append(
                f"{svc}: money-path service declared '{tier}' (below T0). Demoting a money-path "
                f"service requires an ADR-0030 threat model + 2 approvals — it must not be set here."
            )

    if enforced not in ("advisory", "block"):
        config.append(f"finops_tiers.enforced is '{enforced or '(missing)'}' — expected advisory|block.")

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
    # The FLOORED subject count is the DECLARED side, not the union: 23 of the 26 union members
    # come from money_path_services, which `journey-money-path-accountability` already floors at
    # 20. A union floor is therefore green straight through a `declared: {}` wipe — the exact
    # collapse this gate was written about. Floor the half nothing else watches.
    return config, policy, info, len(declared)


EXIT_OK = 0
EXIT_FINDINGS = 1


def self_test() -> int:
    """Prove this check can FAIL. It could not, for its whole life (#9678).

    Three rejectable inputs were measured against the old version — a tier value that is not a
    defined tier, `enforced: block` with a money-path service below T0, and a rules.yaml the
    parser cannot read at all — and every one of them exited 0 while printing findings. A check
    that prints and exits 0 is a report; the gate label on it was the defect.

    Each case below drives the real `evaluate_split` over a temporary rules.yaml, so it exercises
    the parser and the classification, not a mock of them. The must-PASS case is as load-bearing
    as the must-FAIL ones: a self-test where everything fails proves nothing either.
    """
    import tempfile

    global RULES
    original = RULES
    failures: list[str] = []

    def run(label: str, yaml_text: str, *, enforce: bool, want_config: bool, want_policy: bool) -> None:
        nonlocal failures
        with tempfile.TemporaryDirectory() as td:
            f = pathlib.Path(td) / "rules.yaml"
            f.write_text(yaml_text, encoding="utf-8")
            globals()["RULES"] = f
            try:
                config, policy, _, _ = evaluate_split()
            finally:
                globals()["RULES"] = original
        ok = bool(config) == want_config and bool(policy) == want_policy
        blocking = bool(config) or (bool(policy) and enforce)
        print(f"  [{'ok ' if ok else 'FAIL'}] {label}: config={len(config)} policy={len(policy)} "
              f"blocking={blocking}")
        if not ok:
            failures.append(label)

    # A real money-path service directory, so case 3 is about the TIER and not about an unknown key.
    mp = sorted(parse_money_path_services(original.read_text(encoding="utf-8")) & service_dirs())
    if not mp:
        print("  [FAIL] self-test cannot run: no money-path service directory found")
        return EXIT_FINDINGS
    svc = mp[0]

    def doc(tier_line: str, enforced: str = "advisory") -> str:
        return (
            "money_path_services:\n"
            f"  - {svc}\n"
            "finops_tiers:\n"
            f"  enforced: {enforced}\n"
            "  tiers:\n"
            "    T0:\n"
            "      description: always-on\n"
            "    T1:\n"
            "      description: scaled\n"
            "  declared:\n"
            f"{tier_line}"
        )

    # must PASS — a money-path service at T0 under an advisory policy is the healthy shape
    run("a money-path service at T0 is clean", doc(f"    {svc}: T0"),
        enforce=False, want_config=False, want_policy=False)

    # must FAIL as a CONFIG bug, under every policy setting
    run("an undefined tier is a config error", doc(f"    {svc}: T9"),
        enforce=False, want_config=True, want_policy=False)
    run("a declared key that is not a service is a config error",
        doc("    openbank-does-not-exist: T0"), enforce=False, want_config=True, want_policy=False)
    run("an unparseable finops_tiers is a config error",
        "money_path_services:\n  - " + svc + "\nfinops_tiers:\n  enforced: advisory\n",
        enforce=False, want_config=True, want_policy=False)
    run("an invalid enforced value is a config error", doc(f"    {svc}: T0", enforced="maybe"),
        enforce=False, want_config=True, want_policy=False)

    # must be a POLICY finding — blocking only when enforcing
    run("a money-path service below T0 is a policy finding", doc(f"    {svc}: T1"),
        enforce=False, want_config=False, want_policy=True)
    run("…and the same input blocks under --enforce", doc(f"    {svc}: T1"),
        enforce=True, want_config=False, want_policy=True)

    # ── the SUBJECT COUNT is a second, independent output, and it fails differently ──────────
    #
    # Everything above falsifies the FINDINGS. The count is what `gates.yaml: min_subjects`
    # floors, and it can collapse while every case above still passes — a check reasoning about
    # zero services reports no findings, which reads as health. Measured 2026-09-05: 3 of 68
    # services carry an explicit tier, and before #9678 the script exited 0 whether that was 3
    # or 0. So drive count_subjects() over fixtures with a known answer, the empty one included.
    count_cases = [
        ("three declared, none money-path", {"a": "T2", "b": "T1", "c": "T0"}, set(), 3),
        ("declared and money-path union, not sum", {"a": "T0", "b": "T1"}, {"a", "z"}, 3),
        ("nothing declared, money-path only", {}, {"x", "y"}, 2),
        ("the collapse case: nothing at all", {}, set(), 0),
    ]
    for label, declared, money_path, want in count_cases:
        got = count_subjects(declared, money_path)
        ok = got == want
        print(f"  [{'ok ' if ok else 'FAIL'}] count: {label}: want {want}, got {got}")
        if not ok:
            failures.append(f"count: {label}")
    # A counter that ignored its input would pass every case above by returning a constant, so
    # the fixtures deliberately span 0..3 and disagree with each other.
    if len({c[3] for c in count_cases}) < 3:
        failures.append("the count fixtures no longer span enough distinct values to catch a constant")

    # count_subjects() is a one-line union and cannot plausibly drift; the real collapse mechanism
    # is the line-scanning parser upstream of it. Hold that to a fixture too, or a tightened regex
    # silently empties the count while every case above still passes (measured: `[A-Za-z0-9_-]+`
    # -> `[A-Za-z0-9]+` gives SUBJECTS=3 with the self-test green).
    snippet = (
        "money_path_services:\n"
        "  - openbank-ledger-service\n"
        "  - openbank-sepa-payment  # inline comment\n"
        "unrelated_key:\n"
        "  - openbank-not-money-path\n"
    )
    got_mp = parse_money_path_services(snippet)
    want_mp = {"openbank-ledger-service", "openbank-sepa-payment"}
    ok = got_mp == want_mp
    print(f"  [{'ok ' if ok else 'FAIL'}] parser: money_path_services from a fixture: "
          f"want {sorted(want_mp)}, got {sorted(got_mp)}")
    if not ok:
        failures.append("parse_money_path_services no longer reads a known-good block")

    print()
    if failures:
        print(f"SELF-TEST FAILED: {len(failures)} case(s): {', '.join(failures)}")
        return EXIT_FINDINGS
    print("self-test ok: the check can fail — config bugs block under any policy, a money-path")
    print("              demotion blocks under --enforce or `enforced: block`, and a healthy")
    print("              declaration still passes. The SUBJECT COUNT is falsifiable too —")
    print("              four fixtures spanning 0..3, plus the parser it is computed from.")
    return EXIT_OK


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", action="store_true", help="markdown report for the weekly audit")
    ap.add_argument(
        "--enforce",
        action="store_true",
        help="also fail on POLICY findings (money-path below T0). Config bugs fail regardless.",
    )
    ap.add_argument("--self-test", action="store_true", help="prove this check can fail")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    config, policy, info, subject_count = evaluate_split()
    errors = config + policy

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

    # `enforced: block` in rules.yaml means what it says. Before #9678 this value was read,
    # printed, and then ignored — the check exited 0 under `block` exactly as under `advisory`,
    # so flipping the policy would have changed nothing and everyone would have believed it had.
    enforcing = args.enforce or any(line == "gate enforced: block" for line in info)

    print("FinOps workload-tier validator (ADR-0057, declared side)\n")
    for line in info:
        print(f"  {line}")
    # The floor's only input. POLICY findings still return 0 while rules.yaml says `advisory`,
    # so for the drift half of this gate `min_subjects` is the one way it can go red — and the
    # floor can only read this line. (CONFIG errors block on their own since #9678; that is a
    # different half of the check and does not make the floor redundant.)
    print(f"SUBJECTS={subject_count}  # services with an EXPLICIT finops_tiers.declared entry")

    if config:
        print("\nCONFIG ERRORS (always blocking — these are bugs in rules.yaml, not policy):")
        for e in config:
            print(f"  ::error::{e}")
    if policy:
        label = "blocking" if enforcing else "advisory, not blocking while finops_tiers.enforced is advisory"
        print(f"\nPOLICY FINDINGS ({label}):")
        for e in policy:
            print(f"  {'::error::' if enforcing else '  ⚠️ '}{e}")
    if not config and not policy:
        print("\n  OK — no declared-side findings.")

    if config:
        return EXIT_FINDINGS
    if policy and enforcing:
        return EXIT_FINDINGS
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
