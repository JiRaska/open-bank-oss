#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# Re-drive record-deployment when the broker's sandbox record disagrees with the gitops pin.
#
# THE FAILURE THIS CATCHES
# record-deployment-on-merge.yml runs ONCE, when a gitops PR merges. For each pin it asks
# resolve-record-deployment-version.sh which broker version to record, and on `none` it records
# nothing and moves on — leaving the PREVIOUS record current. That is correct at that instant and
# wrong forever after, because the version it needed usually arrives LATER: a shared-library push
# fans out to the whole fleet, auto-deploy builds and its gitops PR merges, and the same push's
# Services CI contract jobs publish the provider versions afterwards (they queue behind the same
# fan-out). Measured 2026-10-02 on the #11770 fan-out (85a0be3e): record-deployment ran at ~01:50Z
# and found no version; the broker's openbank-ledger-service version 85a0be3e was created at
# 06:22Z by that very push's Services CI run. Nothing re-asked. At that point 14 providers were
# deployed at a sha the broker HAD, while the environment record still named a 7ccd9bde/25bde9f5-era
# version — card-processing's can-i-deploy was blocked against a card-issuance version that was no
# longer running.
#
# Same shape as auto-deploy-reconcile-lag.sh (#2020) — a one-shot step racing a later event —
# on the recording side instead of the deploying side. pact-verification-reconcile.yml already
# polls the broker every 30 minutes but asks only "is the consumer's latest MAIN pact verified by
# the provider's latest MAIN version"; no control compared the environment record with gitops.
#
# WHAT IT DOES
#   1. pins    = every `openbank-*:sandbox-<sha>` in openbank-infra/gitops/components (one per svc)
#   2. records = the broker's currently-deployed versions in `sandbox`
#   3. drift   = pacticipants with a pact edge in pacts/*.json whose record does not match the pin
#   4. for each drift, ask the SAME resolver record-deployment uses (one definition of equivalence):
#        exact/equivalent, different from the record  -> RECORDABLE
#        exact/equivalent, equal to the record        -> already right (an equivalent record)
#        none                                         -> OWED: the deployed tree has no published
#                                                        version; reported, not dispatched
#   5. --dispatch: ONE record-deployment-on-merge.yml workflow_dispatch for all recordable services.
#      That workflow re-resolves from gitops on main, so this never chooses a version itself.
#
# WHY OWED IS NOT DISPATCHED
# Publishing a deployed tree is a full provider build (verify-provider.yml) on the self-hosted pool;
# a fleet fan-out would turn every tick into dozens of builds. It is printed as a ::warning:: with
# the exact command instead. Recording is one cheap API-only job and is safe to automate.
#
# SCOPE: only pacticipants that appear in a committed pact. A pacticipant with no edge has no
# can-i-deploy that reads its record, so drift there is inert — and asking the resolver about it
# every tick would spend GitHub API calls on nothing.
# ---------------------------------------------------------------------------------------
import argparse
import base64
import json
import os
import pathlib
import re
import subprocess
import sys
import urllib.request

PIN_RE = re.compile(r"/(openbank-[a-z0-9-]+):sandbox-([0-9a-f]{7,40})\b")
RECORD_WORKFLOW = "record-deployment-on-merge.yml"


# ── pure core (self-tested) ─────────────────────────────────────────────────────────────
def pins_from_text(texts):
    """{svc: set(short)} from manifest texts."""
    pins = {}
    for t in texts:
        for svc, sha in PIN_RE.findall(t):
            pins.setdefault(svc, set()).add(sha)
    return pins


def pact_pacticipants(names):
    """Every pacticipant named in a `<consumer>-<provider>.json` pact filename."""
    out = set()
    for n in names:
        stem = n[:-5] if n.endswith(".json") else n
        # Split at the second `openbank-`: both sides are `openbank-*` names.
        i = stem.find("-openbank-")
        if stem.startswith("openbank-") and i > 0:
            out.add(stem[:i])
            out.add(stem[i + 1:])
    return out


def pact_providers(names):
    """The provider side of every `<consumer>-<provider>.json` pact filename."""
    out = set()
    for n in names:
        stem = n[:-5] if n.endswith(".json") else n
        i = stem.find("-openbank-")
        if stem.startswith("openbank-") and i > 0:
            out.add(stem[i + 1:])
    return out


def drift(pins, records, edged):
    """(svc, pin) for each edged pacticipant whose broker record does not match its single pin.

    A service with no record is NOT drift here: there is no stale claim to correct, and the
    merge-time path already warned about it. A service with several pins is ambiguous and is
    returned separately so it is reported rather than guessed.
    """
    stale, ambiguous = [], []
    for svc in sorted(edged):
        if svc not in records or svc not in pins:
            continue
        p = pins[svc]
        if len(p) != 1:
            ambiguous.append(svc)
            continue
        short = next(iter(p))
        if not records[svc].startswith(short):
            stale.append((svc, short))
    return stale, ambiguous


def classify(resolver_out, recorded):
    """Map the resolver's one-line answer to recordable / current / owed / error."""
    kind, _, ver = resolver_out.strip().partition(":")
    if kind in ("exact", "equivalent") and re.fullmatch(r"[0-9a-f]{40}", ver):
        return "current" if ver == recorded else "recordable"
    if resolver_out.strip() == "none":
        return "owed"
    return "error"


# ── I/O ─────────────────────────────────────────────────────────────────────────────────
def broker_json(base, path, user, password):
    req = urllib.request.Request(base.rstrip("/") + path)
    if user:
        req.add_header("Authorization", "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode())
    req.add_header("Accept", "application/hal+json")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())


def broker_records(base, user, password, env="sandbox"):
    envs = broker_json(base, "/environments", user, password)["_embedded"]["environments"]
    uuid = next(e["uuid"] for e in envs if e["name"] == env)
    dv = broker_json(base, f"/environments/{uuid}/deployed-versions/currently-deployed", user, password)
    recs = {}
    for d in dv["_embedded"]["deployedVersions"]:
        recs[d["_embedded"]["pacticipant"]["name"]] = d["_embedded"]["version"]["number"]
    return recs


def full_sha(repo, short):
    r = subprocess.run(["gh", "api", f"repos/{repo}/commits/{short}", "--jq", ".sha"],
                       capture_output=True, text=True)
    sha = r.stdout.strip()
    return sha if re.fullmatch(r"[0-9a-f]{40}", sha) else ""


def dispatch_record(repo, services, token):
    url = f"https://api.github.com/repos/{repo}/actions/workflows/{RECORD_WORKFLOW}/dispatches"
    body = json.dumps({"ref": "main", "inputs": {"services": " ".join(services)}}).encode()
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.status


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--dispatch", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    if a.self_test:
        return self_test()

    root = pathlib.Path(a.root).resolve()
    base = os.environ.get("PACT_BROKER_URL", "")
    user = os.environ.get("PACT_BROKER_USERNAME", "")
    pw = os.environ.get("PACT_BROKER_PASSWORD", "")
    repo = os.environ.get("GH_REPO") or os.environ.get("GITHUB_REPOSITORY", "")
    if not base or not repo:
        print("::error::PACT_BROKER_URL and GH_REPO are required — refusing to report a check that did not run")
        return 2

    texts = [p.read_text(errors="replace") for p in (root / "openbank-infra/gitops/components").rglob("*.yaml")]
    pins = pins_from_text(texts)
    edged = pact_pacticipants(p.name for p in (root / "pacts").glob("*.json"))
    if not pins or not edged:
        print(f"::error::found {len(pins)} gitops pins and {len(edged)} pact pacticipants — an empty side "
              "would make this reconciler a silent no-op")
        return 2
    records = broker_records(base, user, pw)
    stale, ambiguous = drift(pins, records, edged)
    for svc in ambiguous:
        print(f"::warning::{svc}: several sandbox pins in gitops ({sorted(pins[svc])}) — not reconciled")
    print(f"deployed-record reconcile: {len(edged)} pact pacticipants, {len(stale)} with a stale sandbox record")

    resolver = root / ".github/scripts/resolve-record-deployment-version.sh"
    recordable, owed, errors = [], [], 0
    shas = {}
    for svc, short in stale:
        if short not in shas:
            shas[short] = full_sha(repo, short)
        sha = shas[short]
        if not sha:
            print(f"::warning::{svc}: pin sandbox-{short} does not resolve to a commit — skipped")
            errors += 1
            continue
        out = subprocess.run(["bash", str(resolver), svc, sha], capture_output=True, text=True, cwd=root).stdout
        verdict = classify(out, records[svc])
        print(f"  {svc}: pinned {short}, recorded {records[svc][:8]}, resolver '{out.strip()[:50]}' -> {verdict}")
        if verdict == "recordable":
            recordable.append(svc)
        elif verdict == "owed":
            owed.append((svc, sha))
        elif verdict == "error":
            errors += 1
    providers = pact_providers(p.name for p in (root / "pacts").glob("*.json"))
    for svc, sha in owed:
        head = (f"::warning::{svc}: deployed sandbox-{sha[:8]} has no published (or provably equivalent) version, "
                f"and the broker still records {records[svc][:8]}.")
        if svc in providers:
            print(f"{head} Publish it, then re-record: gh workflow run verify-provider.yml -f service={svc} -f ref={sha}"
                  f" && gh workflow run {RECORD_WORKFLOW} -f services={svc}")
        else:
            # Consumer-only: verify-provider would publish this service's pacts under the dispatch's
            # GITHUB_SHA (the tip of main), not under `ref` — _service-ci.yml's consumer publish
            # step — and then fail for want of a provider test. So no dispatch publishes this sha.
            print(f"{head} Consumer-only: no dispatchable lane publishes a consumer version at a non-tip sha; "
                  f"it clears on its next deploy from a commit whose Services CI published its pacts.")
    if recordable:
        print(f"recordable now: {' '.join(recordable)}")
        if a.dispatch:
            token = os.environ.get("GITHUB_TOKEN", "")
            if not token:
                print("::error::--dispatch needs GITHUB_TOKEN")
                return 2
            status = dispatch_record(repo, recordable, token)
            print(f"dispatched {RECORD_WORKFLOW} services='{' '.join(recordable)}' (HTTP {status})")
        else:
            print(f"report only — to apply: gh workflow run {RECORD_WORKFLOW} -f services='{' '.join(recordable)}'")
    return 1 if errors else 0


# ── self-test ────────────────────────────────────────────────────────────────────────────
def self_test():
    fails = []

    def check(name, cond):
        print(("  ok   " if cond else "  FAIL ") + name)
        if not cond:
            fails.append(name)

    L = "a" * 32
    manifests = [
        "image: 123.dkr.ecr.eu-central-1.amazonaws.com/openbank-ledger-service:sandbox-85a0be3e\n",
        "image: x/openbank-card-issuance-service:sandbox-85a0be3e\nimage: x/openbank-fx-service:sandbox-25bde9f5\n",
        "image: x/openbank-two-pins:sandbox-11111111\n", "image: x/openbank-two-pins:sandbox-22222222\n",
        "image: x/openbank-no-edge:sandbox-33333333\n",
    ]
    pins = pins_from_text(manifests)
    check("pins: one per service read from manifests", pins["openbank-ledger-service"] == {"85a0be3e"})
    check("pins: two distinct pins are both kept (ambiguity is reported, not guessed)", len(pins["openbank-two-pins"]) == 2)

    edged = pact_pacticipants([
        "openbank-clearing-service-openbank-ledger-service.json",
        "openbank-card-processing-service-openbank-card-issuance-service.json",
        "openbank-fx-service-openbank-fraud-service.json",
        "openbank-two-pins-openbank-ledger-service.json",
    ])
    check("edges: both sides of a pact are pacticipants",
          {"openbank-clearing-service", "openbank-ledger-service", "openbank-card-issuance-service",
           "openbank-card-processing-service", "openbank-fx-service", "openbank-fraud-service"} <= edged)
    check("edges: a pin with no pact is not a pacticipant here", "openbank-no-edge" not in edged)
    provs = pact_providers(["openbank-fx-service-openbank-fraud-service.json"])
    check("providers: only the right-hand side is a provider", provs == {"openbank-fraud-service"})

    # KNOWN-POSITIVE: the measured 2026-10-02 state. Ledger and card-issuance run 85a0be3e while the
    # broker still records 7ccd9bde / 25bde9f5. The merge-time path left these behind; this must flag both.
    records = {
        "openbank-ledger-service": "7ccd9bde" + L,
        "openbank-card-issuance-service": "25bde9f5" + L,
        "openbank-fx-service": "25bde9f5" + L,           # matches its pin: NOT drift
        "openbank-two-pins": "11111111" + L,
        "openbank-no-edge": "00000000" + L,              # stale, but no edge: inert, NOT reported
    }
    stale, ambiguous = drift(pins, records, edged)
    check("drift: the measured 85a0be3e state is flagged for ledger",
          ("openbank-ledger-service", "85a0be3e") in stale)
    check("drift: ...and for card-issuance", ("openbank-card-issuance-service", "85a0be3e") in stale)
    check("drift: a record matching its pin is not drift", all(s != "openbank-fx-service" for s, _ in stale))
    check("drift: a service without an edge is out of scope", all(s != "openbank-no-edge" for s, _ in stale))
    check("drift: several pins are ambiguous, never guessed", ambiguous == ["openbank-two-pins"]
          and all(s != "openbank-two-pins" for s, _ in stale))
    check("drift: an edged pacticipant with no record is not drift",
          drift({"openbank-clearing-service": {"abcdef12"}}, {}, edged) == ([], []))

    F, R = "85a0be3e" + L, "7ccd9bde" + L
    check("classify: exact version now published -> recordable (the 06:22Z ledger case)", classify(f"exact:{F}\n", R) == "recordable")
    check("classify: an equivalent version other than the record -> recordable", classify(f"equivalent:{F}", R) == "recordable")
    check("classify: equivalent to what is ALREADY recorded -> current (no churn)", classify(f"equivalent:{R}", R) == "current")
    check("classify: none -> owed", classify("none\n", R) == "owed")
    check("classify: an empty answer is an error, never 'current'", classify("", R) == "error")
    check("classify: a non-sha version is an error", classify("exact:not-a-sha", R) == "error")

    if fails:
        print(f"pact-reconcile-deployed-records: self-test FAIL ({len(fails)})")
        return 1
    print("pact-reconcile-deployed-records: self-test PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
