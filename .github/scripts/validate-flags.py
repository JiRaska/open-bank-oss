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
#
# WARN MODE (--warn-days N, issue #7941). Rule 4 compares against NOW, so the first
# signal a flag has a retirement date is the day it expires and turns every PR red
# regardless of its diff — which is how `sepa-instant-new-router` reached
# 2026-09-01T00:00:00Z unnoticed and froze a ~80-PR queue (#7897/#7919).
# `--warn-days N` lists every flag whose expiresAt falls inside the next N days and
# ALWAYS exits 0, including for flags already expired: a horizon report, never a
# second gate. The enforcing path is untouched.

import json
import re
import sys
from datetime import datetime, timedelta, timezone
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


def _parses(path: Path) -> bool:
    try:
        json.loads(path.read_text())
        return True
    except json.JSONDecodeError:
        return False


def horizon_findings(files: list[Path], warn_days: int) -> list[tuple[str, str, str, int]]:
    """Flags whose expiresAt falls on or before now+warn_days.

    Returns (source, key, expiresAt, days_left); days_left is negative for a flag that
    has already expired. Those are included on purpose — the enforcing lane reports them
    too, but a horizon report that silently omits the overdue ones is narrower than the
    estate it claims to cover, and reads as "nothing due".

    Deliberately independent of validate_flag(): a malformed or missing expiresAt is a
    VIOLATION, not a horizon finding, and must not be smuggled into a report that always
    exits 0.
    """
    now = datetime.now(timezone.utc)
    cutoff = now + timedelta(days=warn_days)
    out: list[tuple[str, str, str, int]] = []
    for path in sorted(files):
        try:
            data = json.loads(path.read_text())
        except json.JSONDecodeError:
            continue  # the enforcing lane reports this; a warner must not fail on it
        for key, flag_obj in data.get("flags", {}).items():
            expires_str = (flag_obj.get("openbank") or {}).get("expiresAt")
            if not expires_str:
                continue
            try:
                expires = datetime.fromisoformat(expires_str.replace("Z", "+00:00"))
            except ValueError:
                continue
            if expires <= cutoff:
                out.append((str(path), key, expires_str, (expires - now).days))
    return out


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

    # --- warn mode (#7941) -------------------------------------------------------------
    # A warner is worth nothing unless it can be shown to DISCRIMINATE: the same flag must
    # be reported under one window and silent under another. Without that, "0 due" and
    # "the scan walked nothing" are the same output — the shape that let
    # sepa-instant-new-router reach its own expiry with nobody noticing.
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        soon = (datetime.now(timezone.utc) + timedelta(days=10)).isoformat()
        far = (datetime.now(timezone.utc) + timedelta(days=200)).isoformat()
        gone = (datetime.now(timezone.utc) - timedelta(days=5)).isoformat()
        (root / "w.flagd.json").write_text(_json.dumps({"flags": {
            "soon-flag": {"openbank": {**ok_meta, "expiresAt": soon}},
            "far-flag": {"openbank": {**ok_meta, "expiresAt": far}},
        }}))
        files = find_flagd_files(root)

        keys = {k for _, k, _, _ in horizon_findings(files, 30)}
        if keys != {"soon-flag"}:
            fails.append(f"a 30d window must name exactly soon-flag, got {sorted(keys)}")
        # The SAME flag, a narrower window: silent. This is the half that proves the
        # window is real rather than a scan reporting everything it sees.
        if horizon_findings(files, 3):
            fails.append("a 3d window must report nothing, got {}".format(horizon_findings(files, 3)))
        # Wide enough and BOTH appear — so "nothing found" was never a broken walker.
        if len(horizon_findings(files, 365)) != 2:
            fails.append("a 365d window must name both flags")
        # The degenerate window: no future date can be inside it.
        if horizon_findings(files, 0):
            fails.append("--warn-days 0 must report nothing for future dates")

        # An ALREADY-EXPIRED flag is a horizon finding with a negative days_left, not an
        # omission — the enforcing lane fails on it, and a report that hides it reads clean.
        (root / "x.flagd.json").write_text(_json.dumps({"flags": {
            "gone-flag": {"openbank": {**ok_meta, "expiresAt": gone}}}}))
        exp = [f for f in horizon_findings(find_flagd_files(root), 0) if f[1] == "gone-flag"]
        if not exp or exp[0][3] >= 0:
            fails.append(f"an expired flag must be reported with a negative days_left: {exp}")

        # A malformed or absent expiresAt is a VIOLATION, never a horizon finding — it must
        # not be smuggled into a lane that always exits 0.
        (root / "y.flagd.json").write_text(_json.dumps({"flags": {
            "bad-flag": {"openbank": {**ok_meta, "expiresAt": "soon"}},
            "no-date-flag": {"openbank": {"owner": "x", "classification": "STANDARD"}}}}))
        noisy = {k for _, k, _, _ in horizon_findings(find_flagd_files(root), 3650)}
        if noisy & {"bad-flag", "no-date-flag"}:
            fails.append(f"warn mode must not report unparseable/missing expiresAt: {sorted(noisy)}")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: feature-flag governance is falsifiable (22 cases)")
    return 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()

    argv = sys.argv[1:]
    warn_days = None
    positional: list[str] = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--warn-days":
            if i + 1 >= len(argv):
                print("validate-flags: --warn-days needs a value", file=sys.stderr)
                return 1
            warn_days, i = argv[i + 1], i + 2
        elif a.startswith("--warn-days="):
            warn_days, i = a.split("=", 1)[1], i + 1
        else:
            positional.append(a)
            i += 1
    if warn_days is not None:
        if not warn_days.isdigit():
            print(f"validate-flags: --warn-days needs a non-negative integer, got {warn_days!r}",
                  file=sys.stderr)
            return 1
        warn_days = int(warn_days)

    repo_root = Path(positional[0]) if positional else Path(".")
    files = find_flagd_files(repo_root)

    if warn_days is not None:
        if not files:
            print("validate-flags --warn-days: no *.flagd.json files found — skipping.")
            return 0
        total = sum(len(json.loads(f.read_text()).get("flags", {}))
                    for f in files if _parses(f))
        findings = horizon_findings(files, warn_days)
        for source, key, expires, days_left in findings:
            when = "ALREADY EXPIRED" if days_left < 0 else f"due in {days_left}d"
            print(f"  {source}  {key}  {expires}  {when}")
        # Both numbers, always: "0 due" alone cannot be told apart from a scan that
        # walked nothing, which is the failure this warner exists to prevent.
        print(f"validate-flags --warn-days {warn_days}: {len(findings)} of {total} "
              f"flag(s) expiring within {warn_days} day(s).")
        return 0

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
