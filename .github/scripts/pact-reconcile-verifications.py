#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Find pacts a provider has not verified, and dispatch that provider's verification.
#
# WHY THIS EXISTS
#   A provider only ever verified a consumer's pact when the PROVIDER itself next
#   happened to build. Nothing connected the two. Measured on 2026-08-01:
#
#     15:08  openbank-lending-service publishes a pact for 9052f5f1
#     15:43, 16:45, 17:24, 18:42  auto-deploy FAILS — lending UNVERIFIED, blocked
#                                 on openbank-ledger-service
#     20:09  ledger finally verifies it, on a build of its own that had nothing to
#            do with lending
#
#   Five hours of red on a money-path service, and can-i-deploy was right every
#   time. 33 of the last 40 auto-deploy runs failed that way. It compounds with the
#   scoped main-push build: a push builds only the modules it can attribute the diff
#   to, so a lending-only change never rebuilds ledger. can-i-deploy classifies
#   UNVERIFIED as self-clearing — "the counterpart verifies minutes later" — which is
#   only true if something CAUSES the counterpart to verify. This is that something.
#
# WHY A RECONCILER AND NOT A BROKER WEBHOOK
#   The push version of this is a Pact Broker webhook, and it works — but it needs a
#   GitHub token stored IN the broker, and this broker is internet-facing
#   (https://pact.open-bank.tech, a documented ADR-0056 exception). That turns a
#   broker compromise into CI-execution capability, and it adds a credential whose
#   rotation nothing tracks.
#
#   Polling from CI needs NO new secret at all: the PACT_BROKER_* credentials already
#   exist for publish/can-i-deploy, and the dispatch uses the workflow's own
#   GITHUB_TOKEN, scoped by `permissions:` to `actions: write` for the length of one
#   job. Nothing outside GitHub ever holds a credential that can start a workflow.
#   The cost is latency — a poll interval instead of an instant push — against a
#   strand that ran for five hours, so minutes are not the constraint.
#
# HOW IT DECIDES
#   The integration list (which consumer talks to which provider) comes from the
#   COMMITTED pacts/*.json, not from the broker. That is deliberate: it is the set CI
#   actually cares about, it needs no extra endpoint, and a pact that exists only in
#   the broker is not something this repo can dispatch a build for anyway.
#
#   For each consumer→provider edge it asks the broker's matrix the same question
#   can-i-deploy asks — is the consumer's latest main version verified by the
#   provider's latest main version — and treats `unknown > 0` as "the provider owes a
#   verification". Two things are deliberately NOT dispatched:
#
#     A FAILED verification. Re-running a verification that failed on its merits just
#     burns a runner and hides a real contract break behind a retry loop — the #2549
#     failure shape.
#
#     A provider with NO main-branch version in the broker. This one nearly shipped:
#     the first live run reported five providers "owed", and four of them had simply
#     never published a version on main (`/pacticipants/<p>/branches/main/latest-version`
#     → 404). The matrix answers `unknown` for those exactly as it does for a real
#     strand, so dispatching on `unknown` alone would have re-dispatched those four on
#     EVERY cycle, forever, onto the six-runner pool this is supposed to stop starving
#     (#2039). auto-deploy already knows this state — "has no 'main'-tagged version yet
#     (new service, no pacts) — treating as deployable (ADR-0092)" — and this reports it
#     rather than acting on it, because a provider that has never published is a
#     different problem from one that is behind.
#
# Usage:
#   python3 .github/scripts/pact-reconcile-verifications.py            # report only
#   python3 .github/scripts/pact-reconcile-verifications.py --dispatch # and act
#   python3 .github/scripts/pact-reconcile-verifications.py --self-test

import argparse
import base64
import json
import os
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request

PACTS = "pacts/*.json"
DEFAULT_BRANCH = "main"
# A cap exists so a broker outage answering `unknown` for everything cannot dispatch
# the whole fleet onto a six-runner pool (#2039). Whatever it drops is NAMED in the
# output — a silent cap reads as "nothing else needed doing", which is the one thing
# it must never be mistaken for.
DEFAULT_MAX_DISPATCH = 8


def http_json(url, user, password, timeout=30):
    req = urllib.request.Request(url)
    if user:
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        req.add_header("Authorization", f"Basic {token}")
    req.add_header("Accept", "application/hal+json")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def integrations(root: pathlib.Path):
    """Consumer→provider edges, from the committed pacts. Sorted, deduped."""
    edges = set()
    for f in sorted(root.glob(PACTS)):
        try:
            doc = json.loads(f.read_text())
        except json.JSONDecodeError as e:
            sys.stderr.write(f"::warning::{f.name}: not valid JSON ({e}) — skipped\n")
            continue
        c = ((doc.get("consumer") or {}).get("name") or "").strip()
        p = ((doc.get("provider") or {}).get("name") or "").strip()
        if c and p:
            edges.add((c, p))
    return sorted(edges)


def has_branch_version(broker, pacticipant, user, password, branch=DEFAULT_BRANCH):
    """True if the pacticipant has ever published a version on `branch`.

    An `unknown` matrix verdict means "no verification result", which is the same answer
    whether the provider is BEHIND or has simply never published. Only this call tells
    the two apart — see the module header for what happened without it.
    """
    url = f"{broker.rstrip('/')}/pacticipants/{urllib.parse.quote(pacticipant)}/branches/{urllib.parse.quote(branch)}/latest-version"
    try:
        http_json(url, user, password)
        return True
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return False
        raise


def matrix_summary(broker, consumer, provider, user, password, branch=DEFAULT_BRANCH):
    """The same question can-i-deploy asks: is the consumer's latest verified by the provider's?"""
    q = [
        ("q[][pacticipant]", consumer), ("q[][latest]", "true"), ("q[][branch]", branch),
        ("q[][pacticipant]", provider), ("q[][latest]", "true"), ("q[][branch]", branch),
        ("latestby", "cvpv"),
    ]
    url = f"{broker.rstrip('/')}/matrix?" + urllib.parse.urlencode(q)
    return http_json(url, user, password).get("summary") or {}


def dispatch(repo, workflow, ref, service, token):
    url = f"https://api.github.com/repos/{repo}/actions/workflows/{workflow}/dispatches"
    body = json.dumps({"ref": ref, "inputs": {"service": service}}).encode()
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.status


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--broker", default=os.environ.get("PACT_BROKER_URL", ""))
    ap.add_argument("--branch", default=DEFAULT_BRANCH)
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    ap.add_argument("--workflow", default="verify-provider.yml")
    ap.add_argument("--dispatch", action="store_true", help="actually dispatch (default: report only)")
    ap.add_argument("--max-dispatch", type=int, default=DEFAULT_MAX_DISPATCH)
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    root = pathlib.Path(args.root).resolve()
    edges = integrations(root)
    if not edges:
        sys.stderr.write(
            "::error::no consumer/provider edges found in pacts/*.json — refusing to report "
            "success. With an empty edge set this reconciler would do nothing and look "
            "identical to a run where nothing needed doing.\n"
        )
        return 2

    if not args.broker:
        sys.stderr.write("::error::PACT_BROKER_URL is not set\n")
        return 2
    user = os.environ.get("PACT_BROKER_USERNAME", "")
    password = os.environ.get("PACT_BROKER_PASSWORD", "")

    owed, failing, unpublished, errors = {}, [], {}, 0
    branch_cache = {}
    for consumer, provider in edges:
        try:
            s = matrix_summary(args.broker, consumer, provider, user, password, args.branch)
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, ValueError) as e:
            # Do NOT treat an unreachable broker as "needs verification": that would
            # dispatch the fleet on an outage. Count it and surface it instead.
            sys.stderr.write(f"::warning::{consumer} -> {provider}: broker query failed: {e}\n")
            errors += 1
            continue
        unknown, failed = int(s.get("unknown") or 0), int(s.get("failed") or 0)
        if failed:
            failing.append(f"{consumer} -> {provider}")
        elif unknown:
            if provider not in branch_cache:
                try:
                    branch_cache[provider] = has_branch_version(
                        args.broker, provider, user, password, args.branch
                    )
                except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError) as e:
                    sys.stderr.write(
                        f"::warning::{provider}: could not check for a {args.branch} version: {e}"
                        f" — NOT dispatching, since it cannot be told apart from never-published\n"
                    )
                    branch_cache[provider] = False
                    errors += 1
            if branch_cache[provider]:
                owed.setdefault(provider, []).append(consumer)
            else:
                unpublished.setdefault(provider, []).append(consumer)

    print(f"{len(edges)} integration(s) checked against {args.broker}")
    if errors:
        print(f"  {errors} could not be queried (see warnings above) — NOT counted as owed")
    for pair in failing:
        print(f"  FAILED verification, not dispatching (a retry cannot fix a real break): {pair}")
    for p in sorted(unpublished):
        print(
            f"  NO {args.branch} VERSION, not dispatching: {p} has never published a version "
            f"on {args.branch}, so its consumers ({', '.join(sorted(unpublished[p]))}) read as "
            f"unverified for a different reason. Building it would not change that."
        )
    if not owed:
        print("  every provider has verified its consumers' latest pacts — nothing to do")
        return 1 if errors and not args.dispatch else 0

    providers = sorted(owed)
    for p in providers:
        print(f"  OWED  {p}  <- {', '.join(sorted(owed[p]))}")

    todo = providers[: args.max_dispatch]
    if len(providers) > len(todo):
        # Name what is dropped. A cap that reports only what it did is indistinguishable
        # from having had nothing more to do.
        dropped = providers[args.max_dispatch:]
        print(
            f"::warning::capped at {args.max_dispatch} dispatches this run; NOT dispatched "
            f"and still owed: {', '.join(dropped)} — they will be picked up next run"
        )

    if not args.dispatch:
        print(f"\n(report only — would dispatch {args.workflow} for: {', '.join(todo)})")
        return 0

    token = os.environ.get("GITHUB_TOKEN", "")
    if not token or not args.repo:
        sys.stderr.write("::error::--dispatch needs GITHUB_TOKEN and GITHUB_REPOSITORY\n")
        return 2

    bad = 0
    for p in todo:
        try:
            status = dispatch(args.repo, args.workflow, args.branch, p, token)
            print(f"  dispatched {args.workflow} for {p} (HTTP {status})")
        except urllib.error.HTTPError as e:
            sys.stderr.write(f"::error::dispatch for {p} failed: HTTP {e.code} {e.read()[:200]!r}\n")
            bad += 1
        except (urllib.error.URLError, TimeoutError) as e:
            sys.stderr.write(f"::error::dispatch for {p} failed: {e}\n")
            bad += 1
    return 1 if bad else 0


# ---------------------------------------------------------------------------
# Self-test: every branch of the decision, on synthetic summaries. No broker.
# ---------------------------------------------------------------------------
def self_test() -> int:
    # (summary, provider_has_main_branch, expect_owed, expect_failing, why)
    cases = [
        ({"deployable": True, "success": 1, "failed": 0, "unknown": 0}, True, False, False, "verified"),
        ({"deployable": None, "success": 0, "failed": 0, "unknown": 1}, True, True, False, "unverified, provider publishes"),
        # The case that nearly shipped: identical summary, but the provider has never
        # published on main. Dispatching it re-fires every cycle forever and changes
        # nothing — six providers in this fleet are in exactly that state today.
        ({"deployable": None, "success": 0, "failed": 0, "unknown": 1}, False, False, False, "unverified, provider never published"),
        ({"deployable": False, "success": 0, "failed": 1, "unknown": 0}, True, False, True, "failed"),
        ({"deployable": False, "success": 0, "failed": 1, "unknown": 1}, True, False, True, "failed wins over unknown"),
        ({}, True, False, False, "empty summary is not 'owed'"),
    ]
    bad = []
    for s, has_branch, want_owed, want_failing, why in cases:
        unknown, failed = int(s.get("unknown") or 0), int(s.get("failed") or 0)
        is_failing = bool(failed)
        is_owed = bool(unknown) and not failed and has_branch
        mark = "ok " if (is_owed, is_failing) == (want_owed, want_failing) else "BAD"
        print(f"  {mark} {why:38s} owed={is_owed} failing={is_failing}")
        if (is_owed, is_failing) != (want_owed, want_failing):
            bad.append(why)

    # The edge list must come out of the real pacts, and must not be empty — an empty
    # one is the shape in which this reconciler silently does nothing forever.
    root = pathlib.Path(".").resolve()
    edges = integrations(root)
    print(f"  {'ok ' if edges else 'BAD'} edge list from pacts/*.json: {len(edges)} edge(s)")
    if not edges:
        bad.append("edge list empty")

    if bad:
        print("\n::error::self-test FAILED: " + "; ".join(bad))
        return 1
    print("\nself-test: every decision branch classified correctly.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
