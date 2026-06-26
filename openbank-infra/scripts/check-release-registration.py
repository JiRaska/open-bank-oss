#!/usr/bin/env python3
"""Release-registration consistency gate (CLAUDE.md rule #3, ADR-0029).

Enforces the invariant: a module is a released component **iff it has a
`version.txt`**, and every released component is registered in BOTH
`release-please-config.json` (`packages`) and `.release-please-manifest.json`.

This catches the drift where a service ships with a `version.txt` (added by the
feat PR that deployed it) but is never registered with release-please — so it
silently never gets a Release PR, changelog, or tag. Two services
(customer-edge, security-scanner) had drifted this way before this gate existed.

stdlib only — runs in PR CI with no extra deps, like the sibling governance
gates (check-threat-models.py, check-version-lifecycle.py).
"""
from __future__ import annotations

import json
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
CONFIG = REPO / "release-please-config.json"
MANIFEST = REPO / ".release-please-manifest.json"


def modules_with_version_txt() -> set[str]:
    """Every top-level `openbank-*` module that carries a `version.txt`.

    `version.txt` presence is the rule-#3 definition of a released component, so
    it is the source of truth this gate compares the release config against.
    """
    return {p.parent.name for p in REPO.glob("openbank-*/version.txt")}


def registered_packages() -> set[str]:
    data = json.loads(CONFIG.read_text(encoding="utf-8"))
    return set(data.get("packages", {}).keys())


def manifest_keys() -> set[str]:
    data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    return set(data.keys())


def main() -> int:
    have_version = modules_with_version_txt()
    in_config = registered_packages()
    in_manifest = manifest_keys()

    violations: list[str] = []

    # A component with version.txt must be registered in both files.
    for missing in sorted(have_version - in_config):
        violations.append(
            f"{missing} has version.txt but is NOT in release-please-config.json `packages` "
            f"— it will never get a Release PR/changelog/tag. Add: "
            f'"{missing}": {{ "component": "{missing.removeprefix("openbank-")}" }}'
        )
    for missing in sorted(have_version - in_manifest):
        violations.append(
            f"{missing} has version.txt but is NOT in .release-please-manifest.json "
            f"— add it with its current version.txt value as the baseline."
        )

    # A registered component must actually have a version.txt (no phantom entries).
    for orphan in sorted(in_config - have_version):
        violations.append(
            f"{orphan} is in release-please-config.json `packages` but has no version.txt "
            f"— either add version.txt or remove the package entry."
        )
    for orphan in sorted(in_manifest - have_version):
        violations.append(
            f"{orphan} is in .release-please-manifest.json but has no version.txt "
            f"— either add version.txt or remove the manifest entry."
        )

    # config and manifest must cover exactly the same components.
    for diff in sorted(in_config ^ in_manifest):
        side = "config but not manifest" if diff in in_config else "manifest but not config"
        violations.append(f"{diff} is in release-please {side} — the two must stay in lockstep.")

    print("Release-registration consistency gate (rule #3, ADR-0029)")
    print(f"  modules with version.txt: {len(have_version)}")
    print(f"  config packages:          {len(in_config)}")
    print(f"  manifest entries:         {len(in_manifest)}")
    if violations:
        print("  VIOLATIONS:")
        for v in violations:
            print(f"    - {v}")
        return 1
    print("  OK: every released component is registered in config + manifest, and vice versa.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
