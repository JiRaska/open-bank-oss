#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Gate: every client whose service account a policy names must issue `preferred_username`
# (issue #10486).
#
# WHY
#   A rego rule keyed on `input.principal.id == "service-account-<client>"` matches the
#   principal NAME, and that name reaches the policy from the token's `preferred_username`
#   claim. Keycloak puts that claim in a client_credentials token only through the `profile`
#   client scope. A client whose template `defaultClientScopes` omit `profile` therefore mints
#   tokens with no principal name, and every identity-gated rule written for it can never
#   match — it denies (or, for an exclusion rule, fails to exclude) silently.
#   `openbank-edge` was in that state in realm-template.json: the running realm happened to
#   carry the scope, so nothing failed, but a cold-started realm (`--import-realm`) would have
#   issued nameless edge tokens and 91 rego references to that principal would have gone dead.
#
# WHAT IT CHECKS
#   Every `service-account-<x>` literal in a non-test `.rego` file must name a client that
#   (a) exists in some realm template under
#       openbank-infra/gitops/components/keycloak/*realm-template*.json, and
#   (b) lists `profile` in that client's `defaultClientScopes`.
#   `_test.rego` files are excluded: their principals are fixtures (several name clients no
#   realm has, by design, to prove a rule does NOT admit them).
#
# Run:  python3 .github/scripts/check-rego-service-account-profile-scope.py [--root .]
#       python3 .github/scripts/check-rego-service-account-profile-scope.py --self-test

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gatelib  # noqa: E402

REALM_GLOB = "openbank-infra/gitops/components/keycloak/*realm-template*.json"
SA_RE = re.compile(r"service-account-([a-z0-9][a-z0-9-]*[a-z0-9])")
SKIP_DIRS = {".git", "node_modules", "build", ".gradle"}


def rego_references(root: pathlib.Path) -> dict:
    """{clientId: [file, ...]} for every service-account literal in non-test rego."""
    refs: dict = {}
    for p in sorted(root.rglob("*.rego")):
        if p.name.endswith("_test.rego") or SKIP_DIRS & set(p.relative_to(root).parts):
            continue
        for m in SA_RE.finditer(p.read_text()):
            refs.setdefault(m.group(1), set()).add(str(p.relative_to(root)))
    return {k: sorted(v) for k, v in refs.items()}


def template_clients(root: pathlib.Path) -> dict:
    """{clientId: [(template file, defaultClientScopes or None), ...]}"""
    out: dict = {}
    for p in sorted(root.glob(REALM_GLOB)):
        for c in json.loads(p.read_text()).get("clients", []) or []:
            out.setdefault(c.get("clientId"), []).append(
                (str(p.relative_to(root)), c.get("defaultClientScopes")))
    return out


def evaluate(refs: dict, clients: dict) -> list:
    findings = []
    for client, files in sorted(refs.items()):
        where = ", ".join(files[:3]) + (f" (+{len(files) - 3} more)" if len(files) > 3 else "")
        if client not in clients:
            findings.append(
                f"`service-account-{client}` is named by {where} but no realm template defines "
                f"client `{client}` — the rule can never match a real token")
            continue
        for tpl, scopes in clients[client]:
            if "profile" not in (scopes or []):
                findings.append(
                    f"{tpl}: client `{client}` lacks `profile` in defaultClientScopes "
                    f"({scopes}) — its tokens carry no preferred_username, so the "
                    f"principal.id-gated rules in {where} never match on a cold-started realm")
    return findings


def self_test() -> int:
    cases = [
        ("clean", {"a": ["x.rego"]}, {"a": [("t", ["openid", "profile"])]}, 0),
        ("missing profile", {"a": ["x.rego"]}, {"a": [("t", ["openid", "roles"])]}, 1),
        ("null scopes", {"a": ["x.rego"]}, {"a": [("t", None)]}, 1),
        ("unknown client", {"ghost": ["x.rego"]}, {}, 1),
        ("unreferenced client w/o profile is fine", {}, {"a": [("t", ["openid"])]}, 0),
    ]
    failed = 0
    for name, refs, clients, want in cases:
        got = len(evaluate(refs, clients))
        if got != want:
            failed += 1
            print(f"::error::self-test FAILED: {name}: want {want} finding(s), got {got}")
    m = SA_RE.findall('x == "service-account-openbank-edge"\nstartswith(id, "service-account-")')
    if m != ["openbank-edge"]:
        failed += 1
        print(f"::error::self-test FAILED: regex matched {m}")
    print(f"self-test: {len(cases) + 1 - failed}/{len(cases) + 1} passed")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    root = pathlib.Path(args.root).resolve()
    refs, clients = rego_references(root), template_clients(root)
    gatelib.subjects(len(refs), "service-account principals referenced by rego")
    if not clients:
        print(f"::error::no realm template under {REALM_GLOB} — cannot run blind")
        return 1
    findings = evaluate(refs, clients)
    for f in findings:
        print(f"::error::{f}")
    if findings:
        return 1
    print(f"check-rego-service-account-profile-scope: OK — {len(refs)} referenced client(s) "
          f"all issue `profile`: {sorted(refs)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
