#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# ADR-0251 SCOPE RESOLVER — is this PR title in scope for independent model review?
#
# In scope iff the conventional-commit SCOPE names a `rules.yaml: money_path_services`
# entry, the TYPE is `security`, or the SCOPE is `governance`.
#
# This lives in a script rather than inline in agent-review.yml because both of its rules
# were WRONG in the first draft, and both were wrong in the silent direction — the workflow
# would have reviewed less than it claimed while reporting nothing unusual:
#
#   1. `governance` was written as a TYPE. rules.yaml `commits.types` is
#      feat|fix|perf|refactor|docs|test|chore|build|ci|security, so no such type exists and
#      the branch was structurally unreachable — the same defect class as the
#      `input.principal.type == "SERVICE"` rego rule this repo banned outright. Measured:
#      0 PRs with a `governance:` type in 7 days, against 14 with `security:`. Governance
#      work lands as `ci(governance)`, so the SCOPE is what carries it.
#
#   2. The money-path list was matched literally. `money_path_services` spells entries
#      `openbank-ledger-service`, but real commit scopes use both spellings for the same
#      service — `fix(fraud)` 7x and `fix(fraud-service)` 1x in a single week. Literal
#      matching covered 48 PRs where the correct rule covers 114, and missed `fix(ledger)`,
#      the most obviously in-scope shape in the repository.
#
# Neither was visible by reading the rule; both fell out of running it against titles whose
# answer was known. So the self-test IS the artifact, and the rule is the thing it guards.
#
# Usage:  agent-review-scope.py --title "fix(ledger): ..."   # prints in_scope=... / why=...
#         agent-review-scope.py --self-test
#
# Exit 0 always for a real classification (the VERDICT is on stdout, for $GITHUB_OUTPUT);
# exit 1 only when the rules file cannot be read, because silently answering "out of scope"
# to an unreadable money-path list is how this control would quietly stop covering anything.

import argparse
import pathlib
import re
import sys

import yaml

RULES = "openbank-libs/governance/rules.yaml"

# A type that always warrants a second reader regardless of which service it touches.
IN_SCOPE_TYPES = {"security"}
# A scope that always warrants one — governance changes are fleet-wide by construction.
IN_SCOPE_SCOPES = {"governance"}


def money_path_scopes(root="."):
    """The money-path service names as a commit SCOPE may spell them — both suffixes."""
    f = pathlib.Path(root) / RULES
    if not f.exists():
        raise FileNotFoundError(f"{RULES} not found — cannot resolve money-path scope")
    doc = yaml.safe_load(f.read_text()) or {}
    raw = doc.get("money_path_services")
    if not raw:
        raise ValueError(f"{RULES}: money_path_services is empty or missing — refusing to "
                         f"classify every PR as out of scope on the strength of that")
    out = set()
    for s in raw:
        n = s.replace("openbank-", "")
        out.add(n)
        # Accept the other spelling of the SAME service. Not a guess: both forms are in live
        # use in this repo's commit history for one service.
        out.add(n[:-8] if n.endswith("-service") else n + "-service")
    return out


def parse_title(title):
    m = re.match(r"^(\w+)(?:\(([^)]+)\))?!?:", title or "")
    return (m.group(1), m.group(2)) if m else (None, None)


def classify(title, mp):
    ty, sc = parse_title(title)
    if ty in IN_SCOPE_TYPES:
        return True, f"type={ty}"
    if sc in IN_SCOPE_SCOPES:
        return True, f"scope={sc}"
    if sc and sc in mp:
        return True, f"money-path scope={sc}"
    return False, f"type={ty} scope={sc}"


def self_test():
    # The fixture is a FILE, and it goes through money_path_scopes() — the real function.
    #
    # The first version of this self-test built the normalised set inline with its own copy
    # of the loop. It passed, and it kept passing when the suffix normalisation was deleted
    # from money_path_scopes(): the counter-example never reached the code under test. That
    # is the defect this whole script exists to prevent, reproduced inside its own test, so
    # the fixture must be read the way production reads it.
    #
    # Spellings here are rules.yaml's (`openbank-*-service`), so the normalisation is what
    # has to do the work for a `fix(ledger)`-shaped title to match.
    import tempfile

    tmp = tempfile.mkdtemp()
    fixture = pathlib.Path(tmp) / RULES
    fixture.parent.mkdir(parents=True, exist_ok=True)
    fixture.write_text(
        "money_path_services:\n"
        "  - openbank-ledger-service\n"
        "  - openbank-fraud-service\n"
        "  - openbank-sepa-payment\n"
        "  - openbank-delegation-service\n"
    )
    mp = money_path_scopes(root=tmp)

    cases = [
        # (title, expected)
        ("fix(ledger): wrong sign on a reversal", True),          # suffix-stripped spelling
        ("fix(ledger-service): wrong sign", True),                # rules.yaml spelling
        ("feat(fraud): new rule", True),
        ("fix(fraud-service): new rule", True),                   # both spellings, one service
        ("fix(sepa-payment): return handling", True),             # no -service suffix at all
        ("security(psd2): token leak", True),                     # type, not a money scope
        ("security: no scope at all", True),                      # type alone is enough
        ("ci(governance): assert an event contract", True),       # governance is a SCOPE
        ("docs(governance): document the matrix", True),
        ("governance(x): a type that does not exist", False),     # the dead branch
        ("feat(admin-ui): new page", False),
        ("chore(main): release main", False),                     # release-please
        ("chore(gitops): auto-deploy sandbox-abc", False),        # auto-deploy
        ("chore(admin-ui): deploy sandbox-abc", False),
        ("docs: no scope", False),
        ("not a conventional commit at all", False),
        ("", False),
        ("feat(delegation): count delegated spend", True),
    ]
    fails = []
    for title, want in cases:
        got, why = classify(title, mp)
        if got != want:
            fails.append(f"{title!r}: expected in_scope={want}, got {got} ({why})")

    # The bot titles above are the 45% of the queue that must never be reviewed. Assert the
    # count, so deleting a case is a failure rather than a quieter test.
    if len(cases) != 18:
        fails.append(f"case count changed: {len(cases)} != 18")

    # An unreadable/empty money-path list must RAISE, never classify everything out of scope.
    try:
        money_path_scopes(root="/nonexistent-root-for-self-test")
        fails.append("a missing rules.yaml did not raise (would silently review nothing)")
    except (FileNotFoundError, ValueError):
        pass

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print(f"self-test ok: ADR-0251 scope rule is falsifiable ({len(cases)} titles + 1 read failure)")
    return 0


def main():
    ap = argparse.ArgumentParser(description="ADR-0251 review scope resolver")
    ap.add_argument("--title", help="the PR title")
    ap.add_argument("--root", default=".", help="repo root")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    try:
        mp = money_path_scopes(args.root)
    except (FileNotFoundError, ValueError) as e:
        sys.stderr.write(f"::error::{e}\n")
        return 1

    in_scope, why = classify(args.title, mp)
    print(f"in_scope={'true' if in_scope else 'false'}")
    print(f"why={why}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
