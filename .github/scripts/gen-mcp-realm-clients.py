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
    alternative (ADR-0385 D2.2), then the withheld-code authenticator REQUIRED (ADR-0385 D3) —
    the code is issued only after the context-linked SCA challenge was consumed;
  * the `openbank-mcp` client profile + policy binding the `openbank-mcp-client-guard` executor
    to every client (`any-client`; the executor acts only on `openbank.mcp.tier` clients): no
    token exchange for or of an MCP client (#16667), the bound RFC 8707 resource at PAR, and the
    PAR-held request facts the authenticator recomputes the connect context from;
  * the `openbank-mcp-oauth-hardening` (pkce-enforcer, reject-implicit-grant, reject-ropc-grant)
    and `openbank-mcp-dpop` (dpop-bind-enforcer) Keycloak built-in profiles, bound by the
    `openbank-mcp-tier-h` / `openbank-mcp-tier-l` policies on the `openbank.mcp.tier` attribute.

Both providers ship in the bank's Keycloak image (openbank-infra/docker/keycloak/extension).

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
RESOURCE_ATTR = "openbank.mcp.resource"
WITHHELD_CODE_AUTHENTICATOR = "openbank-mcp-withheld-code"
GUARD_EXECUTOR = "openbank-mcp-client-guard"
POLICY_NAME = "openbank-mcp"
# Review of #16672 (6): Keycloak's BUILT-IN executors, as a second layer under the per-client
# attributes (pkce.code.challenge.method, implicitFlowEnabled, directAccessGrantsEnabled,
# dpop.bound.access.tokens). A client edited in the admin console loses its attribute silently;
# it cannot lose a client policy without a realm-level change. Scoped by the client's own tier
# attribute (never any-client: reject-ropc-grant on every customers-realm client is not this
# PR's decision to make).
HARDENING_PROFILE = "openbank-mcp-oauth-hardening"
HARDENING_POLICY_PREFIX = "openbank-mcp-tier-"
HARDENING_EXECUTORS = [
    {"executor": "pkce-enforcer", "configuration": {"auto-configure": "false"}},
    {"executor": "reject-implicit-grant", "configuration": {"auto-configure": "false"}},
    {"executor": "reject-ropc-grant", "configuration": {}},
]
# Only Tier L is DPoP-bound (Tier H is private_key_jwt-confidential, dpop.bound=false), so the
# DPoP enforcer is its own profile, bound for Tier L alone.
DPOP_PROFILE = "openbank-mcp-dpop"
DPOP_EXECUTORS = [
    {"executor": "dpop-bind-enforcer",
     "configuration": {"auto-configure": "false", "enforce-authorization-code-binding-to-dpop": "false"}},
]

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
        RESOURCE_ATTR: decl["resource"],
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


def build_flow(decl: dict) -> dict:
    return {
        "id": decl["flow"]["id"],
        "alias": decl["flow"]["alias"],
        "description": (
            "ADR-0385 D2.2: fresh passwordless WebAuthn for every MCP authorize. NO auth-cookie "
            "alternative - an SSO session never skips the assertion. D3: the code is withheld "
            "until the context-linked SCA challenge is consumed. DERIVED from mcp-client-allowlist.yaml."
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
            },
            {
                "authenticator": WITHHELD_CODE_AUTHENTICATOR,
                "authenticatorFlow": False,
                "requirement": "REQUIRED",
                "priority": 20,
                "userSetupAllowed": False,
            },
        ],
    }


def build_profile() -> dict:
    return {
        "name": POLICY_NAME,
        "description": "ADR-0385 / #16667: MCP client guard. DERIVED from mcp-client-allowlist.yaml.",
        "executors": [{"executor": GUARD_EXECUTOR, "configuration": {}}],
    }


def _tier_condition(tier: str) -> dict:
    # ONE key/value per condition: Keycloak 26.8.1's client-attributes condition deserialises the
    # list into a Map and refuses (matches nothing, logs "More values ... cannot match any client")
    # when one key carries two values, so "tier H or L" is two policies, not one OR condition.
    return {
        "condition": "client-attributes",
        "configuration": {
            "is_negative_logic": False,
            "attributes": json.dumps([{"key": TIER_ATTR, "value": tier}]),
        },
    }


def build_hardening() -> tuple[list[dict], list[dict]]:
    """(profiles, policies) for the built-in executors, in the order they are written."""
    profiles = [
        {"name": HARDENING_PROFILE, "description": "Review of #16672: PKCE, no implicit, no ROPC for every MCP "
         "client. DERIVED from mcp-client-allowlist.yaml.", "executors": copy.deepcopy(HARDENING_EXECUTORS)},
        {"name": DPOP_PROFILE, "description": "Review of #16672: DPoP-bound tokens (Tier L). DERIVED from "
         "mcp-client-allowlist.yaml.", "executors": copy.deepcopy(DPOP_EXECUTORS)},
    ]
    policies = []
    for tier, names in (("H", [HARDENING_PROFILE]), ("L", [HARDENING_PROFILE, DPOP_PROFILE])):
        policies.append({
            "name": f"{HARDENING_POLICY_PREFIX}{tier.lower()}",
            "description": f"Review of #16672: Keycloak built-in hardening for Tier {tier} MCP clients. "
                           f"DERIVED from mcp-client-allowlist.yaml.",
            "enabled": True,
            "conditions": [_tier_condition(tier)],
            "profiles": names,
        })
    return profiles, policies


def build_policy() -> dict:
    # any-client, not a client-id list: the executor decides by the client's own tier attribute,
    # so a newly declared client is covered without touching the policy, and the grant-type
    # condition (which does not fire for a v1 exchange, #16667) is not relied on.
    return {
        "name": POLICY_NAME,
        "description": "ADR-0385 / #16667: bind the MCP client guard. DERIVED from mcp-client-allowlist.yaml.",
        "enabled": True,
        "conditions": [{"condition": "any-client", "configuration": {}}],
        "profiles": [POLICY_NAME],
    }


def load_allowlist(path: Path = ALLOWLIST) -> dict:
    return yaml.safe_load(path.read_text())


def jwks_sha256(base: Path, rel: str) -> str:
    return hashlib.sha256((base / rel).read_bytes()).hexdigest()


class OwnedPrefix:
    def __init__(self, prefix: str):
        self.prefix = prefix

    def __contains__(self, name: object) -> bool:
        return isinstance(name, str) and (name == self.prefix or name.startswith(self.prefix + "-"))


def render(template: dict, decl: dict, base: Path) -> dict:
    out = copy.deepcopy(template)
    wanted = [build_client(decl, c, base) for c in decl["clients"]]
    kept = [c for c in out.get("clients", []) if TIER_ATTR not in (c.get("attributes") or {})]
    out["clients"] = kept + wanted
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
    # The guard stays LAST in both lists (the parity self-test mutates `[-1]`).
    hardening_profiles, hardening_policies = build_hardening()
    # Every client profile/policy named `openbank-mcp*` is owned here, so a renamed one cannot linger.
    owned_profiles = owned_policies = OwnedPrefix(POLICY_NAME)
    profiles = out.get("clientProfiles") or {}
    profiles["profiles"] = [p for p in profiles.get("profiles") or [] if p.get("name") not in owned_profiles] + [
        *hardening_profiles, build_profile()]
    out["clientProfiles"] = profiles
    policies = out.get("clientPolicies") or {}
    policies["policies"] = [p for p in policies.get("policies") or [] if p.get("name") not in owned_policies] + [
        *hardening_policies, build_policy()]
    out["clientPolicies"] = policies
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
