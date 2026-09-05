#!/usr/bin/env python3
"""Guard: every Temporal worker disable switch is named `openbank.<service>.worker.enabled`.

WHY THIS EXISTS: six services register a Temporal worker at `StartupEvent`, and every registrar
carries a disable switch so `@QuarkusTest` runs (and the API-fuzz harness) can boot the service
with no Temporal present. But the property name was invented per service:
`openbank.transaction.worker.enabled`, `openbank.sepa.worker.enabled`, `openbank.campaign…`,
`openbank.domestic…`, `openbank.settlement…` — and lending's `lending.origination.worker.enabled`,
not even under `openbank.` (since renamed; see below). The fuzz harness therefore could not
derive the switch from the service name and had to hard-code a per-service name list; a name
list drifts, and a drifted list means the service under test never booted and its fuzz job
reported a failure that was never a finding (2026-08-18 run: 6 of 7 "failures" never sent a
request).

One convention — `openbank.<service>.worker.enabled` — makes the switch derivable from the
artifact name everywhere it is needed: the fuzz harness, local dev, the DR manifest, runbooks.

WHAT IT CHECKS: every `@ConfigProperty(name = "…")` in `openbank-*/src/main/kotlin/**.kt` whose
name ends in `worker.enabled`. The name must match `^openbank\\.[a-z0-9-]+\\.worker\\.enabled$`.
A genuine exception lives in `rules.yaml: temporal_worker_switch_naming.allowlist` with a
one-line reason; the allowlist fails on a stale entry in either direction, so an exception
cannot quietly outlive its reason (same idiom as scheduled_methods.runblocking_allowlist).

The fleet's one pre-convention name — lending's `lending.origination.worker.enabled` — was
renamed to `openbank.lending.worker.enabled` in the same follow-up that introduced this guard;
the allowlist (`rules.yaml: temporal_worker_switch_naming.allowlist`) stays armed, and fails on
a stale entry in either direction, so the next exception must be named and justified rather than
absorbed.

ENFORCED: findings are ::error:: annotations and exit 1.

stdlib + PyYAML + gatelib (both already used by sibling gates in the same CI job).
Usage: check-temporal-worker-switch-naming.py [--root .] [--rules openbank-libs/governance/rules.yaml] [--self-test]
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

import yaml

import gatelib

CONFIG_PROPERTY_RE = re.compile(r'@(?:param:)?ConfigProperty\(name\s*=\s*"([^"]*worker\.enabled)"')
CANONICAL_RE = re.compile(r"^openbank\.[a-z0-9-]+\.worker\.enabled$")
ALLOWLIST_KEY = "temporal_worker_switch_naming"


def find_switches(root: pathlib.Path) -> dict[str, tuple[str, int]]:
    """All worker.enabled switch properties as {name: (path, line_no)}."""
    found: dict[str, tuple[str, int]] = {}
    for kt in gatelib.rglob(root, "openbank-*/src/main/**/*.kt"):
        for idx, line in enumerate(gatelib.read_text(kt).splitlines(), start=1):
            m = CONFIG_PROPERTY_RE.search(line)
            if m:
                found[m.group(1)] = (kt.relative_to(root).as_posix(), idx)
    return found


def load_allowlist(rules_path: pathlib.Path) -> dict[str, str]:
    data = yaml.safe_load(gatelib.read_text(rules_path)) or {}
    entries = (data.get(ALLOWLIST_KEY) or {}).get("allowlist") or []
    return {str(e["property"]): str(e.get("reason", "")) for e in entries if isinstance(e, dict)}


def self_test() -> int:
    fails: list[str] = []

    def case(label: str, name: str, want_canonical: bool) -> None:
        got = bool(CANONICAL_RE.match(name))
        if got != want_canonical:
            fails.append(f"{label}: {name!r} canonical={got}, want {want_canonical}")

    case("canonical", "openbank.transaction.worker.enabled", True)
    case("canonical with digit", "openbank.ap2.worker.enabled", True)
    case("the lending exception is NOT canonical", "lending.origination.worker.enabled", False)
    case("missing openbank prefix is NOT canonical", "campaign.worker.enabled", False)
    case("extra segment is NOT canonical", "openbank.lending.origination.worker.enabled", False)
    case("uppercase is NOT canonical", "openbank.SEPA.worker.enabled", False)

    m = CONFIG_PROPERTY_RE.search('@ConfigProperty(name = "openbank.sepa.worker.enabled", defaultValue = "true")')
    if not m or m.group(1) != "openbank.sepa.worker.enabled":
        fails.append("plain @ConfigProperty declaration not matched")
    m = CONFIG_PROPERTY_RE.search('@param:ConfigProperty(name = "lending.origination.worker.enabled", defaultValue = "true")')
    if not m or m.group(1) != "lending.origination.worker.enabled":
        fails.append("@param:ConfigProperty use-site form not matched")
    if CONFIG_PROPERTY_RE.search('@ConfigProperty(name = "openbank.worker.enabledness")'):
        fails.append("non-worker.enabled property falsely matched")

    if fails:
        for f in fails:
            print(f"SELF-TEST FAILURE: {f}")
        return 1
    print("self-test OK (9 assertions)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--rules", default="openbank-libs/governance/rules.yaml")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root)
    found = find_switches(root)
    allowlist = load_allowlist(root / args.rules)

    gatelib.subjects(len(found), "worker.enabled switch properties")

    rc = 0
    for name, (path, line_no) in sorted(found.items()):
        if CANONICAL_RE.match(name):
            continue
        if name in allowlist:
            print(f"::notice::{name} ({path}:{line_no}) is non-canonical but allowlisted: "
                  f"{allowlist[name]}")
            continue
        print(f"::error file={path},line={line_no}::Temporal worker disable switch {name!r} does "
              f"not follow the convention `openbank.<service>.worker.enabled` — an underivable "
              f"name forces every consumer (fuzz harness, DR manifests, runbooks) to hard-code a "
              f"drifting name list. Rename it, or allowlist it in rules.yaml: "
              f"{ALLOWLIST_KEY}.allowlist with the reason.")
        rc = 1
    for name in sorted(set(allowlist) - set(found)):
        print(f"::error::stale allowlist entry {name!r} in rules.yaml: {ALLOWLIST_KEY}.allowlist "
              f"— no such @ConfigProperty exists; remove the entry in the same PR.")
        rc = 1

    if rc == 0:
        canonical = sum(1 for n in found if CANONICAL_RE.match(n))
        print(f"OK: {len(found)} worker.enabled switches, {canonical} canonical, "
              f"{len(found) - canonical} allowlisted.")
    return rc


if __name__ == "__main__":
    sys.exit(main())
