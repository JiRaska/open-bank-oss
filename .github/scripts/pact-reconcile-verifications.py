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
#     A provider whose build CANNOT publish a verification result. A provider only
#     publishes to the broker from a `@PactBroker`-sourced verification test; a
#     `@PactFolder` one replays the committed pact from disk and never contacts the
#     broker at all. Eight of this fleet's seventeen providers are folder-only, so
#     dispatching them re-runs a build that cannot change the answer — every cycle,
#     forever. That is not hypothetical: the first live dispatch of this reconciler
#     went to openbank-kyc-service, which is folder-only, and would have repeated
#     every 30 minutes indefinitely. The check is `@PactBroker` in the provider's own
#     test sources, offline, from the repo.
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
import hashlib
import json
import os
import pathlib
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402  — the shared gh-transient vocabulary lives here

PACTS = "pacts/*.json"
DEFAULT_BRANCH = "main"
# A cap exists so a broker outage answering `unknown` for everything cannot dispatch
# the whole fleet onto a six-runner pool (#2039). Whatever it drops is NAMED in the
# output — a silent cap reads as "nothing else needed doing", which is the one thing
# it must never be mistaken for.
DEFAULT_MAX_DISPATCH = 8

# Enough of a broker error body to name the rejected selector; not enough to paste a page of HTML.
HTTP_ERROR_DETAIL_CHARS = 400

# Run names are queryable in the workflow-runs API; workflow_dispatch inputs are not.
RUN_NAME_PREFIX = "pact-verify:"
HISTORY_START = "2026-09-25T00:00:00Z"
HISTORY_PAGE_SIZE = 100
HISTORY_MAX_PAGES = 20
RUN_NAME = re.compile(r"^pact-verify:(openbank-[a-z0-9-]+):([0-9a-f]{64}|manual):(auto|manual)$")


def redact_origin(url: str) -> str:
    """Path plus query, never the host.

    PACT_BROKER_URL is a secret here (the broker has no public ingress), and CI logs on a public
    repository are readable by anyone. The host is also the one part of the URL that a selector
    rejection is never about: a broker 400 on /matrix is about the q[] terms, which live in the
    query. So the diagnosable half is safe to print and the unsafe half carries no information.
    """
    split = urllib.parse.urlsplit(url)
    return urllib.parse.urlunsplit(("", "", split.path, split.query, ""))


def broker_error_message(reason, url: str, detail: str) -> str:
    """What the caller's ::warning:: line says when the broker rejects a query.

    Must name the SUBJECT (the path and the selectors) and must not name the HOST. The self-test
    exercises this function rather than re-deriving the string, so a change to either half is
    caught here instead of in a CI log two days later.
    """
    if len(detail) > HTTP_ERROR_DETAIL_CHARS:
        detail = detail[:HTTP_ERROR_DETAIL_CHARS] + "\u2026"
    return f"{reason} for {redact_origin(url)}" + (f" \u2014 {detail}" if detail else "")


PACTICIPANT_NOT_FOUND = re.compile(r"Pacticipant (\S+?) not found")


def missing_pacticipant(error: Exception, consumer: str, provider: str) -> str | None:
    """Which side of the edge the broker says does not exist, or None if that is not the error.

    #9776. The broker answers a matrix query for a pacticipant that has never published anything
    with HTTP 400 and `{"errors":["Pacticipant <name> not found"]}`. That is not a broker failure
    and not a stranded pact: it is the same "never published" state `has_branch_version` already
    classifies, reached one call earlier. Counting it as an error left the edge permanently
    unevaluated and indistinguishable from an outage — two days of runs could not say why, and
    after #9818 printed the body the answer was this sentence.

    Deliberately narrow. Only a 400/404, only this exact sentence, and only when the named
    pacticipant is EXACTLY one side of this edge — a name that merely contains the provider's
    name, or a "not found" about something else, stays an error. Anything wider would let a real
    broker fault be quietly reclassified as a benign state, which is the one direction this
    reconciler must never drift.
    """
    if not isinstance(error, urllib.error.HTTPError) or error.code not in (400, 404):
        return None
    for name in PACTICIPANT_NOT_FOUND.findall(str(error)):
        name = name.strip('"\',')
        if name in (consumer, provider):
            return name
    return None


def http_json(url, user, password, timeout=30):
    req = urllib.request.Request(url)
    if user:
        token = base64.b64encode(f"{user}:{password}".encode()).decode()
        req.add_header("Authorization", f"Basic {token}")
    req.add_header("Accept", "application/hal+json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        # `HTTPError.__str__` renders as "HTTP Error 400: Bad Request" and nothing else, so the
        # caller's warning named a status and no subject — which is why #9776 sat unactionable for
        # two days. The broker DOES say which selector it rejected, in the response body; re-raise
        # with the body and the redacted path/query attached so the warning identifies the edge.
        try:
            detail = e.read().decode("utf-8", "replace").strip()
        except OSError:
            detail = ""
        raise urllib.error.HTTPError(
            e.url, e.code, broker_error_message(e.reason, url, detail), e.headers, None,
        ) from None


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


def can_publish_verification(root: pathlib.Path, provider: str) -> bool:
    """True if the provider has a broker-sourced test selected by providerPactTest.

    A `@PactFolder` test replays the committed pact from disk and never contacts the
    broker, so no amount of building that provider will ever publish a verification
    result. A @PactBroker class with the wrong filename is equally inert: the Gradle
    providerPactTest source set selects *ProviderVerificationTest.kt. Lending had a
    broker annotation on *PactBrokerProviderTest.kt and seven 30-minute dispatches
    rebuilt the service before the contract job reported no providerPactTest (#10787).
    Dispatch only when the broker class is discoverable by that same convention.
    """
    tests = root / provider / "src" / "test"
    if not tests.is_dir():
        return False
    for f in tests.rglob("*.kt"):
        if not f.name.endswith("ProviderVerificationTest.kt"):
            continue
        try:
            src = f.read_text(errors="replace")
        except OSError:
            continue
        if "@Provider" in src and "@PactBroker" in src:
            return True
    return False


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


def dispatch(repo, workflow, ref, service, key, token):
    url = f"https://api.github.com/repos/{repo}/actions/workflows/{workflow}/dispatches"
    body = json.dumps({"ref": ref, "inputs": {"service": service, "reconcile_key": key}}).encode()
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.status


def verification_key(root: pathlib.Path, provider: str, consumers: list[str], broker: str, user: str, password: str, branch: str, fetch=None, tree_oid=None) -> str:
    """Hash the inputs whose changes can make a failed provider run worth retrying.

    Git tree IDs avoid reading entire services on every tick and do not change for
    unrelated main commits. The broker pact CONTENT matters, not its consumer app
    version: Pact inherits verification for identical content republished by a new
    consumer version. Missing evidence is an error, not a reason to send a build.
    """
    fetch = fetch or http_json
    tree_oid = tree_oid or (lambda worktree, path: subprocess.check_output(
        ["git", "rev-parse", f"HEAD:{path}"], cwd=worktree, text=True,
    ).strip())
    shared = sorted(p.name for p in root.glob("openbank-libs*") if p.is_dir())
    paths = [provider, *shared, "build-logic", "gradle", "settings.gradle.kts",
             "build.gradle.kts", "gradle.properties", ".github/workflows/verify-provider.yml",
             ".github/workflows/_service-ci.yml"]
    digest = hashlib.sha256()
    for path in paths:
        if not (root / path).exists():
            continue
        oid = tree_oid(root, path)
        digest.update(f"git:{path}:{oid}\n".encode())
    selected = set(consumers)
    pact_count = 0
    for path in sorted((root / "pacts").glob("*.json")):
        doc = json.loads(path.read_text())
        if ((doc.get("provider") or {}).get("name") == provider
                and (doc.get("consumer") or {}).get("name") in selected):
            digest.update(f"pact:{path.name}:".encode())
            digest.update(hashlib.sha256(path.read_bytes()).hexdigest().encode())
            digest.update(b"\n")
            pact_count += 1
    if pact_count < len(selected):
        raise ValueError(f"{provider}: missing committed pact for an owed consumer")
    for consumer in sorted(selected):
        url = (f"{broker.rstrip('/')}/pacticipants/{urllib.parse.quote(consumer)}"
               f"/branches/{urllib.parse.quote(branch)}/latest-version")
        version = fetch(url, user, password).get("number")
        if not isinstance(version, str) or not version:
            raise ValueError(f"{consumer}: latest {branch} broker version has no number")
        pact_url = (f"{broker.rstrip('/')}/pacts/provider/{urllib.parse.quote(provider)}"
                    f"/consumer/{urllib.parse.quote(consumer)}"
                    f"/version/{urllib.parse.quote(version)}")
        pact = fetch(pact_url, user, password)
        if ((pact.get("consumer") or {}).get("name") != consumer
                or (pact.get("provider") or {}).get("name") != provider
                or not ("interactions" in pact or "messages" in pact)):
            raise ValueError(f"{consumer} -> {provider}: broker pact content is incomplete")
        contract = {name: pact[name] for name in
                    ("consumer", "provider", "interactions", "messages", "metadata", "pluginData")
                    if name in pact}
        content = json.dumps(contract, sort_keys=True, separators=(",", ":")).encode()
        digest.update(f"broker-pact:{consumer}:".encode())
        digest.update(hashlib.sha256(content).hexdigest().encode())
        digest.update(b"\n")
    return digest.hexdigest()


def github_json(url: str, token: str) -> dict:
    req = urllib.request.Request(url)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.loads(response.read().decode())


def complete_run_history(repo: str, workflow: str, token: str, fetch=None) -> list[dict]:
    """Fetch every run in the migration window, or refuse to decide.

    The hard page cap limits API work during a storm. Exceeding it pauses automated
    dispatch and leaves the broker debt visible; it must not silently forget a failure.
    """
    fetch = fetch or github_json
    runs = []
    first_count = None
    first_ids = None
    for page in range(1, HISTORY_MAX_PAGES + 1):
        query = urllib.parse.urlencode({
            "event": "workflow_dispatch", "created": f">={HISTORY_START}",
            "per_page": HISTORY_PAGE_SIZE, "page": page,
        })
        url = f"https://api.github.com/repos/{repo}/actions/workflows/{workflow}/runs?{query}"
        doc = fetch(url, token)
        count, batch = doc.get("total_count"), doc.get("workflow_runs")
        if not isinstance(count, int) or not isinstance(batch, list):
            raise TypeError("incomplete Actions run-history response")
        ids = [r.get("id") for r in batch if isinstance(r, dict)]
        if len(ids) != len(batch) or any(not isinstance(i, int) for i in ids):
            raise TypeError("Actions run history has missing run IDs")
        if first_count is None:
            first_count, first_ids = count, ids
        elif count != first_count:
            raise ValueError("Actions run count changed during pagination")
        if count > HISTORY_PAGE_SIZE * HISTORY_MAX_PAGES:
            raise ValueError(f"Actions run history exceeds {HISTORY_MAX_PAGES} pages")
        runs.extend(batch)
        if len(runs) >= count:
            if len(runs) != count or len({r["id"] for r in runs}) != count:
                raise ValueError("Actions run-history pages overlap or omit runs")
            if page > 1:
                first_url = url.replace(f"page={page}", "page=1")
                fresh = fetch(first_url, token)
                fresh_runs = fresh.get("workflow_runs")
                if (fresh.get("total_count") != first_count or not isinstance(fresh_runs, list)
                        or [r.get("id") for r in fresh_runs] != first_ids):
                    raise ValueError("Actions first page changed during pagination")
            return runs
        if not batch:
            raise ValueError("Actions run-history pagination ended early")
    raise ValueError("Actions run-history page cap reached")


def admit_provider(provider: str, key: str, runs: list[dict]) -> tuple[bool, str]:
    """Admit one new run only when complete history proves no same-input attempt exists."""
    named_dates = [r.get("created_at") for r in runs if RUN_NAME.fullmatch(str(r.get("display_title", "")))]
    first_named_at = min(named_dates) if named_dates else None
    for run in runs:
        title = run.get("display_title")
        created = run.get("created_at")
        status = run.get("status")
        conclusion = run.get("conclusion")
        link = run.get("html_url")
        if not all(isinstance(x, str) and x for x in (title, created, status, link)):
            return False, "malformed Actions run evidence"
        if status == "completed" and not isinstance(conclusion, str):
            return False, f"terminal Actions run has no conclusion: {link}"
        match = RUN_NAME.fullmatch(title)
        if match:
            seen_provider, seen_key, origin = match.groups()
            if seen_provider != provider:
                continue
            if status != "completed":
                return False, f"{provider}: verification already {status}: {link}"
            if origin == "auto" and seen_key == key:
                return False, f"{provider}: unchanged inputs already attempted ({conclusion}): {link}"
        elif title == "Verify one provider":
            # Legacy run-list entries have inputs=null and cannot be attributed. A
            # live one may still be this provider; stop until it reaches terminal.
            if status != "completed":
                return False, f"unattributed legacy verification still {status}: {link}"
            if first_named_at is not None and created > first_named_at:
                return False, f"unattributed verification after migration marker: {link}"
        else:
            return False, f"unrecognized verification run title: {link}"
    return True, "no matching active or terminal attempt"


def _defer(provider: str, message: str, deferred: list) -> bool:
    """Is this dispatch failure a TRANSIENT GitHub answer rather than a real one?

    A rate-limited or transport-failed dispatch is a **debt**, not a defect: the
    provider still owes the verification, the next scheduled run re-derives the same
    `todo` set from the broker, and nothing about the repo needs changing. Failing the
    run for it turns a quota window into a red reconcile — measured 2026-09-11/12,
    **11 of the last 60 scheduled runs** failed and EVERY one was
    `API rate limit exceeded for installation ... (HTTP 403)` on the dispatch POST,
    while the reconcile logic itself was correct in all 11. That red is worse than
    noise: this workflow is one of the few things watching for a stranded pact, so a
    failure nobody can act on is how a real strand stops being visible.

    The distinction is NOT re-derived here. `gatelib.is_gh_transient` compiles the
    shared vocabulary in `gh-transient-patterns.txt`, whose whole point is that four
    independent copies of this question drifted apart; its measured property is zero
    over-retries across 11 terminal messages (404/422/401/`Resource not accessible by
    integration`), so a genuinely broken dispatch — missing workflow, bad ref, token
    without `actions: write` — still fails this run, loudly.
    """
    if gatelib.is_gh_transient(message):
        sys.stderr.write(f"::warning::dispatch for {provider} deferred (transient): {message}\n")
        deferred.append(provider)
        return True
    return False


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

    owed, failing, unpublished, cannot_publish, errors = {}, [], {}, {}, 0
    consumer_unpublished = []
    branch_cache = {}
    for consumer, provider in edges:
        try:
            s = matrix_summary(args.broker, consumer, provider, user, password, args.branch)
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, ValueError) as e:
            missing = missing_pacticipant(e, consumer, provider)
            if missing == provider:
                # Never published at all: the same state as "no main version", one call earlier.
                branch_cache[provider] = False
                unpublished.setdefault(provider, []).append(consumer)
                continue
            if missing == consumer:
                # The consumer's pact was never published, so there is nothing for the provider
                # to verify and nothing this reconciler could dispatch to change that.
                consumer_unpublished.append(f"{consumer} -> {provider}")
                continue
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
            if not branch_cache[provider]:
                unpublished.setdefault(provider, []).append(consumer)
            elif not can_publish_verification(root, provider):
                cannot_publish.setdefault(provider, []).append(consumer)
            else:
                owed.setdefault(provider, []).append(consumer)

    print(f"{len(edges)} integration(s) checked against Pact Broker")
    if errors:
        print(f"  {errors} could not be queried (see warnings above) — NOT counted as owed")
    for pair in failing:
        print(f"  FAILED verification, not dispatching (a retry cannot fix a real break): {pair}")
    for p in sorted(cannot_publish):
        print(
            f"  CANNOT PUBLISH, not dispatching: {p} has only a @PactFolder verification "
            f"test, which replays the committed pact from disk and never contacts the "
            f"broker — building it cannot publish a result, so its consumers "
            f"({', '.join(sorted(cannot_publish[p]))}) would stay unverified and this "
            f"would re-dispatch every cycle. It needs the @PactBroker half."
        )
    for pair in consumer_unpublished:
        print(
            f"  CONSUMER NEVER PUBLISHED, not dispatching: {pair} — the broker has no such "
            f"consumer pacticipant, so no pact exists for the provider to verify. The committed "
            f"pact file is not on the broker yet."
        )
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

    if not args.dispatch:
        print("\n(report only — owed providers listed above; Actions history admission was not run)")
        return 0

    token = os.environ.get("GITHUB_TOKEN", "")
    if not token or not args.repo:
        sys.stderr.write("::error::--dispatch needs GITHUB_TOKEN and GITHUB_REPOSITORY\n")
        return 2

    try:
        # Complete every evidence check BEFORE the first POST. A partial history or
        # broker answer must not permit a subset of the fleet to escape admission.
        keys = {
            p: verification_key(root, p, owed[p], args.broker, user, password, args.branch)
            for p in providers
        }
        runs = complete_run_history(args.repo, args.workflow, token)
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError,
            OSError, subprocess.CalledProcessError, TypeError, ValueError) as e:
        sys.stderr.write(f"::error::verification admission evidence incomplete: {e} — no dispatch\n")
        return 2

    eligible = []
    for p in providers:
        allowed, reason = admit_provider(p, keys[p], runs)
        if allowed:
            eligible.append(p)
        else:
            print(f"  HELD  {reason}; broker verification remains owed")

    todo = eligible[: args.max_dispatch]
    if len(eligible) > len(todo):
        dropped = eligible[args.max_dispatch:]
        print(
            f"::warning::capped at {args.max_dispatch} dispatches this run; NOT dispatched "
            f"and still owed: {', '.join(dropped)} — they will be picked up next run"
        )

    bad = 0
    deferred = []
    for p in todo:
        try:
            status = dispatch(args.repo, args.workflow, args.branch, p, keys[p], token)
            print(f"  dispatched {args.workflow} for {p} (HTTP {status})")
        except urllib.error.HTTPError as e:
            msg = f"HTTP {e.code} {e.read()[:200]!r}"
            if _defer(p, msg, deferred):
                continue
            sys.stderr.write(f"::error::dispatch for {p} failed: {msg}\n")
            bad += 1
        except (urllib.error.URLError, TimeoutError) as e:
            if _defer(p, str(e), deferred):
                continue
            sys.stderr.write(f"::error::dispatch for {p} failed: {e}\n")
            bad += 1
    if deferred:
        # Named, never silent: an unnamed deferral reads as "nothing else needed doing",
        # the same mistake the --max-dispatch cap is written to avoid.
        print(
            f"::warning::dispatch deferred for {len(deferred)} provider(s) on a transient "
            f"GitHub answer; still owed and picked up next run (~30 min): "
            f"{', '.join(deferred)}"
        )
    return 1 if bad else 0


# ---------------------------------------------------------------------------
# Self-test: every branch of the decision, on synthetic summaries. No broker.
# ---------------------------------------------------------------------------
def self_test() -> int:
    # (summary, provider_has_main_branch, expect_owed, expect_failing, why)
    # `can_publish` is exercised separately below against the real repo, because it is a
    # filesystem fact rather than a branch of this arithmetic.
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

    # can_publish_verification in BOTH directions, against SYNTHETIC fixtures.
    #
    # This used to assert on the fleet's own population — that some provider could publish
    # and some could not. That caught what it was built to catch and then went red for the
    # right reason: #3232 added the @PactBroker half to all eight folder-only providers, so
    # the "some cannot" half became false by being FIXED. An assertion whose truth depends
    # on the fleet still having the defect is an assertion that must be edited every time
    # someone improves things, and editing it under time pressure is how it ends up deleted.
    # Fixtures do not have that problem: they test the function, not the estate.
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        tmpp = pathlib.Path(tmp)
        for name, filename, ann in (
            ("prov-broker", "TProviderVerificationTest.kt", "@PactBroker"),
            ("prov-broker-off-filter", "TPactBrokerProviderTest.kt", "@PactBroker"),
            ("prov-folder", "TProviderVerificationTest.kt", '@PactFolder("../pacts")'),
        ):
            d = tmpp / name / "src" / "test" / "kotlin"
            d.mkdir(parents=True)
            (d / filename).write_text(f'@Provider("x")\n{ann}\nclass T\n')
        (tmpp / "prov-none" / "src" / "test").mkdir(parents=True)
        checks = [
            ("prov-broker", True, "@PactBroker provider is publishable"),
            ("prov-broker-off-filter", False, "@PactBroker class outside providerPactTest is NOT publishable"),
            ("prov-folder", False, "@PactFolder-only provider is NOT publishable"),
            ("prov-none", False, "provider with no test at all is NOT publishable"),
            ("prov-missing", False, "provider directory that does not exist"),
        ]
        for name, want, why in checks:
            got = can_publish_verification(tmpp, name)
            mark = "ok " if got == want else "BAD"
            print(f"  {mark} {why:48s} -> {got}")
            if got != want:
                bad.append(why)

    # And a light fleet sanity check: if NOTHING in the real repo can publish, the function
    # is answering False for everything and no verification would ever be dispatched — which
    # looks exactly like a healthy fleet.
    providers = sorted({p for _, p in edges})
    pub = [p for p in providers if can_publish_verification(root, p)]
    print(f"  {'ok ' if pub else 'BAD'} providers in this repo that can publish: {len(pub)}/{len(providers)}")
    if not pub:
        bad.append("no provider in the repo can publish — the check answers False for everything")

    print("\nself-test: unchanged failed inputs never redispatch; new inputs and manual retry work")
    provider = "openbank-ledger-service"
    key_a, key_b = "a" * 64, "b" * 64
    def run(name, status="completed", conclusion="failure", when="2026-09-25T02:00:00Z"):
        return {"display_title": name, "status": status, "conclusion": conclusion,
                "created_at": when, "html_url": "https://github.com/example/repo/actions/runs/42",
                "id": 42}
    auto_a = run(f"{RUN_NAME_PREFIX}{provider}:{key_a}:auto")
    auto_b = run(f"{RUN_NAME_PREFIX}{provider}:{key_b}:auto")
    manual = run(f"{RUN_NAME_PREFIX}{provider}:manual:manual")
    legacy = run("Verify one provider", when="2026-09-25T01:00:00Z")
    admission_cases = [
        ([auto_a], key_a, False, "unchanged terminal failure is held"),
        ([auto_a], key_b, True, "new pact or provider inputs are eligible"),
        ([auto_a | {"status": "in_progress", "conclusion": None}], key_b, False,
         "active provider run blocks duplicate work even on changed inputs"),
        ([manual | {"status": "queued", "conclusion": None}], key_b, False,
         "explicit manual retry can run, but reconciler does not duplicate it"),
        ([manual], key_b, True, "finished manual retry does not poison a new auto fingerprint"),
        ([legacy, auto_b], key_a, True, "older unattributed terminal legacy run is tolerated"),
        ([legacy | {"status": "in_progress", "conclusion": None}], key_b, False,
         "active unattributed legacy run fails closed"),
        ([legacy | {"created_at": "2026-09-25T03:00:00Z"}, auto_b], key_a, False,
         "unattributed run after named migration marker fails closed"),
        ([auto_a | {"conclusion": None}], key_a, False,
         "terminal run without a conclusion fails closed"),
        ([auto_a | {"display_title": "unknown title"}], key_b, False,
         "unrecognized run metadata fails closed"),
    ]
    for history, queried_key, expected, why in admission_cases:
        got, _ = admit_provider(provider, queried_key, history)
        print(f"  {'ok ' if got == expected else 'BAD'} {why}")
        if got != expected:
            bad.append(why)

    # A new consumer app version with unchanged Pact CONTENT must not restart a
    # failed verification. Pact itself inherits results for identical content.
    consumer, sample_provider = edges[0]
    def broker_fixture(number, interaction="unchanged"):
        def answer(url, *_):
            if url.endswith("latest-version"):
                return {"number": number}
            return {"consumer": {"name": consumer}, "provider": {"name": sample_provider},
                    "interactions": [{"description": interaction}],
                    "_links": {"self": {"href": url}}}
        return answer
    key1 = verification_key(root, sample_provider, [consumer], "https://broker.example", "", "", "main", broker_fixture("1"))
    key1_again = verification_key(root, sample_provider, [consumer], "https://broker.example", "", "", "main", broker_fixture("1"))
    key2 = verification_key(root, sample_provider, [consumer], "https://broker.example", "", "", "main", broker_fixture("2"))
    key_changed = verification_key(root, sample_provider, [consumer], "https://broker.example", "", "", "main", broker_fixture("2", "new interaction"))
    stable_trees = lambda _root, path: path
    testing_fixed = lambda _root, path: path + ("-fixed" if path == "openbank-libs-testing" else "")
    shared_before = verification_key(root, sample_provider, [consumer], "https://broker.example", "", "", "main", broker_fixture("1"), stable_trees)
    shared_after = verification_key(root, sample_provider, [consumer], "https://broker.example", "", "", "main", broker_fixture("1"), testing_fixed)
    fingerprint_checks = [
        ("unchanged verification inputs produce the same key", key1 == key1_again),
        ("new app version with identical Pact content keeps the key", key1 == key2),
        ("changed broker Pact content changes the key", key1 != key_changed),
        ("shared testing code change resets a failed-run key", shared_before != shared_after),
    ]
    try:
        verification_key(root, sample_provider, [consumer], "https://broker.example", "", "", "main", broker_fixture(None))
    except ValueError:
        fingerprint_checks.append(("missing broker version fails closed", True))
    else:
        fingerprint_checks.append(("missing broker version fails closed", False))
    for why, okay in fingerprint_checks:
        print(f"  {'ok ' if okay else 'BAD'} {why}")
        if not okay:
            bad.append(why)

    # The API count, not a short first page, defines complete history. Failure
    # here must stop every POST rather than treat a missing older failure as fresh.
    history_checks = []
    one_page = complete_run_history("org/repo", "verify-provider.yml", "x", fetch=lambda *_: {
        "total_count": 1, "workflow_runs": [auto_a],
    })
    history_checks.append(("complete one-page history is accepted", len(one_page) == 1))
    page_one = [run(f"{RUN_NAME_PREFIX}{provider}:{key_a}:auto") | {"id": i}
                for i in range(1, HISTORY_PAGE_SIZE + 1)]
    def history_fixture(url, _token, second_count=101, second_id=101):
        page_number = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)["page"][0]
        if page_number == "1":
            return {"total_count": 101, "workflow_runs": page_one.copy()}
        return {"total_count": second_count,
                "workflow_runs": [run(f"{RUN_NAME_PREFIX}{provider}:{key_a}:auto") | {"id": second_id}]}
    complete = complete_run_history("org/repo", "verify-provider.yml", "x",
                                    fetch=history_fixture)
    history_checks.append(("two stable pages are accepted", len(complete) == 101))
    for why, fetcher in (
        ("run inserted between pages fails closed",
         lambda url, token: history_fixture(url, token, second_count=102)),
        ("overlapping pages fail closed",
         lambda url, token: history_fixture(url, token, second_id=100)),
    ):
        try:
            complete_run_history("org/repo", "verify-provider.yml", "x", fetch=fetcher)
        except ValueError:
            history_checks.append((why, True))
        else:
            history_checks.append((why, False))
    first_page_reads = 0
    def shifted_first_page(url, token):
        nonlocal first_page_reads
        answer = history_fixture(url, token)
        if urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)["page"] == ["1"]:
            first_page_reads += 1
            if first_page_reads == 2:
                answer["workflow_runs"][0] = answer["workflow_runs"][0] | {"id": 999}
        return answer
    try:
        complete_run_history("org/repo", "verify-provider.yml", "x", fetch=shifted_first_page)
    except ValueError:
        history_checks.append(("first page changed during scan fails closed", True))
    else:
        history_checks.append(("first page changed during scan fails closed", False))
    try:
        complete_run_history("org/repo", "verify-provider.yml", "x", fetch=lambda url, _token: {
            "total_count": 2,
            "workflow_runs": [auto_a] if urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)["page"] == ["1"] else [],
        })
    except ValueError:
        history_checks.append(("missing second page fails closed", True))
    else:
        history_checks.append(("missing second page fails closed", False))
    try:
        complete_run_history("org/repo", "verify-provider.yml", "x", fetch=lambda *_: {
            "total_count": HISTORY_PAGE_SIZE * HISTORY_MAX_PAGES + 1, "workflow_runs": [],
        })
    except ValueError:
        history_checks.append(("history beyond the hard cap fails closed", True))
    else:
        history_checks.append(("history beyond the hard cap fails closed", False))
    for why, okay in history_checks:
        print(f"  {'ok ' if okay else 'BAD'} {why}")
        if not okay:
            bad.append(why)

    # ---- The dispatch-failure classification (#9750).
    # The load-bearing asymmetry: a quota answer must DEFER (the debt survives to the next
    # run), a real answer must FAIL THIS RUN. Both directions are asserted, because a
    # classifier that defers everything makes this workflow green about a dispatch that can
    # never succeed — a token without `actions: write` would then strand pacts silently,
    # which is the exact failure this reconciler exists to make visible.
    print("\nself-test: dispatch-failure classification")
    dispatch_cases = [
        ("HTTP 403 b'{\"message\": \"API rate limit exceeded for installation ID 1.\"}'", True,
         "installation rate limit DEFERS — this was 11 of the last 60 scheduled runs"),
        ("You have exceeded a secondary rate limit. Please wait a few minutes.", True,
         "secondary rate limit DEFERS"),
        ("HTTP 502 b'Bad gateway'", True, "a 5xx DEFERS"),
        ("<urlopen error [Errno 104] Connection reset by peer>", True, "a transport failure DEFERS"),
        ("HTTP 404 b'{\"message\": \"Not Found\"}'", False,
         "a missing workflow FAILS — retrying cannot create verify-provider.yml"),
        ("HTTP 422 b'{\"message\": \"Reference does not exist\"}'", False,
         "a bad ref FAILS"),
        ("HTTP 403 b'{\"message\": \"Resource not accessible by integration\"}'", False,
         "a permission denial FAILS — the one message that must not read as quota"),
        ("HTTP 401 b'{\"message\": \"Bad credentials\"}'", False, "bad credentials FAIL"),
    ]
    for msg, want_defer, why in dispatch_cases:
        seen = []
        got = _defer("prov", msg, seen)
        okmark = "ok " if (got == want_defer and bool(seen) == want_defer) else "BAD"
        print(f"  {okmark} {why}")
        if okmark == "BAD":
            bad.append(why)

    # A broker rejection must NAME the edge it rejected. The old warning rendered
    # `HTTPError` directly, which is "HTTP Error 400: Bad Request" and nothing else — a status
    # with no subject, which is why #9776 could not be acted on for two days. Equally, the
    # message must not carry the broker HOST: PACT_BROKER_URL is a secret and these logs are
    # public. Both halves are asserted here, and the second is the one that regresses quietly.
    print("\nself-test: broker-error message is diagnosable and host-free")
    probe_url = "https://broker.internal.example/matrix?q[][pacticipant]=consumer-x&latestby=cvpv"
    rendered = broker_error_message(
        "Bad Request", probe_url, '{"error":"unknown pacticipant consumer-x"}',
    )
    checks = [
        ("names the path", "/matrix" in rendered),
        ("keeps the selector that was rejected", "q[][pacticipant]=consumer-x" in rendered),
        ("carries the broker's own reason", "unknown pacticipant" in rendered),
        ("does NOT leak the broker host", "broker.internal.example" not in rendered),
        ("does NOT leak the scheme", "https://" not in rendered),
    ]
    for why, okay in checks:
        print(f"  {'ok ' if okay else 'BAD'} {why}")
        if not okay:
            bad.append(why)

    # Truncation must be a bound, not a silent drop: a huge body still has to say it was cut.
    long_detail = "x" * (HTTP_ERROR_DETAIL_CHARS + 50)
    truncated = broker_error_message("Bad Request", probe_url, long_detail)
    trunc_ok = truncated.endswith("\u2026") and long_detail not in truncated
    print(f"  {'ok ' if trunc_ok else 'BAD'} a long body is truncated and marked as truncated")
    if not trunc_ok:
        bad.append("truncation")

    # #9776: "Pacticipant X not found" is never-published, not a broker failure — but ONLY for
    # this exact sentence about exactly one side of this edge.
    print("\nself-test: an unknown pacticipant is classified, anything else stays an error")
    def http_err(code, body):
        return urllib.error.HTTPError(
            "https://b/matrix", code, broker_error_message("Bad Request", "https://b/matrix?q", body), {}, None,
        )
    live = '{"errors":["Pacticipant openbank-case-coordinator-agent not found"]}'
    classify = [
        (http_err(400, live), "openbank-case-coordinator-agent",
         "the live #9776 answer names the PROVIDER as never published"),
        (http_err(400, '{"errors":["Pacticipant openbank-admin-ui not found"]}'), "openbank-admin-ui",
         "a missing CONSUMER is named as the consumer"),
        (http_err(400, '{"errors":["Pacticipant openbank-case-coordinator-agent-v2 not found"]}'), None,
         "a name that merely CONTAINS the provider is not this edge"),
        (http_err(400, '{"errors":["Version 1.2.3 not found"]}'), None,
         "a different 'not found' stays an error"),
        # The discriminating case: a DIFFERENT sentence that still ends with an edge side's name
        # right before "not found". Without the "Pacticipant " anchor this would be misread as a
        # never-published provider and silently stop counting a real broker fault.
        (http_err(400, '{"errors":["Branch main for openbank-case-coordinator-agent not found"]}'), None,
         "an edge name in a non-Pacticipant sentence stays an error"),
        (http_err(500, live), None, "the same sentence on a 5xx stays an error — a fault is a fault"),
        (urllib.error.URLError("Connection refused"), None, "a transport failure stays an error"),
    ]
    for err, want, why in classify:
        got = missing_pacticipant(err, "openbank-admin-ui", "openbank-case-coordinator-agent")
        okmark = "ok " if got == want else "BAD"
        print(f"  {okmark} {why:70s} -> {got}")
        if got != want:
            bad.append(why)

    # The floor run-gates holds this gate to: every decision case evaluated above. An emptied
    # table must not pass as a clean one.
    gatelib.subjects(len(cases) + len(checks) + len(dispatch_cases) + 1 + len(classify)
                     + len(admission_cases) + len(fingerprint_checks) + len(history_checks))
    if bad:
        print("\n::error::self-test FAILED: " + "; ".join(bad))
        return 1
    print("\nself-test: every decision branch classified correctly.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
