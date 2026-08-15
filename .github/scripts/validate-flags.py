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

def self_test() -> int:
    """Falsify the per-flag validator and the file walker.

    A feature flag is a runtime switch over money-path behaviour, so the failures here are
    governance ones and every single one is silent at runtime: an orphan flag nobody owns
    outlives the person who added it, a MONEY_PATH flag without four-eyes can be flipped by
    one person, and a flag with no expiry is permanent by omission — none of which any test
    or deploy notices, because the flag WORKS.

    The prohibited set is the sharpest case: `sanctions-screening-disabled` existing at all is
    the finding, whatever its value, because a flag that can be flipped is a control that can
    be turned off.
    """
    import tempfile, json as _json
    from datetime import timedelta

    fails: list[str] = []

    def case(label, key, obj, want_hit, want_sub=""):
        v = validate_flag(key, obj, "fixture")
        got = bool(v)
        if got != want_hit:
            fails.append(f"{label}: expected violation={want_hit}, got {v}")
        elif want_sub and not any(want_sub in x for x in v):
            fails.append(f"{label}: flagged for the wrong reason — no {want_sub!r} in {v}")

    future = (datetime.now(timezone.utc) + timedelta(days=30)).isoformat()
    past = (datetime.now(timezone.utc) - timedelta(days=1)).isoformat()
    ok_meta = {"owner": "payments-team", "classification": "STANDARD", "expiresAt": future}

    # The only fully clean shape.
    case("a well-formed flag is clean", "my-flag", {"openbank": ok_meta}, False)

    # PROHIBITED: existence is the finding, whatever the value or metadata. A flag that can be
    # flipped is a control that can be turned off.
    case("a prohibited key is flagged even when otherwise perfect",
         "sanctions-screening-disabled", {"openbank": ok_meta}, True, "prohibited")
    # ...and even with no openbank block at all, because the early return must come AFTER the
    # prohibited check — otherwise adding the flag without metadata evades the ban entirely.
    case("a prohibited key with no metadata is still flagged",
         "aml-screening-disabled", {}, True, "prohibited")

    # Naming, so the key is addressable by the OPA policy that reads it.
    case("a non-kebab key is flagged", "My_Flag", {"openbank": ok_meta}, True, "kebab-case")
    case("a blank key is flagged", "", {"openbank": ok_meta}, True, "kebab-case")

    # Ownership: an orphan flag outlives whoever added it.
    case("a blank owner is flagged", "my-flag",
         {"openbank": {**ok_meta, "owner": "   "}}, True, "owner is blank")

    # MONEY_PATH without four-eyes: one person can flip a money-path behaviour.
    case("MONEY_PATH without fourEyes is flagged", "my-flag",
         {"openbank": {**ok_meta, "classification": "MONEY_PATH"}}, True, "fourEyes")
    case("MONEY_PATH with fourEyes is clean", "my-flag",
         {"openbank": {**ok_meta, "classification": "MONEY_PATH", "fourEyes": True}}, False)

    # Expiry: missing means permanent by omission; expired means overdue; unparseable must not
    # be swallowed as "no expiry set".
    case("a missing expiresAt is flagged", "my-flag",
         {"openbank": {"owner": "x", "classification": "STANDARD"}}, True, "expiresAt is missing")
    case("an expired flag is flagged", "my-flag",
         {"openbank": {**ok_meta, "expiresAt": past}}, True, "expired at")
    case("an unparseable expiresAt is flagged", "my-flag",
         {"openbank": {**ok_meta, "expiresAt": "soon"}}, True, "not a valid ISO-8601")

    # A flag with no openbank block is out of scope — third-party flagd files exist and
    # demanding our metadata of them would make the gate unusable.
    case("a flag with no openbank block is out of scope", "my-flag", {"state": "ENABLED"}, False)

    # --- the file walker ---------------------------------------------------------------
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        (root / "a").mkdir()
        good = root / "a" / "x.flagd.json"
        good.write_text(_json.dumps({"flags": {"my-flag": {"openbank": ok_meta}}}))
        # Directories a scan must never descend into: build output is a COPY, so every finding
        # would be reported twice, and a hidden dir is not source.
        for skip in ("build", ".git", "node_modules"):
            d = root / skip; d.mkdir()
            (d / "y.flagd.json").write_text(_json.dumps({"flags": {}}))
        found = {p.name for p in find_flagd_files(root)}
        if found != {"x.flagd.json"}:
            fails.append(f"the walker descended where it should not: {sorted(found)}")

        # Malformed JSON must be a VIOLATION, not a skip: a file that cannot be parsed is a
        # file whose flags were never checked, and silence about it reads as compliance.
        bad = root / "a" / "bad.flagd.json"
        bad.write_text("{ not json")
        checked, count, msgs = validate_file(bad)
        if count != 1 or not any("invalid JSON" in m for m in msgs):
            fails.append(f"malformed JSON was not reported as a violation: {msgs}")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: feature-flag governance is falsifiable (16 cases)")
    return 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()

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
