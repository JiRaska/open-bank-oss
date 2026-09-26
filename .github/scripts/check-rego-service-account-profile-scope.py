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
#   Every `service-account-<x>` literal in a non-test `.rego` file that is USED AS A PRINCIPAL
#   IDENTITY must name a client that
#   (a) exists in some realm template under
#       openbank-infra/gitops/components/keycloak/*realm-template*.json, and
#   (b) lists `profile` in that client's `defaultClientScopes`.
#   "Used as a principal identity" means: compared with `input.principal.id` (`==`, `!=`, either
#   operand order), a member of an `input.principal.id in {...}` set, or a member of a named
#   identity-set constant (`NAME := {"service-account-..."}` / `[...]`). Comments and allow-REASON
#   names (`allowed_reasons contains "service-account-transaction-create"`) are NOT principals and
#   are ignored; the bare `startswith(id, "service-account-")` prefix has no client suffix.
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
SKIP_DIRS = {".git", "node_modules", "build", ".gradle"}
SA_LIT = r'"service-account-([a-z0-9][a-z0-9-]*[a-z0-9])"'
PID = r"(?:input\.)?principal\.id"
# principal.id == "sa-x" / != , and the mirrored operand order
CMP_RES = [re.compile(PID + r"\s*[!=]=\s*" + SA_LIT),
           re.compile(SA_LIT + r"\s*[!=]=\s*" + PID)]
# principal.id in {"sa-x", ...}  and  NAME := {"sa-x", ...} / [...]  (identity-set constants)
SET_RES = [re.compile(PID + r"\s+in\s+[\[{]([^\]}]*)[\]}]"),
           re.compile(r"^\s*[A-Za-z_]\w*\s*(?::=|=)\s*[\[{]([^\]}]*)[\]}]", re.MULTILINE)]
LIT_RE = re.compile(SA_LIT)


def strip_comments(text: str) -> str:
    """Drop `# ...` comments, honouring `#` inside a string literal."""
    out = []
    for line in text.splitlines():
        in_str, cut = False, len(line)
        for i, ch in enumerate(line):
            if ch == '"' and (i == 0 or line[i - 1] != "\\"):
                in_str = not in_str
            elif ch == "#" and not in_str:
                cut = i
                break
        out.append(line[:cut])
    return "\n".join(out)


def principal_clients(text: str) -> set:
    """clientIds the rego text uses as principal identities (never reasons or comments)."""
    code, found = strip_comments(text), set()
    for r in CMP_RES:
        found.update(m.group(1) for m in r.finditer(code))
    for r in SET_RES:
        for m in r.finditer(code):
            found.update(LIT_RE.findall(m.group(1)))
    return found


def rego_references(root: pathlib.Path) -> dict:
    """{clientId: [file, ...]} for every service-account literal in non-test rego."""
    refs: dict = {}
    for p in sorted(root.rglob("*.rego")):
        if p.name.endswith("_test.rego") or SKIP_DIRS & set(p.relative_to(root).parts):
            continue
        for c in principal_clients(p.read_text()):
            refs.setdefault(c, set()).add(str(p.relative_to(root)))
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
    edge = 'input.principal.id == "service-account-openbank-edge"\n'
    rego = {
        "reason name is not a principal": (
            'allowed_reasons contains "service-account-transaction-create" if {\n\tx\n}\n', set()),
        "comment is not a principal": ("# service-account-openbank-edge\n", set()),
        "prefix check has no client": ('startswith(input.principal.id, "service-account-")\n', set()),
        "principal comparison": (edge, {"openbank-edge"}),
        "negated comparison": (edge.replace("==", "!="), {"openbank-edge"}),
        "mirrored comparison": ('"service-account-openbank-edge" == input.principal.id\n',
                                {"openbank-edge"}),
        "inline set": ('input.principal.id in {"service-account-a-b", "service-account-c-d"}\n',
                       {"a-b", "c-d"}),
        "identity-set constant": ('CALLERS := {\n"service-account-x-y",\n}\n', {"x-y"}),
        "trailing comment": (edge.rstrip() + " # not service-account-zzz\n", {"openbank-edge"}),
    }
    total = len(cases) + len(rego) + 1
    for name, (src, want) in rego.items():
        got = principal_clients(src)
        if got != want:
            failed += 1
            print(f"::error::self-test FAILED: {name}: want {sorted(want)}, got {sorted(got)}")
    # end-to-end: a real principal whose client lacks `profile` MUST be flagged; a reason-name
    # string naming no client must NOT be.
    refs = {c: ["x.rego"] for c in principal_clients(
        'input.principal.id == "service-account-openbank-nop"\n'
        'allowed_reasons contains "service-account-transaction-create" if {true}\n')}
    tpl = {"openbank-nop": [("t", ["openid", "roles"])]}
    if set(refs) != {"openbank-nop"} or len(evaluate(refs, tpl)) != 1:
        failed += 1
        print("::error::self-test FAILED: principal-without-profile not flagged exactly once")
    print(f"self-test: {total - failed}/{total} passed")
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
