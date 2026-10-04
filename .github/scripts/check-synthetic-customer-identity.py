#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Gate: synthetic-customer-identity (ADR-0331, ADR-0252 phase 1, #4348).
#
# WHAT IT HOLDS
#   A bank-owned canary authenticates as a confidential `openbank-synthetic-*` client in the
#   customers realm whose service-account user IS the customer. That identity is safe only in
#   one shape, and every part of the shape is a line someone could change in a JSON file:
#
#   1. The client has no browser, password or implicit flow, and is confidential with a service
#      account. A canary with a password grant re-opens the surface ADR-0066 closed.
#   2. The client emits `party_id` from the user attribute, the claim customer-edge scopes
#      every call by, and `preferred_username`, the claim the edge's principal name comes from.
#      Without party_id the canary is nobody; without preferred_username its principal is a
#      UUID that no trust list names (measured live 2026-10-04: the first canary token had none).
#   3. Its service-account user holds EXACTLY `ROLE_CUSTOMER` and no client role, and carries a
#      `party_id` attribute. One extra role turns a canary into a privileged customer.
#   4. `openbank.synthetic.trusted-principals` is set only to principals that may assert the
#      taint: in customer-edge, the declared canary clients; anywhere else, customer-edge's own
#      service account, the one relay the edge forwards the taint through. Any other value lets
#      a caller drop real activity out of the regulatory aggregates by sending one header.
#
# WHAT IT DOES NOT DO
#   It reads the TEMPLATE. The live realm is imported from Vault and provisioned by kcadm
#   (ADR-0331 D5), so this gate cannot see the running Keycloak. ADR-0331's Delivery check is
#   the live half.
#
# Exit: 0 clean; 2 findings; prints SUBJECTS=<n>.
import argparse
import json
import pathlib
import sys
import tempfile

# The checkers run as scripts from the repo root, so this directory is not on sys.path.
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import gatelib  # noqa: E402  (path insert must precede the import)

REALM = "openbank-infra/gitops/components/keycloak/customers-realm-template.json"
GITOPS = "openbank-infra/gitops"
EDGE_MANIFEST_DIR = "openbank-infra/gitops/components/customer-edge"
PREFIX = "openbank-synthetic-"
SA_PREFIX = "service-account-"
ENV_NAME = "OPENBANK_SYNTHETIC_TRUSTED_PRINCIPALS"
# The one principal a downstream service may trust: customer-edge's M2M identity, which
# forwards the taint only after its own trusted-principal decision (UpstreamClient, #12050).
RELAY_PRINCIPALS = {"service-account-openbank-edge"}
FLOWS_OFF = ("standardFlowEnabled", "implicitFlowEnabled", "directAccessGrantsEnabled")


def _username_mapper(client: dict) -> bool:
    """preferred_username is the claim Quarkus takes the principal name from (after upn); without
    it the principal is the service-account user's UUID and no trust list can name it."""
    for m in client.get("protocolMappers") or []:
        cfg = m.get("config") or {}
        if (
            m.get("protocolMapper") == "oidc-usermodel-property-mapper"
            and cfg.get("user.attribute") == "username"
            and cfg.get("claim.name") == "preferred_username"
            and str(cfg.get("access.token.claim")).lower() == "true"
        ):
            return True
    return False


def _party_id_mapper(client: dict) -> bool:
    for m in client.get("protocolMappers") or []:
        cfg = m.get("config") or {}
        if (
            m.get("protocolMapper") == "oidc-usermodel-attribute-mapper"
            and cfg.get("user.attribute") == "party_id"
            and cfg.get("claim.name") == "party_id"
            and str(cfg.get("access.token.claim")).lower() == "true"
        ):
            return True
    return False


def check_realm(realm: dict) -> tuple[list[str], set[str]]:
    findings: list[str] = []
    principals: set[str] = set()
    users = {u.get("serviceAccountClientId"): u for u in realm.get("users") or [] if u.get("serviceAccountClientId")}
    for c in realm.get("clients") or []:
        cid = c.get("clientId", "")
        if not cid.startswith(PREFIX):
            continue
        where = f"{REALM}: client {cid}"
        if c.get("publicClient") is not False:
            findings.append(f"{where}: must be confidential (publicClient: false)")
        if c.get("serviceAccountsEnabled") is not True:
            findings.append(f"{where}: must have serviceAccountsEnabled: true — its service-account user is the customer")
        for flow in FLOWS_OFF:
            if c.get(flow) is not False:
                findings.append(f"{where}: {flow} must be explicitly false — a canary authenticates by client_credentials only")
        if not _username_mapper(c):
            findings.append(f"{where}: no preferred_username mapper on the access token — the principal would be a UUID the trust list cannot name")
        if not _party_id_mapper(c):
            findings.append(f"{where}: no party_id user-attribute mapper on the access token — the edge would scope it by sub")
        user = users.get(cid)
        if user is None:
            findings.append(f"{where}: no service-account user (serviceAccountClientId: {cid}) declaring its role and party_id")
            continue
        roles = user.get("realmRoles") or []
        if roles != ["ROLE_CUSTOMER"]:
            findings.append(f"{where}: service-account user must hold exactly [ROLE_CUSTOMER], holds {roles}")
        if user.get("clientRoles"):
            findings.append(f"{where}: service-account user must hold no client roles, holds {sorted(user['clientRoles'])}")
        if not (user.get("attributes") or {}).get("party_id"):
            findings.append(f"{where}: service-account user has no party_id attribute — it must name one SYNTHETIC party")
        principals.add(SA_PREFIX + cid)
    return findings, principals


def trusted_declarations(root: pathlib.Path) -> list[tuple[str, list[str]]]:
    """Every OPENBANK_SYNTHETIC_TRUSTED_PRINCIPALS env value in the GitOps tree."""
    out: list[tuple[str, list[str]]] = []
    for path in sorted(gatelib.rglob(root / GITOPS, "*.yaml")):
        text = gatelib.read_text(path, errors="replace")
        if ENV_NAME not in text:
            continue
        rel = str(path.relative_to(root))
        try:
            docs = gatelib.loads_all(text)
        except Exception as exc:  # a manifest we cannot parse cannot be cleared either
            out.append((rel, [f"<unparseable: {exc.__class__.__name__}>"]))
            continue
        for doc in docs:
            for value in _env_values(doc):
                out.append((rel, [p.strip() for p in str(value).split(",") if p.strip()]))
    return out


def _env_values(node):
    if isinstance(node, dict):
        if node.get("name") == ENV_NAME and "value" in node:
            yield node["value"]
        for v in node.values():
            yield from _env_values(v)
    elif isinstance(node, list):
        for v in node:
            yield from _env_values(v)


def check(root: pathlib.Path) -> tuple[list[str], int]:
    realm_path = root / REALM
    if not realm_path.is_file():
        return [f"{REALM}: missing — cannot check the synthetic identity"], 0
    findings, canaries = check_realm(json.loads(gatelib.read_text(realm_path)))
    decls = trusted_declarations(root)
    for rel, principals in decls:
        in_edge = rel.startswith(EDGE_MANIFEST_DIR + "/")
        allowed = canaries if in_edge else RELAY_PRINCIPALS
        role = "a declared openbank-synthetic-* client" if in_edge else "customer-edge's relay identity"
        for p in principals:
            if p not in allowed:
                findings.append(f"{rel}: {ENV_NAME} trusts {p!r}, which is not {role} — that caller could taint real activity")
    return findings, len(canaries) + len(decls)


GOOD_CLIENT = {
    "clientId": "openbank-synthetic-retail",
    "publicClient": False,
    "serviceAccountsEnabled": True,
    "standardFlowEnabled": False,
    "implicitFlowEnabled": False,
    "directAccessGrantsEnabled": False,
    "protocolMappers": [
        {
            "protocolMapper": "oidc-usermodel-property-mapper",
            "config": {"user.attribute": "username", "claim.name": "preferred_username", "access.token.claim": "true"},
        },
        {
            "protocolMapper": "oidc-usermodel-attribute-mapper",
            "config": {"user.attribute": "party_id", "claim.name": "party_id", "access.token.claim": "true"},
        }
    ],
}
GOOD_USER = {
    "username": "service-account-openbank-synthetic-retail",
    "serviceAccountClientId": "openbank-synthetic-retail",
    "realmRoles": ["ROLE_CUSTOMER"],
    "attributes": {"party_id": ["__P__"]},
}


def _tree(tmp: pathlib.Path, client: dict, user: dict | None, edge_env: str | None, other_env: str | None) -> pathlib.Path:
    realm = {"clients": [client], "users": [user] if user else []}
    (tmp / REALM).parent.mkdir(parents=True, exist_ok=True)
    (tmp / REALM).write_text(json.dumps(realm))
    for d, val in ((EDGE_MANIFEST_DIR, edge_env), (f"{GITOPS}/components/wealth", other_env)):
        if val is None:
            continue
        (tmp / d).mkdir(parents=True, exist_ok=True)
        (tmp / d / "deploy.yaml").write_text(
            "spec:\n  template:\n    spec:\n      containers:\n        - env:\n"
            f"            - name: {ENV_NAME}\n              value: \"{val}\"\n"
        )
    return tmp


def self_test() -> int:
    edge_ok = "service-account-openbank-synthetic-retail"
    relay_ok = "service-account-openbank-edge"
    cases = [
        ("conformant tree", GOOD_CLIENT, GOOD_USER, edge_ok, relay_ok, 0),
        ("password grant enabled", {**GOOD_CLIENT, "directAccessGrantsEnabled": True}, GOOD_USER, edge_ok, relay_ok, 1),
        ("flow key omitted", {k: v for k, v in GOOD_CLIENT.items() if k != "standardFlowEnabled"}, GOOD_USER, None, None, 1),
        ("public client", {**GOOD_CLIENT, "publicClient": True}, GOOD_USER, None, None, 1),
        ("no party_id mapper", {**GOOD_CLIENT, "protocolMappers": GOOD_CLIENT["protocolMappers"][:1]}, GOOD_USER, None, None, 1),
        ("no preferred_username mapper", {**GOOD_CLIENT, "protocolMappers": GOOD_CLIENT["protocolMappers"][1:]}, GOOD_USER, None, None, 1),
        ("extra realm role", GOOD_CLIENT, {**GOOD_USER, "realmRoles": ["ROLE_CUSTOMER", "ROLE_OPERATOR"]}, None, None, 1),
        ("client role", GOOD_CLIENT, {**GOOD_USER, "clientRoles": {"realm-management": ["manage-users"]}}, None, None, 1),
        ("no party_id attribute", GOOD_CLIENT, {**GOOD_USER, "attributes": {}}, None, None, 1),
        ("no service-account user", GOOD_CLIENT, None, None, None, 1),
        ("edge trusts an unknown principal", GOOD_CLIENT, GOOD_USER, "alice", None, 1),
        ("edge trusts the relay identity", GOOD_CLIENT, GOOD_USER, relay_ok, None, 1),
        ("downstream trusts the canary directly", GOOD_CLIENT, GOOD_USER, None, edge_ok, 1),
    ]
    failed = 0
    for name, client, user, edge_env, other_env, want in cases:
        with tempfile.TemporaryDirectory() as d:
            findings, _ = check(_tree(pathlib.Path(d), client, user, edge_env, other_env))
        got = 1 if findings else 0
        ok = got == want
        failed += 0 if ok else 1
        print(f"  {'ok  ' if ok else 'FAIL'} {name}: {'finding' if got else 'clean'} (want {'finding' if want else 'clean'})")
    print("self-test: PASS" if not failed else f"self-test: FAIL ({failed})")
    return 0 if not failed else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    findings, n = check(pathlib.Path(args.root))
    gatelib.subjects(n, "synthetic clients + trusted-principal declarations")
    for f in findings:
        print(f"::error::synthetic-customer-identity: {f}")
    if findings:
        print(f"check-synthetic-customer-identity: {len(findings)} finding(s)")
        return 2
    print("check-synthetic-customer-identity: OK — every synthetic customer identity has the ADR-0331 shape.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
