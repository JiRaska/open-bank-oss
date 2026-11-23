#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Derive the customers-realm MCP clients from the MCP client allowlist (ADR-0385 D1).

The allowlist (openbank-mcp-service/src/main/resources/mcp-client-allowlist.yaml) is the one
declaration that drives both mcp-service's tier admission and the realm client config. This
writes, into customers-realm-template.json:

  * one `clients[]` entry per declared client, shaped by its tier (Tier H: confidential,
    private_key_jwt with the content-pinned JWKS; Tier L: public, DPoP-bound), every one with
    PAR required, PKCE S256, exact redirects, no direct/implicit/device grant, no service account,
    and the dedicated browser-flow override;
  * the `openbank-mcp-fresh-passkey` browser flow: passwordless WebAuthn REQUIRED, no auth-cookie
    alternative (ADR-0385 D2.2).

Entries it owns are recognised by the `openbank.mcp.tier` client attribute; an owned entry no
longer declared is removed. Everything else in the template is left byte-for-byte as it was
parsed (the file is re-serialised with the template's own 2-space indent).

    python3 .github/scripts/gen-mcp-realm-clients.py           # rewrite the template
    python3 .github/scripts/gen-mcp-realm-clients.py --check   # exit 1 if it is stale

The parity gate (check-mcp-client-realm-parity.py) asserts the RESULT independently, so a hand
edit of the template, or a bug here, is caught by a check that does not share this code.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
ALLOWLIST = ROOT / "openbank-mcp-service/src/main/resources/mcp-client-allowlist.yaml"
TEMPLATE = ROOT / "openbank-infra/gitops/components/keycloak/customers-realm-template.json"
TIER_ATTR = "openbank.mcp.tier"
EXCHANGE_ATTR = "openbank.mcp.exchange"  # marks the ADR-0383 D4 exchange entries this script owns
EXCHANGE_MAX_LIFESPAN_S = 60  # ADR-0383 D4: exchanged downstream tokens live at most 60 s

ACCESS_TOKEN_LIFESPAN_S = 300  # ADR-0385 D1: access tokens live at most 5 minutes


def _mapper_party_id() -> dict:
    return {
        "name": "party-id",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-usermodel-attribute-mapper",
        "consentRequired": False,
        "config": {
            "user.attribute": "party_id",
            "claim.name": "party_id",
            "id.token.claim": "false",
            "access.token.claim": "true",
            "introspection.token.claim": "true",
            "userinfo.token.claim": "false",
            "jsonType.label": "String",
            "multivalued": "false",
            "aggregate.attrs": "false",
        },
    }


def _mapper_audience(audience: str) -> dict:
    return {
        "name": "mcp-service-audience",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-audience-mapper",
        "consentRequired": False,
        "config": {
            "included.custom.audience": audience,
            "id.token.claim": "false",
            "access.token.claim": "true",
            "introspection.token.claim": "true",
        },
    }


def _mapper_sub() -> dict:
    return {
        "name": "sub",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-sub-mapper",
        "consentRequired": False,
        "config": {"access.token.claim": "true", "introspection.token.claim": "true"},
    }


def build_client(decl: dict, c: dict, base: Path) -> dict:
    tier = c["tier"]
    attrs = {
        TIER_ATTR: tier,
        "pkce.code.challenge.method": "S256",
        "require.pushed.authorization.requests": "true",
        "oauth2.device.authorization.grant.enabled": "false",
        "oidc.ciba.grant.enabled": "false",
        "standard.token.exchange.enabled": "false",
        "use.refresh.tokens": "true",
        "client_credentials.use_refresh_token": "false",
        "access.token.lifespan": str(ACCESS_TOKEN_LIFESPAN_S),
        "exclude.session.state.from.auth.response": "false",
        "dpop.bound.access.tokens": "true" if tier == "L" else "false",
        "frontchannel.logout.session.required": "false",
        "backchannel.logout.session.required": "true",
    }
    client: dict = {
        "clientId": c["client_id"],
        "name": c["display_name"],
        "description": f"MCP client, ADR-0385 Tier {tier}. DERIVED from mcp-client-allowlist.yaml - do not hand-edit.",
        "enabled": bool(c.get("enabled", False)),
        "protocol": "openid-connect",
        "standardFlowEnabled": True,
        "implicitFlowEnabled": False,
        "directAccessGrantsEnabled": False,
        "serviceAccountsEnabled": False,
        "authorizationServicesEnabled": False,
        "frontchannelLogout": False,
        "consentRequired": False,
        "fullScopeAllowed": False,
        "redirectUris": list(c["redirect_uris"]),
        "webOrigins": [],
        "authenticationFlowBindingOverrides": {"browser": decl["flow"]["id"]},
        "defaultClientScopes": [],
        "optionalClientScopes": ["offline_access"],
        "protocolMappers": [_mapper_sub(), _mapper_party_id(), _mapper_audience(decl["audience"])],
    }
    if tier == "H":
        auth = c["auth"]
        client["publicClient"] = False
        if auth["method"] == "private_key_jwt":
            jwks = (base / auth["jwks_file"]).read_text()
            client["clientAuthenticatorType"] = "client-jwt"
            attrs["use.jwks.url"] = "false"
            attrs["use.jwks.string"] = "true"
            attrs["jwks.string"] = json.dumps(json.loads(jwks), separators=(",", ":"))
            attrs["token.endpoint.auth.signing.alg"] = auth.get("signing_alg", "ES256")
        elif auth["method"] == "client_secret":
            client["clientAuthenticatorType"] = "client-secret"
            client["secret"] = auth["secret_placeholder"]
            attrs["openbank.mcp.secret_exception_expires"] = str(c["secret_exception"]["expires"])
        else:
            raise SystemExit(f"{c['client_id']}: unsupported auth.method {auth['method']!r}")
    else:
        client["publicClient"] = True
    client["attributes"] = dict(sorted(attrs.items()))
    return client


def _no_flow_client(client_id: str, description: str, role: str) -> dict:
    """A client that can obtain no token by any flow of its own."""
    return {
        "clientId": client_id,
        "name": client_id,
        "description": description,
        "enabled": True,
        "protocol": "openid-connect",
        "publicClient": False,
        "standardFlowEnabled": False,
        "implicitFlowEnabled": False,
        "directAccessGrantsEnabled": False,
        "serviceAccountsEnabled": False,
        "authorizationServicesEnabled": False,
        "frontchannelLogout": False,
        "consentRequired": False,
        "fullScopeAllowed": False,
        "redirectUris": [],
        "webOrigins": [],
        "defaultClientScopes": [],
        "optionalClientScopes": [],
        "protocolMappers": [],
        "attributes": {
            EXCHANGE_ATTR: role,
            "oauth2.device.authorization.grant.enabled": "false",
            "oidc.ciba.grant.enabled": "false",
            "standard.token.exchange.enabled": "false",
        },
    }


def build_exchange(decl: dict) -> tuple[list[dict], list[dict]]:
    """ADR-0383 D4: the requester client, one audience client per downstream, one scope each."""
    ex = decl["exchange"]
    req = ex["requester"]
    lifespan = int(req["access_token_lifespan"])
    if not 0 < lifespan <= EXCHANGE_MAX_LIFESPAN_S:
        raise SystemExit(f"exchange.requester.access_token_lifespan must be 1..{EXCHANGE_MAX_LIFESPAN_S}")
    scopes = []
    clients = []
    for a in ex["audiences"]:
        scopes.append({
            "name": a["scope"],
            "description": f"ADR-0383 D4 customer read for {a['client_id']}. DERIVED - do not hand-edit.",
            "protocol": "openid-connect",
            "attributes": {
                EXCHANGE_ATTR: "scope",
                "include.in.token.scope": "true",
                "display.on.consent.screen": "false",
            },
            "protocolMappers": [{
                "name": f"{a['client_id']}-audience",
                "protocol": "openid-connect",
                "protocolMapper": "oidc-audience-mapper",
                "consentRequired": False,
                "config": {
                    "included.client.audience": a["client_id"],
                    "id.token.claim": "false",
                    "access.token.claim": "true",
                    "introspection.token.claim": "true",
                },
            }],
        })
        aud = _no_flow_client(
            a["client_id"],
            "ADR-0383 D4 audience of exchanged customer tokens; obtains no token itself. DERIVED - do not hand-edit.",
            "audience",
        )
        # Bearer-only: it holds no credential at all, so there is no secret to provision or leak.
        del aud["publicClient"]
        aud["bearerOnly"] = True
        clients.append(aud)
    requester = _no_flow_client(
        req["client_id"],
        "ADR-0383 D4 mcp-service RFC 8693 requester: customer token to one downstream read. DERIVED - do not hand-edit.",
        "requester",
    )
    requester["clientAuthenticatorType"] = "client-secret"
    requester["secret"] = req["secret_placeholder"]
    requester["optionalClientScopes"] = [a["scope"] for a in ex["audiences"]]
    requester["attributes"].update({
        "standard.token.exchange.enabled": "true",
        "access.token.lifespan": str(lifespan),
        "use.refresh.tokens": "false",
    })
    requester["attributes"] = dict(sorted(requester["attributes"].items()))
    for c in clients:
        c["attributes"] = dict(sorted(c["attributes"].items()))
    return [requester] + clients, scopes


def build_flow(decl: dict) -> dict:
    return {
        "id": decl["flow"]["id"],
        "alias": decl["flow"]["alias"],
        "description": (
            "ADR-0385 D2.2: fresh passwordless WebAuthn for every MCP authorize. NO auth-cookie "
            "alternative - an SSO session never skips the assertion. DERIVED from "
            "mcp-client-allowlist.yaml."
        ),
        "providerId": "basic-flow",
        "topLevel": True,
        "builtIn": False,
        "authenticationExecutions": [
            {
                "authenticator": "webauthn-authenticator-passwordless",
                "authenticatorFlow": False,
                "requirement": "REQUIRED",
                "priority": 10,
                "userSetupAllowed": False,
            }
        ],
    }


def load_allowlist(path: Path = ALLOWLIST) -> dict:
    return yaml.safe_load(path.read_text())


def jwks_sha256(base: Path, rel: str) -> str:
    return hashlib.sha256((base / rel).read_bytes()).hexdigest()


def render(template: dict, decl: dict, base: Path) -> dict:
    out = copy.deepcopy(template)
    wanted = [build_client(decl, c, base) for c in decl["clients"]]
    ex_clients, ex_scopes = build_exchange(decl) if decl.get("exchange") else ([], [])

    def _owned(c: dict) -> bool:
        attrs = c.get("attributes") or {}
        return TIER_ATTR in attrs or EXCHANGE_ATTR in attrs

    kept = [c for c in out.get("clients", []) if not _owned(c)]
    out["clients"] = kept + wanted + ex_clients
    out["clientScopes"] = [s for s in out.get("clientScopes", []) if not _owned(s)] + ex_scopes
    # fullScopeAllowed=false, and the offline_access client scope's own role mapping does NOT make
    # the role reachable (measured on Keycloak 26.8.1: "Offline tokens not allowed for the user or
    # client"). So each MCP client is scope-mapped to offline_access and NOTHING else: the token
    # carries no realm role mcp-service could mistake for a grant (realm-template-importable R11
    # holds MCP clients to exactly this mapping).
    owned = {c["client_id"] for c in decl["clients"]} | {
        c["clientId"] for c in template.get("clients", []) if TIER_ATTR in (c.get("attributes") or {})}
    out["scopeMappings"] = [m for m in out.get("scopeMappings", []) if m.get("client") not in owned] + [
        {"client": c["client_id"], "roles": ["offline_access"]} for c in decl["clients"]]
    alias = decl["flow"]["alias"]
    flows = [f for f in out.get("authenticationFlows", []) if f.get("alias") != alias]
    out["authenticationFlows"] = flows + [build_flow(decl)]
    return out


def serialise(doc: dict) -> str:
    return json.dumps(doc, indent=2, ensure_ascii=False) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="exit 1 if the template is stale")
    args = ap.parse_args()
    decl = load_allowlist()
    current = TEMPLATE.read_text()
    rendered = serialise(render(json.loads(current), decl, ALLOWLIST.parent))
    if args.check:
        if rendered != current:
            print(f"::error file={TEMPLATE.relative_to(ROOT)}::MCP clients are stale - run "
                  "python3 .github/scripts/gen-mcp-realm-clients.py")
            return 1
        print("MCP realm clients are up to date")
        return 0
    TEMPLATE.write_text(rendered)
    print(f"wrote {len(decl['clients'])} MCP client(s) + flow {decl['flow']['alias']} into "
          f"{TEMPLATE.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
