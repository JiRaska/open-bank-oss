#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors.
#
# CI gate for flag-as-code (ADR-0067 / issue #419).
# Finds every *.flagd.json file under the repo, parses the `openbank` metadata
# block in each flag, and enforces the same rules as FlagDefinition.validate():
#
#   1. key             — kebab-case, non-blank
#   2. owner           — non-blank (no orphan flags)
#   3. MONEY_PATH      — fourEyes must be true
#   4. expiresAt       — must be in the future (stale-flag GC, ADR-0067 §7)
#   5. prohibited keys — keys in rules.yaml:feature_flags.prohibited_flag_combinations
#                        must never appear (hardened safety controls cannot be disabled
#                        via a flag — see OPA rest.rego prohibited rule)
#
# Exit 0 = all flags valid. Exit 1 = violations found (CI gate fails).

import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

# ── Authoritative constants (mirrors openbank-libs/governance/rules.yaml) ────
PROHIBITED_KEYS = {
    "sca-enforcement-disabled",
    "sanctions-screening-disabled",
    "aml-screening-disabled",
    "payment-gate-fail-open",
}
KEBAB_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")

# ── Helpers ───────────────────────────────────────────────────────────────────

def find_flagd_files(root: Path) -> list[Path]:
    return [
        p for p in root.rglob("*.flagd.json")
        if not any(part.startswith(".") for part in p.parts)
        and "node_modules" not in p.parts
        and "build" not in p.parts
    ]


def validate_flag(key: str, flag_obj: dict, source: str) -> list[str]:
    """
    Validate a single flag. Returns a list of violation strings (empty = OK).
    """
    now = datetime.now(timezone.utc)
    meta = flag_obj.get("openbank", {})
    violations = []

    # 1. key kebab-case
    if not KEBAB_RE.match(key):
        violations.append(f"  [{source}] flag '{key}': key must be non-blank kebab-case")

    # 2. prohibited keys — must never appear in the repo
    if key in PROHIBITED_KEYS:
        violations.append(
            f"  [{source}] flag '{key}': key is in prohibited_flag_combinations "
            f"(ADR-0067/OPA rest.rego) — this flag may never exist"
        )

    if not meta:
        # No openbank block — warn but don't hard-fail (not every flagd file may
        # be an OpenBank managed file; external sidecars may share the suffix).
        # CI only enforces on files that carry at least one openbank block.
        return violations

    # 3. owner non-blank
    owner = meta.get("owner", "")
    if not owner or not owner.strip():
        violations.append(f"  [{source}] flag '{key}': owner is blank (no orphan flags)")

    # 4. MONEY_PATH → fourEyes required
    classification = meta.get("classification", "")
    four_eyes = meta.get("fourEyes", False)
    if classification == "MONEY_PATH" and not four_eyes:
        violations.append(
            f"  [{source}] flag '{key}': classification=MONEY_PATH but fourEyes={four_eyes} "
            f"(must be true — ADR-0023/0034)"
        )

    # 5. expiresAt must exist and be in the future
    expires_str = meta.get("expiresAt")
    if expires_str:
        try:
            expires = datetime.fromisoformat(expires_str.replace("Z", "+00:00"))
            if expires <= now:
                violations.append(
                    f"  [{source}] flag '{key}': expired at {expires_str} "
                    f"— remove it or extend (ADR-0067 §7)"
                )
        except ValueError:
            violations.append(
                f"  [{source}] flag '{key}': expiresAt='{expires_str}' is not a valid ISO-8601 datetime"
            )
    else:
        violations.append(
            f"  [{source}] flag '{key}': expiresAt is missing "
            f"— every flag must have a review/retirement date (ADR-0067 §7)"
        )

    return violations


def validate_file(path: Path) -> tuple[int, int, list[str]]:
    """Returns (flags_checked, violations_count, violation_messages)."""
    try:
        data = json.loads(path.read_text())
    except json.JSONDecodeError as e:
        return 0, 1, [f"  [{path}] invalid JSON: {e}"]

    flags = data.get("flags", {})
    if not flags:
        return 0, 0, []

    all_violations: list[str] = []
    # Only validate files that have at least one `openbank` metadata block.
    has_openbank = any("openbank" in v for v in flags.values())
    if not has_openbank:
        return 0, 0, []

    for key, flag_obj in flags.items():
        all_violations.extend(validate_flag(key, flag_obj, str(path)))

    return len(flags), len(all_violations), all_violations


# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> int:
    repo_root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    files = find_flagd_files(repo_root)

    total_flags = 0
    total_violations = 0
    all_messages: list[str] = []

    for f in sorted(files):
        checked, violations, messages = validate_file(f)
        total_flags += checked
        total_violations += violations
        all_messages.extend(messages)

    if not files:
        print("validate-flags: no *.flagd.json files found — skipping.")
        return 0

    print(f"validate-flags: checked {total_flags} flags in {len(files)} file(s).")

    if total_violations:
        print(f"\n❌ {total_violations} violation(s):\n")
        for msg in all_messages:
            print(msg)
        print(
            "\nFix the violations above. See ADR-0067, "
            "openbank-libs/governance/rules.yaml:feature_flags, "
            "and libs/flags FlagDefinition.kt for the rules."
        )
        return 1

    print("✅ All flags valid.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
