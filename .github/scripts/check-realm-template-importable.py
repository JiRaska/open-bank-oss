#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Gate: can the committed Keycloak realm templates actually be IMPORTED? (issue #3246)
#
# WHY THIS EXISTS
#   #3246 measured that the realm JSON Keycloak imports is a stale ancestor of the committed
#   template (4 roles / 2 clients against 14 / 10). Reconciling the two is an owner-gated Vault
#   write (runbook 0009). While preparing that write, the templates were run against the real
#   Keycloak the cluster deploys — quay.io/keycloak/keycloak:26.6.3 — and the CUSTOMERS template
#   did not import at all:
#
#     ERROR: Failed to run import
#     ERROR: Unrecognized field "comment" (class ...ProtocolMapperRepresentation), not marked as
#       ignorable ... ["clients"]->[0]->["protocolMappers"]->[1]->["comment"]
#
#   Keycloak deserializes a realm with Jackson and fails the WHOLE import on one unknown field.
#   JSON has no comment syntax and Keycloak's schema has no comment field, so the five `"comment"`
#   keys someone added for the next reader made the file unimportable. So the template was not
#   merely a superset of the deployed artifact — it could never have rebuilt its realm, which is
#   the property #3246 is actually about.
#
#   Nothing could have caught that. `--import-realm` runs on COLD START ONLY, and it skips a realm
#   that already exists, so the template is inert on every deploy, every restart and every DR
#   restore. ArgoCD is Synced/Healthy about a Secret it does not manage; the three existing realm
#   checks (check-roles-allowed-realm, check-realm-role-parity, check-realm-import-parity) all
#   compare NAME SETS and are perfectly happy with a document Keycloak refuses to parse. The first
#   observation of the defect is the rebuild it was supposed to survive.
#
# WHAT THIS CHECKS, AND WHAT IT HONESTLY CANNOT
#   A static gate CANNOT enumerate Keycloak's representation schema — that lives in Jackson
#   annotations inside the server jar, it is version-specific, and it is not published in any form
#   this repo can read offline. So this gate does not claim "the template imports". It claims:
#   none of the defect classes below is present. They are the ones that (a) fail the entire import
#   or silently produce a broken realm, and (b) are decidable from the file alone:
#
#     R1  a `comment`/`note`/`_comment`/`_doc`/`description_` key anywhere. Prose-in-JSON is the
#         measured defect. Prose goes in components/keycloak/README.md, keyed by path.
#     R2  a client `description` over 255 chars — Keycloak's column cap; a longer one fails the
#         whole import. `openbank-edge-webauthn` was at 490.
#     R3  a confidential client (`publicClient: false`) with no `secret`. Keycloak generates a
#         random one on import, so a rebuilt realm issues credentials the service does not
#         present — an auth outage that looks nothing like a missing JSON field.
#     R4  a `secret` or `credentials[].value` that is NOT a `__PLACEHOLDER__` token. This repo is
#         PUBLIC; a literal secret in a realm template is a disclosure, not a style problem.
#     R5  a login-capable user missing `firstName`/`lastName`/`email` — Keycloak 26 answers
#         "Account is not fully set up" at login with no hint why. `service-account-*` users are
#         exempt: they never log in interactively, both real ones lack all three fields, and the
#         template carrying them imports cleanly against 26.6.3. That carve-out exists because
#         the first run of this gate produced it as a false positive on a correct file.
#     R6  the file is not valid JSON at all.
#     R7  a duplicate key in the same JSON object. Every JSON parser silently keeps the LAST
#         occurrence, so the other one is discarded with no error anywhere — the same failure
#         mode the repo already documents for duplicate YAML keys in application.yaml.
#         `customers-realm-template.json` declared `authenticationFlows` twice, an empty array
#         and the real four-flow list.
#     R8  an `authenticationExecutions[].authenticator` over 36 characters. Keycloak's
#         AUTHENTICATION_EXECUTION.AUTHENTICATOR column is VARCHAR(36) in the Liquibase
#         changelog, so a longer value aborts the import mid-transaction with a raw SQL error
#         ("Value too long for column ..."), not a validation message. The customers template
#         carried `webauthn-register-passwordless-action` (37) — which also proves the live
#         realm cannot contain it, since the same constraint applies to the deployed Postgres.
#     R9  a composite realm role referencing a realm role the template does not declare. A full
#         import with an explicit `roles.realm` list does NOT get Keycloak's automatic built-in
#         realm roles, so the import aborts with "Unable to find composite realm role: <name>".
#         `default-roles-openbank-customers` composed `offline_access` and `uma_authorization`,
#         neither declared.
#
#   Measured on the pre-fix templates: 12 findings, covering R1 (×5), R2, R3 (×2), R7, R8 and R9.
#   Each was independently confirmed against the real container — the customers realm needed all
#   four import-blockers removed before `Realm 'openbank-customers' imported` appeared.
#
#   The residual risk — an unknown field this list does not name — is unbounded and is why
#   README.md keeps the real `docker run ... --import-realm` recipe. Run it before changing a
#   template. This gate narrows the window; it does not close it.
#
# FALSIFIABILITY
#   `--self-test` builds an in-memory template for each rule, in BOTH directions: a clean document
#   that must pass, and a document carrying exactly that defect which must be flagged. It also
#   asserts the real committed templates are the ones being read, and every run prints how many
#   files, clients and users it compared — a green with `templates=0` is a gate that never opened
#   the file, and this makes that visible instead of silent.
#
# Run:
#   python3 .github/scripts/check-realm-template-importable.py
#   python3 .github/scripts/check-realm-template-importable.py --self-test

import argparse
import glob
import json
import sys

REALM_GLOB = "openbank-infra/gitops/components/keycloak/*realm-template*.json"

# R1. Keys a human adds for documentation. None exists in any Keycloak representation, so each
# one fails the entire import with "Unrecognized field".
PROSE_KEYS = {"comment", "_comment", "note", "_note", "doc", "_doc", "description_"}

# R2. Keycloak's client.description column.
DESCRIPTION_MAX = 255

# R5. Keycloak 26 user-profile required attributes for a login-capable user.
REQUIRED_USER_FIELDS = ("firstName", "lastName", "email")

# R8. AUTHENTICATION_EXECUTION.AUTHENTICATOR is VARCHAR(36) in Keycloak's Liquibase changelog.
AUTHENTICATOR_MAX = 36


class DuplicateKey(ValueError):
    """R7. Raised with every duplicated key path found while parsing."""


def _no_duplicates(pairs):
    """json object_pairs_hook that rejects a repeated key instead of keeping the last."""
    seen, dupes = {}, []
    for k, v in pairs:
        if k in seen:
            dupes.append(k)
        seen[k] = v
    if dupes:
        raise DuplicateKey(", ".join(sorted(set(dupes))))
    return seen


def _is_placeholder(v):
    return isinstance(v, str) and v.startswith("__") and v.endswith("__") and len(v) > 4


def _walk_prose_keys(node, path=""):
    """Yield (path, key) for every documentation-only key, at any depth."""
    if isinstance(node, dict):
        for k, v in node.items():
            if k in PROSE_KEYS:
                yield (path or "/", k)
            yield from _walk_prose_keys(v, f"{path}/{k}")
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from _walk_prose_keys(v, f"{path}[{i}]")


def check_realm(name, realm):
    """Return (findings, counts) for one parsed realm document."""
    findings = []
    clients = realm.get("clients", []) or []
    users = realm.get("users", []) or []

    for path, key in _walk_prose_keys(realm):
        findings.append(
            f"[{name}] prose key `{key}` at {path} — Keycloak fails the WHOLE import on an "
            f"unrecognized field (R1). Move the text to components/keycloak/README.md."
        )

    for c in clients:
        cid = c.get("clientId", "<no clientId>")
        desc = c.get("description") or ""
        if len(desc) > DESCRIPTION_MAX:
            findings.append(
                f"[{name}] client `{cid}` description is {len(desc)} chars, over Keycloak's "
                f"{DESCRIPTION_MAX} cap (R2) — a longer one fails the entire import."
            )
        if c.get("publicClient") is False:
            if "secret" not in c:
                findings.append(
                    f"[{name}] confidential client `{cid}` declares no `secret` (R3) — Keycloak "
                    f"generates a random one on import, so a rebuilt realm issues credentials the "
                    f"service does not present. Add a `__PLACEHOLDER__` token."
                )
            elif not _is_placeholder(c.get("secret")):
                findings.append(
                    f"[{name}] client `{cid}` carries a literal `secret` (R4) — this repo is "
                    f"PUBLIC. Use a `__PLACEHOLDER__` token."
                )
        for cred in c.get("credentials", []) or []:
            if "value" in cred and not _is_placeholder(cred["value"]):
                findings.append(
                    f"[{name}] client `{cid}` has a literal credentials[].value (R4) — this repo "
                    f"is PUBLIC. Use a `__PLACEHOLDER__` token."
                )

    for u in users:
        uname = u.get("username", "<no username>")
        # A service account never logs in interactively, so the user-profile requirement does not
        # apply to it. Both `service-account-*` entries in realm-template.json lack all three
        # fields and that template imports cleanly against 26.6.3 — measured, not assumed. Without
        # this carve-out R5 would be a permanent false positive on a correct file.
        is_service_account = uname.startswith("service-account-")
        missing = [] if is_service_account else [f for f in REQUIRED_USER_FIELDS if not u.get(f)]
        if missing:
            findings.append(
                f"[{name}] user `{uname}` is missing {missing} (R5) — Keycloak 26 answers "
                f'"Account is not fully set up" at login with no hint why.'
            )
        for cred in u.get("credentials", []) or []:
            if "value" in cred and not _is_placeholder(cred["value"]):
                findings.append(
                    f"[{name}] user `{uname}` has a literal credentials[].value (R4) — this repo "
                    f"is PUBLIC. Use a `__PLACEHOLDER__` token."
                )

    # R9. A full import with an explicit roles.realm list does NOT get Keycloak's automatic
    # built-in realm roles, so a composite referencing one aborts the import with
    # "Unable to find composite realm role: <name>". Measured: `default-roles-openbank-customers`
    # composed `offline_access` and `uma_authorization`, neither declared.
    realm_roles = realm.get("roles", {}).get("realm", []) or []
    declared = {r.get("name") for r in realm_roles}
    for r in realm_roles:
        for ref in (r.get("composites") or {}).get("realm", []) or []:
            if ref not in declared:
                findings.append(
                    f"[{name}] composite role `{r.get('name')}` references realm role `{ref}`, "
                    f"which the template does not declare (R9) — an explicit-roles import does "
                    f"not get Keycloak's built-ins, so this aborts with "
                    f"'Unable to find composite realm role: {ref}'."
                )

    for flow in realm.get("authenticationFlows", []) or []:
        for ex in flow.get("authenticationExecutions", []) or []:
            a = ex.get("authenticator")
            if isinstance(a, str) and len(a) > AUTHENTICATOR_MAX:
                findings.append(
                    f"[{name}] flow `{flow.get('alias')}` execution authenticator `{a}` is "
                    f"{len(a)} chars, over the VARCHAR({AUTHENTICATOR_MAX}) column (R8) — the "
                    f"import aborts with a raw SQL 'Value too long for column' error."
                )

    return findings, (len(clients), len(users))


def run(paths):
    findings, n_clients, n_users = [], 0, 0
    for p in paths:
        try:
            realm = json.loads(open(p, encoding="utf-8").read(), object_pairs_hook=_no_duplicates)
        except DuplicateKey as e:
            findings.append(
                f"[{p}] duplicate JSON key(s) `{e}` (R7) — every parser silently keeps the LAST "
                f"occurrence, so the other is discarded with no error anywhere."
            )
            continue
        except json.JSONDecodeError as e:
            findings.append(f"[{p}] is not valid JSON (R6): {e}")
            continue
        f, (c, u) = check_realm(realm.get("realm", p), realm)
        findings += f
        n_clients += c
        n_users += u
    return findings, n_clients, n_users


# --------------------------------------------------------------------------------------------
# Self-test: every rule, in BOTH directions.
# --------------------------------------------------------------------------------------------

def _clean():
    return {
        "realm": "t",
        "clients": [
            {"clientId": "pub", "publicClient": True, "description": "ok"},
            {"clientId": "conf", "publicClient": False, "secret": "__X_SECRET__"},
        ],
        "users": [
            {
                "username": "a@b.c",
                "firstName": "A",
                "lastName": "B",
                "email": "a@b.c",
                "credentials": [{"type": "password", "value": "__PW__"}],
            }
        ],
    }


def self_test():
    import copy

    cases = []

    ok, _ = check_realm("t", _clean())
    cases.append(("clean template passes", ok == [], ok))

    d = _clean()
    d["clients"][0]["protocolMappers"] = [{"name": "m", "comment": "why"}]
    f, _ = check_realm("t", d)
    cases.append(("R1 nested prose key flagged", any("prose key" in x for x in f), f))

    d = _clean()
    d["clients"][0]["description"] = "x" * 256
    f, _ = check_realm("t", d)
    cases.append(("R2 over-long description flagged", any("over Keycloak" in x for x in f), f))
    d["clients"][0]["description"] = "x" * 255
    f, _ = check_realm("t", d)
    cases.append(("R2 exactly 255 passes", f == [], f))

    d = _clean()
    del d["clients"][1]["secret"]
    f, _ = check_realm("t", d)
    cases.append(("R3 secret-less confidential client flagged", any("declares no" in x for x in f), f))

    d = _clean()
    d["clients"][1]["secret"] = "s3cr3t-literal"
    f, _ = check_realm("t", d)
    cases.append(("R4 literal client secret flagged", any("literal `secret`" in x for x in f), f))

    d = _clean()
    d["users"][0]["credentials"][0]["value"] = "hunter2"
    f, _ = check_realm("t", d)
    cases.append(("R4 literal user password flagged", any("credentials[].value" in x for x in f), f))

    d = _clean()
    del d["users"][0]["email"]
    f, _ = check_realm("t", d)
    cases.append(("R5 incomplete user flagged", any("not fully set up" in x for x in f), f))

    # ...but a service account legitimately has none of the three. Both real ones in
    # realm-template.json are shaped this way and that template imports cleanly.
    d = _clean()
    d["users"] = [{"username": "service-account-openbank-edge"}]
    f, _ = check_realm("t", d)
    cases.append(("R5 does NOT flag a service account", f == [], f))

    # A public client with no secret must NOT be flagged — that is the correct shape.
    d = _clean()
    f, _ = check_realm("t", d)
    cases.append(("public client with no secret not flagged", f == [], f))

    # R7, exercised through run() because it is a parse-time rule, not a document rule.
    import tempfile, os

    with tempfile.TemporaryDirectory() as td:
        dup = os.path.join(td, "dup.json")
        open(dup, "w").write('{"realm":"t","authenticationFlows":[],"authenticationFlows":[1]}')
        f, _, _ = run([dup])
        cases.append(("R7 duplicate key flagged", any("duplicate JSON key" in x for x in f), f))

        okf = os.path.join(td, "ok.json")
        open(okf, "w").write(json.dumps(_clean()))
        f, nc, nu = run([okf])
        cases.append(("R7 non-duplicate file passes and IS read", f == [] and nc == 2 and nu == 1, (f, nc, nu)))

        badj = os.path.join(td, "bad.json")
        open(badj, "w").write("{not json")
        f, _, _ = run([badj])
        cases.append(("R6 invalid JSON flagged", any("not valid JSON" in x for x in f), f))

    # R8. 37 chars is the measured real defect (`webauthn-register-passwordless-action`).
    d = _clean()
    d["authenticationFlows"] = [
        {"alias": "f", "authenticationExecutions": [{"authenticator": "x" * 37}]}
    ]
    f, _ = check_realm("t", d)
    cases.append(("R8 37-char authenticator flagged", any("VARCHAR(36)" in x for x in f), f))
    d["authenticationFlows"][0]["authenticationExecutions"][0]["authenticator"] = "x" * 36
    f, _ = check_realm("t", d)
    cases.append(("R8 exactly 36 passes", f == [], f))

    # R9, both directions.
    d = _clean()
    d["roles"] = {"realm": [{"name": "a", "composites": {"realm": ["missing"]}}]}
    f, _ = check_realm("t", d)
    cases.append(("R9 undeclared composite ref flagged", any("(R9)" in x for x in f), f))
    d["roles"]["realm"].append({"name": "missing"})
    f, _ = check_realm("t", d)
    cases.append(("R9 declared composite ref passes", f == [], f))

    # Scope: the glob must actually find the committed templates.
    found = sorted(glob.glob(REALM_GLOB))
    cases.append((f"glob finds the committed templates ({len(found)} found)", len(found) >= 2, found))

    # And they must be the ones the gate is about.
    names = {p.split("/")[-1] for p in found}
    cases.append(
        (
            "both realm templates in scope",
            {"realm-template.json", "customers-realm-template.json"} <= names,
            sorted(names),
        )
    )

    bad = 0
    for label, passed, detail in cases:
        print(f"  {'PASS' if passed else 'FAIL'}  {label}")
        if not passed:
            bad += 1
            print(f"        got: {detail}")
    print(f"self-test: {len(cases) - bad}/{len(cases)} passed")
    _ = copy  # keep the import honest for future cases
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    paths = sorted(glob.glob(REALM_GLOB))
    if not paths:
        print(f"::error::no realm template matched {REALM_GLOB} — the gate read nothing.")
        return 1

    findings, n_clients, n_users = run(paths)
    print(
        f"realm-template-importable: compared {len(paths)} template(s), "
        f"{n_clients} client(s), {n_users} user(s) against 9 rules."
    )
    for p in paths:
        print(f"  - {p}")
    for f in findings:
        print(f"::error::{f}")
    if findings:
        print(
            f"::error::{len(findings)} finding(s). Keycloak imports a realm on COLD START ONLY, "
            f"so a malformed template ships silently — see "
            f"openbank-infra/gitops/components/keycloak/README.md."
        )
        return 1
    print("OK — no known-fatal shape in any committed realm template.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
