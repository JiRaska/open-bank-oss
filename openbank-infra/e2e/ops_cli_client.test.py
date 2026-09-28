#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Keep the operator CLI's Keycloak client a loopback-only PKCE public client.

The realm import test proves Keycloak accepts the JSON, but would also accept a
password grant, an open redirect, or a lightweight token on this client.
"""

import argparse
import copy
import json
from pathlib import Path


REALM = Path("openbank-infra/gitops/components/keycloak/realm-template.json")
CLIENT_ID = "openbank-ops-cli"


def findings(clients):
    matches = [client for client in clients if client.get("clientId") == CLIENT_ID]
    if len(matches) != 1:
        return [f"expected one {CLIENT_ID} client, found {len(matches)}"]

    client = matches[0]
    attributes = client.get("attributes") or {}
    required = {
        "enabled": True,
        "publicClient": True,
        "standardFlowEnabled": True,
        "directAccessGrantsEnabled": False,
        "implicitFlowEnabled": False,
        "serviceAccountsEnabled": False,
        "protocol": "openid-connect",
        "redirectUris": ["http://127.0.0.1/*"],
        "webOrigins": [],
    }
    errors = [f"{key}: expected {value!r}, got {client.get(key)!r}"
              for key, value in required.items() if client.get(key) != value]
    if "secret" in client:
        errors.append("public CLI client must not carry a secret")
    for key, value in {
        "pkce.code.challenge.method": "S256",
        "access.token.lifespan": "300",
        "client.use.lightweight.access.token.enabled": "false",
    }.items():
        if attributes.get(key) != value:
            errors.append(f"attributes.{key}: expected {value!r}, got {attributes.get(key)!r}")
    required_scopes = {"openid", "profile", "email", "roles"}
    if not required_scopes.issubset(client.get("defaultClientScopes") or []):
        errors.append("defaultClientScopes must include openid, profile, email and roles")
    return errors


def self_test():
    realm = json.loads(REALM.read_text())
    client = next(c for c in realm["clients"] if c.get("clientId") == CLIENT_ID)
    assert not findings([client]), "committed operator client must pass"

    mutations = [
        ("publicClient", False),
        ("standardFlowEnabled", False),
        ("directAccessGrantsEnabled", True),
        ("implicitFlowEnabled", True),
        ("serviceAccountsEnabled", True),
        ("redirectUris", ["https://evil.example/callback"]),
        ("webOrigins", ["*"]),
        ("secret", "unexpected"),
        ("defaultClientScopes", ["openid"]),
    ]
    for key, value in mutations:
        changed = copy.deepcopy(client)
        changed[key] = value
        assert findings([changed]), f"unsafe {key} change escaped"
    for key, value in {
        "pkce.code.challenge.method": "plain",
        "access.token.lifespan": "3600",
        "client.use.lightweight.access.token.enabled": "true",
    }.items():
        changed = copy.deepcopy(client)
        changed["attributes"][key] = value
        assert findings([changed]), f"unsafe {key} change escaped"
    assert findings([]), "missing client escaped"
    assert findings([client, client]), "duplicate client escaped"
    print("ops-cli-client: 14 negative controls and one committed positive control passed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        clients = json.loads(REALM.read_text())["clients"]
        print(f"SUBJECTS={sum(c.get('clientId') == CLIENT_ID for c in clients)}")
        errors = findings(clients)
        for error in errors:
            print(f"ops-cli-client: {error}")
        if errors:
            raise SystemExit(1)
        print("ops-cli-client: one secure operator client found")
