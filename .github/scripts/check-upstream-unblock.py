#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Upstream-unblock watch: turn a hand-polled "blocked on the ecosystem" issue into a
# self-clearing one (issue #1314).
#
# WHY THIS EXISTS
#   `.github/dependabot.yml` carries `ignore:` entries for majors the ecosystem cannot
#   satisfy yet (eslint 10, typescript 7). Each entry's comment says "drop it once the
#   criterion in #1314 is met" — but nothing ever evaluates that criterion. The only
#   mechanism was a human re-running two `npm view` commands, and the issue's own comment
#   history shows exactly what that produces: three separate triage passes across ten days,
#   each re-deriving the same "still blocked", each costing a full investigation.
#
#   The failure mode is not that the block is wrong. It is that the block has no expiry:
#   the day the peer range DOES admit eslint 10, nothing notices, and the ignore entry
#   silently becomes a permanent unexplained pin. A block nobody re-checks is
#   indistinguishable from a decision.
#
# WHAT IT CHECKS
#   1. DRIFT, both directions. Every npm `ignore:` entry in dependabot.yml must be declared
#      in WATCHES below, and every WATCHES entry must correspond to a live ignore entry.
#      This is the repo's derived-scope rule (never let a gate's coverage set be maintained
#      separately from the artifacts it covers): an ignore entry added without a watch
#      would otherwise be a block this script reports nothing about, while still passing.
#   2. LIVENESS. For each watch that names an upstream criterion, resolve the published
#      peer range from the npm registry and test whether it now admits the blocked major.
#      Satisfied => the block is stale => report it, loudly.
#
#   A watch may legitimately have NO upstream criterion (`upstream: None`) — tailwindcss v4
#   is blocked on a migration in THIS repo, not on anyone else. Those are declared, not
#   omitted, so the drift check stays exact.
#
# EXIT CODES
#   0  every block still justified, no drift
#   1  at least one block is now stale (or drift) — the actionable state
#   2  the probe itself could not run (network, registry shape change)
#
#   Note the separation of 1 and 2. A registry fetch that fails must NEVER be reported as
#   "still blocked" — that is the shape where a broken probe's silence reads as evidence of
#   absence. `--self-test` exercises the range matcher against inputs it MUST accept and
#   inputs it MUST reject, so the matcher is not trusted purely on having never fired.
#
# Run:  python3 .github/scripts/check-upstream-unblock.py [--root .] [--self-test]

import argparse
import json
import pathlib
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

REGISTRY = "https://registry.npmjs.org"

# Keyed by the dependabot `dependency-name` being ignored.
WATCHES = {
    "eslint": {
        "issue": 1314,
        # eslint 10 removed context.getFilename(); the eslint-plugin-react that
        # eslint-config-next bundles still calls it and peers on eslint <= 9.
        "upstream": {
            "package": "eslint-plugin-react",
            "peer": "eslint",
            "probe": "10.0.0",
        },
    },
    "typescript": {
        "issue": 1314,
        # typescript-eslint's parser does not support the TypeScript 7 compiler API.
        "upstream": {
            "package": "@typescript-eslint/parser",
            "peer": "typescript",
            "probe": "7.0.0",
        },
    },
    "tailwindcss": {
        "issue": 1653,
        # Blocked on a deliberate migration in THIS repo (CSS-first @theme, PostCSS plugin
        # rename, darkMode syntax), not on any upstream peer range. Declared with no
        # upstream criterion so the drift check above stays exact — there is nothing to
        # poll, and pretending otherwise would manufacture a signal.
        "upstream": None,
    },
}

# --------------------------------------------------------------------------------------
# Minimal semver-range satisfaction, covering the subset npm peerDependencies actually use:
# `||`-separated alternatives, each a space-separated AND-set of `^X[.Y[.Z]]`, `~X.Y.Z`,
# `>=`/`>`/`<=`/`<`/`=` comparators, a bare version, or `*`. Deliberately NOT a full semver
# implementation — prereleases and hyphen ranges are not used by the peers we watch, and a
# range this script cannot parse raises rather than quietly evaluating to False.
# --------------------------------------------------------------------------------------

_NUM = re.compile(r"^\d+$")


def _parse_version(text):
    core = text.strip().split("-", 1)[0].split("+", 1)[0]
    parts = core.split(".")
    out = []
    for p in parts[:3]:
        if not _NUM.match(p):
            raise ValueError(f"unparseable version component {p!r} in {text!r}")
        out.append(int(p))
    while len(out) < 3:
        out.append(0)
    return tuple(out)


def _parse_partial(text):
    """Parse a possibly-partial version like `9`, `9.7`, `9.7.1` -> (tuple, specificity)."""
    core = text.strip().split("-", 1)[0].split("+", 1)[0]
    parts = [p for p in core.split(".") if p != ""]
    nums = []
    for p in parts[:3]:
        if p in ("x", "X", "*"):
            break
        if not _NUM.match(p):
            raise ValueError(f"unparseable version component {p!r} in {text!r}")
        nums.append(int(p))
    spec = len(nums)
    while len(nums) < 3:
        nums.append(0)
    return tuple(nums), spec


def _caret_bounds(base, spec):
    """Upper bound (exclusive) for ^base, per npm's caret semantics."""
    major, minor, _patch = base
    if major > 0:
        return (major + 1, 0, 0)
    if minor > 0 or spec >= 2:
        return (0, minor + 1, 0)
    return (1, 0, 0)


def _comparator_ok(cmp_text, version):
    cmp_text = cmp_text.strip()
    if cmp_text in ("", "*", "x", "X"):
        return True
    if cmp_text.startswith("^"):
        base, spec = _parse_partial(cmp_text[1:])
        return base <= version < _caret_bounds(base, spec)
    if cmp_text.startswith("~"):
        base, spec = _parse_partial(cmp_text[1:])
        upper = (base[0], base[1] + 1, 0) if spec >= 2 else (base[0] + 1, 0, 0)
        return base <= version < upper
    for op in (">=", "<=", ">", "<", "="):
        if cmp_text.startswith(op):
            base, _spec = _parse_partial(cmp_text[len(op):])
            if op == ">=":
                return version >= base
            if op == "<=":
                return version <= base
            if op == ">":
                return version > base
            if op == "<":
                return version < base
            return version == base
    base, spec = _parse_partial(cmp_text)
    if spec == 3:
        return version == base
    # A bare partial (`9`, `9.7`) means the same set as `^`-less x-range.
    upper = (base[0] + 1, 0, 0) if spec <= 1 else (base[0], base[1] + 1, 0)
    return base <= version < upper


def satisfies(version_text, range_text):
    """True iff `version_text` is admitted by npm range `range_text`."""
    version = _parse_version(version_text)
    for alternative in range_text.split("||"):
        clauses = [c for c in alternative.strip().split() if c]
        if not clauses:
            continue
        if all(_comparator_ok(c, version) for c in clauses):
            return True
    return False


# --------------------------------------------------------------------------------------


def npm_ignore_entries(root):
    """dependency-name -> the raw `versions` list, for the npm ecosystem in dependabot.yml."""
    try:
        import yaml
    except ImportError:
        sys.stderr.write("PyYAML required: pip install pyyaml\n")
        sys.exit(2)
    path = root / ".github" / "dependabot.yml"
    doc = yaml.safe_load(path.read_text(encoding="utf-8"))
    found = {}
    for update in doc.get("updates", []) or []:
        if update.get("package-ecosystem") != "npm":
            continue
        for entry in update.get("ignore", []) or []:
            name = entry.get("dependency-name")
            if name:
                found[name] = entry.get("versions", [])
    return found


def fetch_peer_range(package, peer):
    url = f"{REGISTRY}/{urllib.parse.quote(package, safe='@')}/latest"
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as resp:  # noqa: S310 - fixed https host
        payload = json.load(resp)
    peers = payload.get("peerDependencies") or {}
    if peer not in peers:
        raise KeyError(
            f"{package}@{payload.get('version')} declares no peerDependencies[{peer!r}] — "
            "the criterion this watch is written against no longer exists upstream; "
            "re-derive it by hand rather than trusting this probe."
        )
    return payload.get("version"), peers[peer]


def self_test():
    """The matcher must accept what it should and REJECT what it should not.

    Written because a range matcher that has only ever returned False is
    indistinguishable from one that always returns False — which would report every
    block as permanently justified, forever, with a green exit code.
    """
    cases = [
        # (version, range, expected)
        ("10.0.0", "^3 || ^4 || ^5 || ^6 || ^7 || ^8 || ^9.7", False),  # today's real range
        ("10.0.0", "^3 || ^9.7 || ^10.0.0", True),  # the unblock we are waiting for
        ("9.7.1", "^3 || ^4 || ^5 || ^6 || ^7 || ^8 || ^9.7", True),
        ("9.6.0", "^9.7", False),
        ("7.0.0", ">=4.8.4 <6.1.0", False),  # today's real range
        ("7.0.0", ">=4.8.4 <8.0.0", True),  # the unblock we are waiting for
        ("5.9.0", ">=4.8.4 <6.1.0", True),
        ("10.0.0", "^8.57.0 || ^9.0.0 || ^10.0.0", True),
        ("11.0.0", "^8.57.0 || ^9.0.0 || ^10.0.0", False),
        ("4.0.0", "*", True),
        ("0.29.0", "^0.28.5", False),
        ("0.28.9", "^0.28.5", True),
    ]
    failures = []
    for version, rng, expected in cases:
        try:
            actual = satisfies(version, rng)
        except Exception as exc:  # noqa: BLE001 - a raise is itself a matcher failure
            failures.append(f"  {version} vs {rng!r}: raised {exc}")
            continue
        if actual != expected:
            failures.append(f"  {version} vs {rng!r}: expected {expected}, got {actual}")
    if failures:
        print("self-test FAILED:")
        print("\n".join(failures))
        return 1
    print(f"self-test OK — {len(cases)} cases, including known-positives that MUST unblock.")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".", type=pathlib.Path)
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="exercise the range matcher against known-positive and known-negative inputs",
    )
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    ignores = npm_ignore_entries(args.root)

    problems = []
    for name in sorted(set(ignores) - set(WATCHES)):
        problems.append(
            f"dependabot.yml ignores {name!r} but WATCHES has no entry for it — an "
            "undeclared block is one this script silently reports nothing about. Add a "
            "watch (with `upstream: None` if the blocker is in this repo)."
        )
    for name in sorted(set(WATCHES) - set(ignores)):
        problems.append(
            f"WATCHES declares {name!r} but dependabot.yml no longer ignores it — the "
            "block is gone; drop the stale watch."
        )

    stale = []
    print("Upstream-unblock watch")
    print("=" * 70)
    for name in sorted(WATCHES):
        watch = WATCHES[name]
        if name not in ignores:
            continue
        upstream = watch["upstream"]
        if upstream is None:
            print(f"{name:<14} blocked in-repo (#{watch['issue']}) — nothing upstream to poll")
            continue
        try:
            resolved, peer_range = fetch_peer_range(upstream["package"], upstream["peer"])
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, KeyError) as exc:
            # NOT a "still blocked" verdict. A probe that could not run has no verdict.
            sys.stderr.write(
                f"PROBE FAILED for {name}: {upstream['package']} peer "
                f"{upstream['peer']!r}: {exc}\n"
            )
            return 2
        admitted = satisfies(upstream["probe"], peer_range)
        state = "UNBLOCKED" if admitted else "still blocked"
        print(
            f"{name:<14} {state:<14} {upstream['package']}@{resolved} "
            f"peer {upstream['peer']}: {peer_range!r} "
            f"(probe {upstream['probe']})"
        )
        if admitted:
            stale.append(
                f"`{name}` is no longer blocked: "
                f"`{upstream['package']}@{resolved}` now declares "
                f"`{upstream['peer']}: {peer_range}`, which admits "
                f"{upstream['probe']}. Drop the `ignore:` entry for `{name}` in "
                f"`.github/dependabot.yml` and let the major bump through (#{watch['issue']})."
            )

    print("=" * 70)

    if problems:
        for p in problems:
            print(f"::error::upstream-unblock drift: {p}")
    for s in stale:
        print(f"::warning::upstream-unblock: {s}")

    summary = []
    if stale:
        summary.append("### Upstream block is now stale\n")
        summary += [f"- {s}\n" for s in stale]
    if problems:
        summary.append("### Watch/dependabot drift\n")
        summary += [f"- {p}\n" for p in problems]
    if summary:
        import os

        step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if step_summary:
            with open(step_summary, "a", encoding="utf-8") as fh:
                fh.write("".join(summary))
        return 1

    print("All declared blocks are still justified; no drift.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
