#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Gate: every Keycloak client whose secret is a template placeholder names where that secret lives
# (issue #3246).
#
# WHY THIS EXISTS
#   The realm Keycloak rebuilds from is the OpenBao import artifact, not the committed template,
#   and nothing derives one from the other. Twice in one week (2026-11-10, 2026-11-15) a PR added a
#   client to realm-template.json, merged green, and the nightly keycloak-realm-drift CronJob went
#   red the next morning because the artifact lacked it: a cold-started realm would have come up
#   without that client. The drift job DETECTS that a day late; nothing at PR time even said there
#   was a second step. This gate is that moment: a new placeholder client must state its OpenBao
#   secret source in openbank-libs/governance/keycloak-realm-import-secret-sources.yaml, in the same PR, and the failure message
#   names the operator command that puts it into the artifact.
#
# WHAT IT CHECKS (static; no cluster, no OpenBao read)
#   * every template client with a `__PLACEHOLDER__` secret has an entry in its realm;
#   * the entry's placeholder equals the template's (a renamed placeholder is caught);
#   * an entry is either `openbao: keycloak/<path>` or `unrecorded: <reason >= 20 chars>`;
#   * no entry names a client the template no longer declares (stale entries rot the mapping);
#   * no two clients name the same OpenBao path (a shared path means one mapping is wrong);
#   * `unrecorded` is a closed set: it may only shrink. A NEW unrecorded entry is a finding —
#     a new client always knows where its secret is, because someone just created it.
#
# WHAT IT DOES NOT DO
#   It cannot see OpenBao, so it cannot prove the artifact was updated; the nightly drift job
#   remains the proof. It does not compare secret values.
import ast
import json
import os
import re
import subprocess
import sys
from pathlib import Path

import yaml

TEMPLATES = {
    "openbank": "openbank-infra/gitops/components/keycloak/realm-template.json",
    "openbank-customers": "openbank-infra/gitops/components/keycloak/customers-realm-template.json",
}
SOURCES = "openbank-libs/governance/keycloak-realm-import-secret-sources.yaml"
PLACEHOLDER = re.compile(r"^__[A-Z0-9_]+__$")
OPENBAO = re.compile(r"^keycloak/[a-z0-9][a-z0-9-]*$")

# Clients whose secret source was never recorded when this gate was introduced (2026-11-15).
# This set may only shrink: record the source and delete the name here.
UNRECORDED_BASELINE = {
    "openbank": {
        "agent-merge-recorder-ci", "argocd", "goalert", "openbank-admin-ui",
        "openbank-delegation-disclosure", "openbank-edge", "openbank-glitchtip",
        "openbank-services", "openbank-synthetic-ledger-seed", "openbao", "openbank-mcp-service",
    },
    "openbank-customers": {"customer-edge-admin", "openbank-edge-webauthn"},
}

HINT = ("Add `{cid}` to {src} under `{realm}:` with `placeholder: {ph}` and `openbao: keycloak/<path>`. "
        "After merge, an operator must also add the client to the OpenBao import artifact "
        "(docs/runbooks/0009-keycloak-realm-import-reconcile.md, additive merge); until then the "
        "nightly keycloak-realm-drift job reports it.")


def confidential_without_placeholder(template: dict) -> list:
    """Confidential clients whose secret is not a placeholder: a literal or missing secret would
    reach neither this mapping nor the import artifact by name, and must not pass silently."""
    out = []
    for c in template.get("clients", []):
        if c.get("publicClient") or c.get("bearerOnly"):
            continue
        if c.get("clientAuthenticatorType", "client-secret") != "client-secret":
            continue
        s = c.get("secret")
        if not (isinstance(s, str) and PLACEHOLDER.match(s)):
            out.append(c["clientId"])
    return out


def baseline_on_base() -> dict | None:
    """UNRECORDED_BASELINE as committed on the PR base, or None when no base is known."""
    base = os.environ.get("PR_DIFF_BASE", "").strip()
    if not base:
        return None
    shown = subprocess.run(["git", "show", f"{base}:.github/scripts/check-realm-import-secret-sources.py"],
                           capture_output=True, text=True, check=False)
    if shown.returncode != 0:
        return {}  # the script is new on this PR: its whole baseline is the introduction
    # Read the literal only; never execute code from another revision.
    for node in ast.parse(shown.stdout).body:
        if isinstance(node, ast.Assign) and any(getattr(t, "id", None) == "UNRECORDED_BASELINE" for t in node.targets):
            return {k: set(v) for k, v in ast.literal_eval(node.value).items()}
    return {}


def baseline_growth(current: dict, base: dict | None) -> list:
    if not base:
        return []
    return [f"[{realm}] `{cid}` was ADDED to UNRECORDED_BASELINE; the baseline only shrinks — record its source"
            for realm, names in current.items() for cid in sorted(names - base.get(realm, set()))]


def placeholder_clients(template: dict) -> dict:
    out = {}
    for c in template.get("clients", []):
        s = c.get("secret")
        if isinstance(s, str) and PLACEHOLDER.match(s):
            out[c["clientId"]] = s
    return out


def check(templates: dict, sources: dict, baseline: dict) -> list:
    findings = []
    if not isinstance(sources, dict):
        return [f"{SOURCES}: top level must be a mapping of realm -> clients"]
    for realm, template in templates.items():
        want = placeholder_clients(template)
        have = sources.get(realm) or {}
        if not isinstance(have, dict):
            findings.append(f"{SOURCES}: `{realm}` must be a mapping")
            continue
        for cid, ph in sorted(want.items()):
            entry = have.get(cid)
            if entry is None:
                findings.append(f"[{realm}] client `{cid}` has secret placeholder {ph} but no secret source. "
                                + HINT.format(cid=cid, src=SOURCES, realm=realm, ph=ph))
                continue
            if not isinstance(entry, dict) or entry.get("placeholder") != ph:
                findings.append(f"[{realm}] `{cid}`: placeholder must be {ph} (template), "
                                f"got {entry.get('placeholder') if isinstance(entry, dict) else entry!r}")
                continue
            ob, un = entry.get("openbao"), entry.get("unrecorded")
            if (ob is None) == (un is None):
                findings.append(f"[{realm}] `{cid}`: exactly one of `openbao` or `unrecorded` is required")
            elif ob is not None and not (isinstance(ob, str) and OPENBAO.match(ob)):
                findings.append(f"[{realm}] `{cid}`: openbao must be `keycloak/<path>`, got {ob!r}")
            elif un is not None:
                if not (isinstance(un, str) and len(un.strip()) >= 20):
                    findings.append(f"[{realm}] `{cid}`: unrecorded needs a reason of at least 20 characters")
                if cid not in baseline.get(realm, set()):
                    findings.append(f"[{realm}] `{cid}`: a NEW client cannot be `unrecorded` — whoever created "
                                    "it knows where its secret is. " + HINT.format(cid=cid, src=SOURCES, realm=realm, ph=ph))
        seen = {}
        for cid, e in sorted(have.items()):
            ob = e.get("openbao") if isinstance(e, dict) else None
            if ob and ob in seen:
                findings.append(f"[{realm}] `{cid}` and `{seen[ob]}` both name {ob}: two clients never share one "
                                "secret, so at least one source is wrong")
            elif ob:
                seen[ob] = cid
        for cid in sorted(set(have) - set(want)):
            findings.append(f"[{realm}] `{cid}` is listed in {SOURCES} but the template no longer declares it "
                            "with a placeholder secret — remove the entry")
    for realm, names in baseline.items():
        recorded = {cid for cid, e in (sources.get(realm) or {}).items()
                    if isinstance(e, dict) and e.get("openbao")}
        for cid in sorted(names & recorded):
            findings.append(f"[{realm}] `{cid}` now has a recorded source; delete it from UNRECORDED_BASELINE "
                            "in this script (the baseline only shrinks)")
        gone = names - set(placeholder_clients(templates[realm]))
        for cid in sorted(gone):
            findings.append(f"[{realm}] `{cid}` is in UNRECORDED_BASELINE but no longer in the template — delete it")
    return findings


def self_test() -> int:
    tpl = {"openbank": {"clients": [{"clientId": "a", "secret": "__A__"}, {"clientId": "old", "secret": "__OLD__"},
                                    {"clientId": "pub", "secret": "plain"}]}}
    base = {"openbank": {"old"}}
    ok = {"openbank": {"a": {"placeholder": "__A__", "openbao": "keycloak/a"},
                       "old": {"placeholder": "__OLD__", "unrecorded": "predates the mapping (#3246) x"}}}
    cases = [
        ("clean mapping passes", ok, 0),
        ("new client without a source fails", {"openbank": {"old": ok["openbank"]["old"]}}, 1),
        ("wrong placeholder fails", {"openbank": {**ok["openbank"], "a": {"placeholder": "__B__", "openbao": "keycloak/a"}}}, 1),
        ("new client as unrecorded fails", {"openbank": {**ok["openbank"], "a": {"placeholder": "__A__", "unrecorded": "we do not know where it is"}}}, 1),
        ("bad openbao path fails", {"openbank": {**ok["openbank"], "a": {"placeholder": "__A__", "openbao": "secret/a"}}}, 1),
        ("both fields fails", {"openbank": {**ok["openbank"], "a": {"placeholder": "__A__", "openbao": "keycloak/a", "unrecorded": "x" * 25}}}, 1),
        ("stale entry fails", {"openbank": {**ok["openbank"], "gone": {"placeholder": "__G__", "openbao": "keycloak/g"}}}, 1),
        ("two clients sharing one openbao path fails", {"openbank": {**ok["openbank"], "old": {"placeholder": "__OLD__", "openbao": "keycloak/a"}}}, 1),
        ("recorded baseline entry must leave the baseline", {"openbank": {**ok["openbank"], "old": {"placeholder": "__OLD__", "openbao": "keycloak/old"}}}, 1),
    ]
    bad = 0
    for name, sources, want in cases:
        got = len(check(tpl, sources, base))
        passed = (got == 0) if want == 0 else (got >= 1)
        print(f"self-test {'ok  ' if passed else 'FAIL'} {name} ({got} finding(s))")
        bad += not passed
    for name, got, want in (
        ("baseline addition is a finding", len(baseline_growth({"openbank": {"x", "y"}}, {"openbank": {"x"}})), 1),
        ("baseline shrink is fine", len(baseline_growth({"openbank": {"x"}}, {"openbank": {"x", "y"}})), 0),
        ("no base known is not judged", len(baseline_growth({"openbank": {"x"}}, None)), 0),
        ("confidential literal secret is flagged",
         len(confidential_without_placeholder({"clients": [{"clientId": "c", "secret": "s3cret"}]})), 1),
        ("public client is ignored",
         len(confidential_without_placeholder({"clients": [{"clientId": "p", "publicClient": True}]})), 0),
    ):
        passed = got == want
        print(f"self-test {'ok  ' if passed else 'FAIL'} {name} ({got})")
        bad += not passed
    print("self-test:", "all cases OK" if not bad else f"{bad} case(s) FAILED")
    return 1 if bad else 0


def main() -> int:
    if "--self-test" in sys.argv:
        return self_test()
    templates = {r: json.loads(Path(p).read_text()) for r, p in TEMPLATES.items()}
    sources = yaml.safe_load(Path(SOURCES).read_text()) or {}
    findings = check(templates, sources, UNRECORDED_BASELINE)
    findings += baseline_growth(UNRECORDED_BASELINE, baseline_on_base())
    for realm, t in templates.items():
        for cid in confidential_without_placeholder(t):
            findings.append(f"[{realm}] confidential client `{cid}` has no `__PLACEHOLDER__` secret: give it one "
                            "(the import artifact carries the real value) so its source can be recorded here")
        ids = [c["clientId"] for c in t.get("clients", [])]
        for cid in sorted({i for i in ids if ids.count(i) > 1}):
            findings.append(f"[{realm}] clientId `{cid}` is declared more than once in the template")
    n = sum(len(placeholder_clients(t)) for t in templates.values())
    print(f"SUBJECTS={n}")
    for f in findings:
        print(f"::error file={SOURCES}::{f}")
    print(f"check-realm-import-secret-sources: {n} placeholder client(s), {len(findings)} finding(s).")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
