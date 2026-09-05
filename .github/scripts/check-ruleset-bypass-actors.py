#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""The set of actors who can BYPASS `main-protection` is pinned, and widening it fails a PR.

WHY THIS EXISTS (Refs #4828, sibling of #4240)
----------------------------------------------
`merged-past-red-check-watch.yml` detects a merge that went past an actively failing
required check. It is a good detector and it is only a detector: it reads
`rulesets/rule-suites` twice a day, AFTER the fact, and the merge it names is already on
`main`. Nothing in this repository looks at the other half of the same mechanism — WHO is
allowed to do that at all.

That set is one API field, `bypass_actors`, and today it is exactly one entry:

    {"actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "pull_request"}

RepositoryRole 5 is `admin`. So the bypass surface is "any repository admin, on a pull
request" — which in practice means the CLI's `--admin` override applied to a merge, under
the owner's credentials. Every agent working in this repo runs as that identity:
`current_user_can_bypass` reads `pull_requests_only` for it, not `never`. The repo's own
guardrails record an agent taking that override unprompted on a money-path security PR,
because its prompt did not forbid it.

WHAT THIS GATE DOES, AND WHAT IT HONESTLY CANNOT DO
----------------------------------------------------
It CANNOT prevent the override. Nothing in CI can: the bypass is evaluated by GitHub at
merge time, on the merge API call, long after any check has reported. A gate that claimed
otherwise would be decoration.

What it does is make the surface itself reviewed rather than ambient. `bypass_actors` can
be widened from the repository Settings UI in two clicks, by adding a team, an app, a bot,
or by flipping an existing entry's `bypass_mode` from `pull_request` to `always` (which
additionally permits a DIRECT PUSH to `main`, not merely a merge). None of that produces a
diff, a PR, an issue, or a red check — the exact write-only-control shape #4240 was opened
about, one level up. After this gate, any such change reddens every PR until the declared
set below is updated in a reviewed commit that says why.

Two directions are checked, and both matter:
  * an actor present live but NOT declared      -> the surface was widened
  * an actor declared but NOT present live      -> the declaration is stale, and a stale
                                                   declaration is how a pinned list quietly
                                                   stops describing anything

WHY THE LIVE RULESET IS THE SUBJECT, AND WHAT THE TOKEN CAN ACTUALLY SEE
-----------------------------------------------------------------
Read live, never from a checked-in copy — a hand-kept mirror diffed only when someone
remembers is this repo's most-repeated defect class. `GET /repos/{owner}/{repo}/rulesets`
and `GET .../rulesets/{id}` are world-readable on a public repository — but `bypass_actors`
is NOT. An earlier version of this header claimed it was included in the detail response;
that was wrong, and measuring it is what found the bug. On 2026-08-21, unauthenticated:

    GET /repos/JiRaska/open-bank-oss/rulesets/18325357
    keys: _links conditions created_at enforcement id name node_id rules source
          source_type target updated_at          # no `bypass_actors`

while the same call with an admin token returns `RepositoryRole#5 (pull_request)`. The
field is OMITTED for a non-admin reader, not returned empty — so `detail.get(..., [])`
silently reports "no bypass actors" to anyone who may not see them, which in CI is every
run. `live_bypass_actors` therefore treats a MISSING key as unreadable and raises. Do NOT
add `permissions: administration: read` to the
job: `administration` is not a valid workflow-permissions key at all, and GitHub cannot
parse a workflow that declares one — it broke `ci.yml` outright (zero jobs, every push)
when `check-ruleset-context-parity.py` tried it. That sibling script's header has the full
story; this one shares its transport deliberately.

Note the contrast with the runtime watch, which needs a CLASSIC PAT: `rulesets/rule-suites`
(the bypass LOG) is private and rejects fine-grained tokens, while `rulesets/{id}` (the
bypass CONFIG) is public. Different endpoint, different answer — which is why the config
half can be a PR-blocking gate and the log half cannot.

Usage:  check-ruleset-bypass-actors.py [--enforce] [--repo OWNER/REPO]
        check-ruleset-bypass-actors.py --self-test
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import gatelib  # noqa: E402

# The pinned surface. Each entry is (actor_id, actor_type, bypass_mode) plus a human reason.
# Adding a line here is granting a machine or a person the ability to merge past every
# required check on this repository — it is not a list edit, and it belongs in a PR whose
# body says who asked for it.
DECLARED_BYPASS_ACTORS: dict[tuple[int, str, str], str] = {
    (5, "RepositoryRole", "pull_request"): (
        "the built-in `admin` repository role, on pull requests only. This is the actor "
        "behind every row #4828 lists. `pull_request` mode does NOT permit a direct push "
        "to main; a change of this entry to `always` would."
    ),
}

TARGET_REF = "refs/heads/main"


class Unreadable(RuntimeError):
    """The subject could not be READ — distinct from the subject being clean, and from a broken call.

    A non-admin token omits `bypass_actors` entirely, and CI runs as `GITHUB_TOKEN`. Raising the
    same `RuntimeError` a failed `gh api` raises made the gate exit 1 on every CI run, so an
    enforced gate that has never once been evaluable was red on every pull request — including the
    pull request adding it. Reporting UNRESOLVED is the honest answer: it is not a pass (nothing was
    checked) and not a finding (nothing was seen).
    """


def gh_api(path: str) -> list | dict:
    """`gh api <path>`, parsed. Raises RuntimeError on every failure mode.

    A missing `gh` binary, a network error, a rate limit and a non-JSON body all become one
    exception type, so the caller's single `except RuntimeError` covers them. A gate that
    cannot reach its subject must say so, never report a clean pass.
    """
    try:
        p = subprocess.run(["gh", "api", path], capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError) as exc:
        raise RuntimeError(f"could not run `gh api {path}`: {exc}") from exc
    if p.returncode != 0:
        raise RuntimeError(f"gh api {path} failed (rc={p.returncode}): {p.stderr.strip()}")
    try:
        return json.loads(p.stdout)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"gh api {path} returned non-JSON: {exc}") from exc


def live_bypass_actors(repo: str) -> list[tuple[int, str, str]]:
    """Every bypass actor of every ACTIVE branch ruleset that includes `refs/heads/main`.

    `enforcement` must be `active`: an `evaluate`-mode ruleset is a dry run whose bypass
    list grants nothing. Rulesets targeting other refs are out of scope — this gate is
    about who can bypass protection ON MAIN, and pinning unrelated rulesets would make the
    declaration churn for changes that cannot affect it.
    """
    out: list[tuple[int, str, str]] = []
    for summary in gh_api(f"repos/{repo}/rulesets"):
        if summary.get("enforcement") != "active" or summary.get("target") != "branch":
            continue
        detail = gh_api(f"repos/{repo}/rulesets/{summary['id']}")
        include = detail.get("conditions", {}).get("ref_name", {}).get("include", [])
        if TARGET_REF not in include and "~DEFAULT_BRANCH" not in include and "~ALL" not in include:
            continue
        # A NON-ADMIN read of a ruleset OMITS `bypass_actors` entirely — it does not return an
        # empty list. Measured 2026-08-21 against this repo: an unauthenticated
        # `GET /repos/JiRaska/open-bank-oss/rulesets/18325357` returns no `bypass_actors` key at
        # all, while an admin read returns `RepositoryRole#5 (pull_request)`. Defaulting the
        # missing key to `[]` therefore reads "I may not see this" as "there are none", which
        # breaks the gate in BOTH directions: it reports every correct declaration as stale, and
        # — far worse — a real widening also arrives as zero actors, so the one thing this gate
        # exists to catch could never make it fire. CI runs with GITHUB_TOKEN, which is not an
        # admin, so that was every CI run. Fail as unreadable instead.
        if "bypass_actors" not in detail:
            raise Unreadable(
                f"ruleset {summary['id']} ({summary.get('name')}) returned no `bypass_actors` "
                f"field — the token cannot see bypass actors (admin scope required), so this "
                f"gate CANNOT be evaluated. Not a finding: an unreadable subject is UNRESOLVED, "
                f"never 'zero actors'. Run it with an admin token, or skip it where none exists."
            )
        for actor in detail["bypass_actors"]:
            out.append(
                (
                    int(actor.get("actor_id") or 0),
                    str(actor.get("actor_type") or ""),
                    str(actor.get("bypass_mode") or ""),
                )
            )
    return out


def findings(
    live: list[tuple[int, str, str]],
    declared: dict[tuple[int, str, str], str],
) -> tuple[list[tuple[int, str, str]], list[tuple[int, str, str]]]:
    """(undeclared, stale) — the surface was widened, and the declaration describes nothing."""
    live_set = set(live)
    undeclared = sorted(live_set - set(declared))
    stale = sorted(set(declared) - live_set)
    return undeclared, stale


def _fmt(a: tuple[int, str, str]) -> str:
    return f"{a[1]}#{a[0]} (bypass_mode={a[2]})"


def _run_main(argv: list[str]) -> int:
    """Run main() with a synthetic argv, so the self-test can assert EXIT CODES, not just types."""
    saved = sys.argv
    sys.argv = ["check-ruleset-bypass-actors.py", *argv]
    try:
        return main()
    finally:
        sys.argv = saved


def self_test() -> int:
    fails: list[str] = []

    def case(label, live, declared, want_undeclared, want_stale):
        u, s = findings(live, {k: "" for k in declared})
        if sorted(u) != sorted(want_undeclared) or sorted(s) != sorted(want_stale):
            fails.append(
                f"{label}: want undeclared={want_undeclared} stale={want_stale}, got {u} / {s}"
            )

    admin = (5, "RepositoryRole", "pull_request")
    admin_always = (5, "RepositoryRole", "always")
    a_bot = (1234, "Integration", "pull_request")

    case("today's real shape is clean", [admin], [admin], [], [])
    case("a NEW bypass actor is reported", [admin, a_bot], [admin], [a_bot], [])
    # The one that matters most: same actor, widened mode. A comparison keyed only on
    # actor_id would call this clean, and it is the difference between "can merge a PR past
    # a red check" and "can push straight to main".
    case(
        "widening bypass_mode pull_request -> always is a DIFFERENT actor tuple",
        [admin_always], [admin], [admin_always], [admin],
    )
    case(
        "a declared actor that no longer exists live is reported as stale",
        [], [admin], [], [admin],
    )
    case("an empty ruleset with an empty declaration is clean", [], [], [], [])
    case("both directions are reported at once", [a_bot], [admin], [a_bot], [admin])

    # An UNREADABLE ruleset must raise, never resolve to "no bypass actors". Without this the
    # gate is vacuous for its own purpose: a non-admin token sees no `bypass_actors` key, so a
    # real widening arrives looking exactly like an empty bypass list, and the gate reports the
    # honest declaration as stale instead of reporting that it could not look.
    def _fake_detail(payload):
        def fake(path):
            if path.endswith("/rulesets"):
                return [{"id": 1, "enforcement": "active", "target": "branch", "name": "x"}]
            return payload
        return fake

    _real = globals()["gh_api"]
    try:
        globals()["gh_api"] = _fake_detail(
            {"conditions": {"ref_name": {"include": [TARGET_REF]}}}  # no bypass_actors key
        )
        try:
            live_bypass_actors("o/r")
            fails.append("an unreadable ruleset (no bypass_actors key) must RAISE, not return []")
        except Unreadable:
            pass
        except RuntimeError:
            fails.append("an unreadable ruleset must raise Unreadable, not a bare RuntimeError")

        # The exit code for "could not look" is the whole point of the Unreadable/RuntimeError
        # split: an enforced gate that answers 1 here is red on every CI run and never once says
        # anything about bypass actors. Assert the code, not just the exception type.
        rc = _run_main(["--enforce"])
        if rc != 0:
            fails.append(f"an unreadable subject must exit 0 (UNRESOLVED), got {rc}")

        # ...and the opposite direction, or "exit 0" would just mean "never fails".
        globals()["gh_api"] = _fake_detail(
            {
                "conditions": {"ref_name": {"include": [TARGET_REF]}},
                "bypass_actors": [{"actor_id": 999, "actor_type": "Team", "bypass_mode": "always"}],
            }
        )
        rc = _run_main(["--enforce"])
        if rc != 1:
            fails.append(f"an undeclared live bypass actor must exit 1 under --enforce, got {rc}")

        def _broken(path):
            raise RuntimeError("gh api exploded")

        globals()["gh_api"] = _broken
        rc = _run_main(["--enforce"])
        if rc != 1:
            fails.append(f"a broken API call must still exit 1, got {rc}")

        globals()["gh_api"] = _fake_detail(
            {"conditions": {"ref_name": {"include": [TARGET_REF]}}}  # back to unreadable
        )
        globals()["gh_api"] = _fake_detail(
            {"conditions": {"ref_name": {"include": [TARGET_REF]}}, "bypass_actors": []}
        )
        if live_bypass_actors("o/r") != []:
            fails.append("a genuinely EMPTY bypass_actors list must resolve to [], not raise")
    finally:
        globals()["gh_api"] = _real

    # The gate must be able to FAIL on the real declared set — a self-test that only ever
    # exercises the clean case cannot tell a working comparison from `return [], []`.
    u, s = findings([admin, a_bot], DECLARED_BYPASS_ACTORS)
    if u != [a_bot] or s:
        fails.append(f"against the real DECLARED set: want [{_fmt(a_bot)}] undeclared, got {u} / {s}")
    u, s = findings(list(DECLARED_BYPASS_ACTORS), DECLARED_BYPASS_ACTORS)
    if u or s:
        fails.append(f"the real DECLARED set must be self-consistent, got {u} / {s}")

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: ruleset-bypass-actors is falsifiable (12 cases, both directions, exit codes included)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--repo", default="JiRaska/open-bank-oss")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    try:
        live = live_bypass_actors(args.repo)
    except Unreadable as exc:
        # NOT a pass: say out loud that nothing was checked, and let the runner's floor logic know
        # the corpus was never read, so this run cannot be mistaken for a clean one.
        gatelib.subjects_unresolved("bypass_actors is not visible to this token (admin scope required)")
        print(f"::warning::ruleset-bypass-actors: {exc}")
        print(
            "ruleset-bypass-actors: NOT EVALUATED on this run. The check has teeth only where the "
            "token can read `bypass_actors` — run it with an admin token to get a verdict. Treating "
            "an unreadable subject as red would make the gate permanently and uninformatively red; "
            "treating it as green would be a lie. UNRESOLVED is neither."
        )
        return 0
    except RuntimeError as exc:
        sys.stderr.write(f"::error::ruleset-bypass-actors: {exc}\n")
        return 1

    undeclared, stale = findings(live, DECLARED_BYPASS_ACTORS)
    level = "error" if args.enforce else "warning"
    gatelib.subjects(len(live), "bypass actors on an active ruleset covering main")

    for a in undeclared:
        print(
            f"::{level}::ruleset-bypass-actors: {_fmt(a)} can bypass main-protection and is "
            f"NOT declared in DECLARED_BYPASS_ACTORS. Someone widened who may merge past "
            f"every required check, and that change produced no diff and no red check "
            f"anywhere else (Refs #4828). Either revert it in repository Settings, or "
            f"declare it here in a PR that says who asked for it and why."
        )
    for a in stale:
        print(
            f"::{level}::ruleset-bypass-actors: {_fmt(a)} is declared here but no longer "
            f"holds a bypass on main. Remove the entry — a declaration that describes "
            f"nothing is how a pinned list stops being a pin."
        )
    print(
        f"ruleset-bypass-actors: {len(live)} live bypass actor(s), "
        f"{len(undeclared)} undeclared, {len(stale)} stale."
    )
    return 1 if ((undeclared or stale) and args.enforce) else 0


if __name__ == "__main__":
    sys.exit(main())
