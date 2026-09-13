#!/usr/bin/env python3
"""Derive `.release-please-manifest.json` and `release-please-config.json` from the version.txt files.

WHY THIS EXISTS
    ADR-0029 rule 2 makes a module a released component IFF it has a `version.txt` AND appears in
    both registries. The three are required to stay in lockstep, and
    `check-release-registration.py` enforces that — but enforcement only tells you the invariant
    BROKE. Repairing it meant hand-editing two JSON maps, and a hand-edit is how it breaks.

    Measured 2026-09-13: two open branches had each silently LOST a registered component to a
    clean merge. Both sides held 63 entries, so nothing looked missing; git had taken one side of
    two adjacent key insertions, exited 0 and printed nothing. Only the consistency gate saw it.
    The same day, five sibling PRs all conflicted on this pair. Left alone, the result is a service
    with a `version.txt` on main that release-please does not know, and its first release breaks.

    So: stop hand-editing. The manifest is 100% derivable — measured, 64/64 entries equal to their
    module's `version.txt`, no orphans in either direction — and 61 of 64 config packages have one
    mechanical shape. Resolving a conflict becomes `--write`, which cannot drop a key, instead of
    merging JSON by hand.

    This does NOT make the collision impossible: a branch still commits the regenerated files, so
    two branches adding two services still edit the same map. What it removes is the chance that
    the REPAIR loses something, and it lets `derived-artefact-autoheal` fix a drift on main without
    a human deciding what the file should contain.

WHAT IS NOT DERIVED
    Three packages keep hand-written shapes, declared in OVERRIDES with the reason. Two of them are
    a defect this script found rather than a design (see the OVERRIDES comment) and are tracked
    separately — encoding them here keeps the generator honest about the tree it actually has,
    rather than rewriting two services' release identity as a side effect of an automation change.

Usage:
    gen-release-registry.py            # --check
    gen-release-registry.py --write
    gen-release-registry.py --self-test
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
MANIFEST = REPO / ".release-please-manifest.json"
CONFIG = REPO / "release-please-config.json"

# Packages whose config entry is NOT the mechanical shape. Each needs a reason; an entry for a
# module that no longer exists is a failure, so the map cannot quietly outlive its subject.
#
# `openbank-campaign-service` and `openbank-tax-reporting-service` are NOT a considered exception —
# they are a defect this script surfaced. They carry `{"release-type": "simple"}` with no
# `component` and no `exclude-paths`, which means (a) their tag has no component prefix, so both
# share the repo-root `v<version>` namespace with each other, and neither has ever produced a tag
# despite sitting at 0.39.0 and 0.22.0; and (b) with no `exclude-paths`, a change under their own
# `src/test` releases them, which is the ADR-0029 rule-2 path axis inverted. Fixing that changes
# two services' release identity, so it is filed as #9900 rather than done here as a side effect.
OVERRIDES: dict[str, dict] = {
    "openbank-admin-ui": {
        "component": "admin-ui",
        "extra-files": [{"type": "json", "path": "package.json", "jsonpath": "$.version"}],
        "exclude-paths": ["openbank-admin-ui/src/test", "openbank-admin-ui/e2e"],
    },
    "openbank-campaign-service": {"release-type": "simple"},
    "openbank-tax-reporting-service": {"release-type": "simple"},
}


def released_modules() -> list[str]:
    """Every directory holding a version.txt — the ADR-0029 definition of a released component."""
    return sorted(p.parent.name for p in REPO.glob("*/version.txt"))


def _ordered(existing: list[str], wanted: list[str]) -> list[str]:
    """Existing keys in their CURRENT order, then genuinely new ones appended.

    Deliberately not sorted. Neither live file is sorted and the two do not even share an order,
    so sorting would rewrite all 64 entries — a 439-line diff that would conflict with every open
    PR touching these files, which is the problem this script exists to reduce. Stability of the
    rendering matters more than its tidiness: a new component appends one block.
    """
    keep = [k for k in existing if k in set(wanted)]
    return keep + [k for k in wanted if k not in set(keep)]


def derive() -> tuple[dict, dict]:
    mods = released_modules()

    live_man = json.loads(MANIFEST.read_text(encoding="utf-8"))
    config = json.loads(CONFIG.read_text(encoding="utf-8"))
    live_pkg = config.get("packages", {})

    manifest = {
        m: (REPO / m / "version.txt").read_text(encoding="utf-8").strip()
        for m in _ordered(list(live_man), mods)
    }

    packages: dict[str, dict] = {}
    for m in _ordered(list(live_pkg), mods):
        packages[m] = OVERRIDES[m] if m in OVERRIDES else {
            "component": m.removeprefix("openbank-"),
            "exclude-paths": [f"{m}/src/test"],
        }

    config["packages"] = packages
    return manifest, config


def render(manifest: dict, config: dict) -> tuple[str, str]:
    return (
        json.dumps(manifest, indent=2) + "\n",
        json.dumps(config, indent=2) + "\n",
    )


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--write", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return _self_test()

    manifest, config = derive()
    man_txt, cfg_txt = render(manifest, config)
    print(f"SUBJECTS={len(manifest)}  # released components (modules with a version.txt)")

    if args.write:
        MANIFEST.write_text(man_txt, encoding="utf-8")
        CONFIG.write_text(cfg_txt, encoding="utf-8")
        print(f"wrote {MANIFEST.name} and {CONFIG.name} ({len(manifest)} components)")
        return 0

    drift = []
    if MANIFEST.read_text(encoding="utf-8") != man_txt:
        drift.append(MANIFEST.name)
    if CONFIG.read_text(encoding="utf-8") != cfg_txt:
        drift.append(CONFIG.name)
    if drift:
        print(f"::error::{', '.join(drift)} is stale — run "
              f"`python3 .github/scripts/gen-release-registry.py --write` and commit.")
        for name, want in ((MANIFEST, man_txt), (CONFIG, cfg_txt)):
            have = name.read_text(encoding="utf-8")
            if have == want:
                continue
            h, w = json.loads(have), json.loads(want)
            hk = set(h if name is MANIFEST else h["packages"])
            wk = set(w if name is MANIFEST else w["packages"])
            if hk - wk:
                print(f"::error::{name.name}: registered but has no version.txt: {sorted(hk - wk)}")
            if wk - hk:
                print(f"::error::{name.name}: has a version.txt but is not registered: {sorted(wk - hk)}")
        return 1
    print("release registry: in sync with the version.txt files.")
    return 0


def _self_test() -> int:
    """Prove the check can FAIL. A generator whose only demonstrated behaviour is agreeing with the
    tree it was derived from has not been shown to detect anything."""
    failures: list[str] = []
    manifest, config = derive()

    def expect(label: str, got, want) -> None:
        ok = got == want
        print(f"  [{'ok ' if ok else 'FAIL'}] {label}: got {got!r}")
        if not ok:
            failures.append(label)

    expect("every module with a version.txt is in the derived manifest",
           sorted(manifest) == released_modules(), True)
    expect("manifest and config cover the same components",
           sorted(manifest) == sorted(config["packages"]), True)
    expect("a value equals its module's version.txt",
           all((REPO / m / "version.txt").read_text().strip() == v for m, v in manifest.items()), True)

    # must DETECT: a dropped key, which is the exact failure this exists for
    dropped = dict(manifest)
    victim = sorted(dropped)[0]
    del dropped[victim]
    expect(f"a manifest missing {victim} differs from the derived one",
           render(dropped, config)[0] != render(manifest, config)[0], True)

    # must DETECT: a value that drifted from version.txt
    bumped = dict(manifest)
    bumped[victim] = "99.99.99"
    expect("a manifest value that drifted from version.txt differs",
           render(bumped, config)[0] != render(manifest, config)[0], True)

    # must PRESERVE: the three hand-shaped packages
    for name in OVERRIDES:
        expect(f"{name} keeps its hand-written shape",
               config["packages"].get(name), OVERRIDES[name])
    expect("every OVERRIDES entry still names a released module",
           sorted(OVERRIDES) == sorted(n for n in OVERRIDES if n in manifest), True)

    if failures:
        print(f"gen-release-registry self-test: FAIL ({len(failures)})")
        return 1
    print("gen-release-registry self-test: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
