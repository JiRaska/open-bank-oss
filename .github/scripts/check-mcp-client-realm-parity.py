#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""ADR-0385 D1: every declared MCP client's realm config matches its tier.

Reads the MCP client allowlist (openbank-mcp-service/src/main/resources/mcp-client-allowlist.yaml)
and the customers realm (openbank-infra/gitops/components/keycloak/customers-realm-template.json)
and fails on any mismatch. It does NOT import the generator that writes those realm entries
(gen-mcp-realm-clients.py): the point is a second, independent reading, so a hand edit of the
template or a bug in the generator both turn this red.

Asserted, per declared client:
  * it exists in the realm, carries `openbank.mcp.tier` equal to its declared tier, and no realm
    client carries that marker without a declaration;
  * Tier H: publicClient=false with a client authenticator — `client-jwt` whose inline key set is
    byte-equal to the committed, sha256-pinned JWKS, or `client-secret` ONLY under a recorded
    `secret_exception` (risk acceptance, <= 183 days, `expires` not in the past);
  * Tier L: publicClient=true, `dpop.bound.access.tokens=true`, loopback redirects on 127.0.0.1 /
    [::1] only (never `localhost`, never a pinned port, RFC 8252);
  * every client: code flow only (no direct-access, implicit, device, CIBA grant, no service
    account), PAR required, PKCE S256, exact redirect URIs equal to the declaration (no wildcard),
    access token <= 300 s, fullScopeAllowed=false, scope-mapped to exactly offline_access, an
    audience mapper for the declared audience and no other audience, and the browser flow bound
    to the dedicated MCP flow;
  * the MCP flow: top-level, only REQUIRED executions, passwordless WebAuthn present, no
    `auth-cookie` and no sub-flow anywhere (ADR-0385 D2.2), and `openbank-mcp-withheld-code`
    as its LAST step, after the passkey (ADR-0385 D3);
  * every client's `openbank.mcp.resource` equals the allowlist resource (RFC 8707 binding);
  * `party_id` a declared user-profile attribute viewable and editable by admin only;
  * an enabled `any-client` client policy binding the `openbank-mcp-client-guard` executor
    (#16667: no token exchange for or of an MCP client);
  * per tier, an enabled policy whose ONLY condition is `client-attributes` on exactly
    `openbank.mcp.tier=<tier>` (one value — two values for one key match nothing in Keycloak
    26.8.1) and whose profiles carry Keycloak's built-in pkce-enforcer, reject-implicit-grant,
    reject-ropc-grant, plus dpop-bind-enforcer for Tier L (review of #16672);
  * the realm: refreshTokenMaxReuse == 0 and revokeRefreshToken == true (never loosened).

    python3 .github/scripts/check-mcp-client-realm-parity.py             # enforce
    python3 .github/scripts/check-mcp-client-realm-parity.py --self-test # falsify it
"""
from __future__ import annotations

import argparse
import copy
import datetime as dt
import hashlib
import json
import runpy
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
ALLOWLIST = ROOT / "openbank-mcp-service/src/main/resources/mcp-client-allowlist.yaml"
TEMPLATE = ROOT / "openbank-infra/gitops/components/keycloak/customers-realm-template.json"
TIER_ATTR = "openbank.mcp.tier"
RESOURCE_ATTR = "openbank.mcp.resource"
WITHHELD = "openbank-mcp-withheld-code"
NATIVE_HANDOFF = "openbank-mcp-native-handoff"
GUARD = "openbank-mcp-client-guard"
EXCHANGE_ATTR = "openbank.mcp.exchange"
EXCHANGE_MAX_LIFESPAN_S = 60
MAX_EXCEPTION_DAYS = 183
BUILTIN_EXECUTORS = {
    "H": {"pkce-enforcer", "reject-implicit-grant", "reject-ropc-grant"},
    "L": {"pkce-enforcer", "reject-implicit-grant", "reject-ropc-grant", "dpop-bind-enforcer"},
}
EXPECTED_MAPPERS = ("sub", "party-id", "mcp-service-audience")
LOOPBACK_PREFIXES = ("http://127.0.0.1/", "http://[::1]/")


def _true(v) -> bool:
    return str(v).lower() == "true"


def check_allowlist(decl: dict, base: Path, today: dt.date) -> tuple[list[str], dict]:
    f: list[str] = []
    jwks: dict[str, str] = {}
    for key in ("resource", "audience"):
        if not decl.get(key):
            f.append(f"allowlist: `{key}` is required (ADR-0385 D1)")
    if not (decl.get("flow") or {}).get("id") or not (decl.get("flow") or {}).get("alias"):
        f.append("allowlist: `flow.id` and `flow.alias` are required")
    native_flow = decl.get("native_flow") or {}
    if not native_flow.get("id") or not native_flow.get("alias"):
        f.append("allowlist: `native_flow.id` and `native_flow.alias` are required")
    elif any(native_flow[key] == (decl.get("flow") or {}).get(key) for key in ("id", "alias")):
        f.append("allowlist: native_flow must have a distinct ID and alias")
    seen = set()
    for c in decl.get("clients") or []:
        cid = c.get("client_id")
        if cid in seen:
            f.append(f"allowlist: client `{cid}` declared twice")
        seen.add(cid)
        tier = c.get("tier")
        if "browser_flow" in c and (c["browser_flow"] != "native-passkey" or tier != "H"):
            f.append(f"allowlist `{cid}`: browser_flow must be native-passkey on a Tier H client")
        uris = c.get("redirect_uris") or []
        if not uris:
            f.append(f"allowlist `{cid}`: no redirect_uris")
        if any("*" in u for u in uris):
            f.append(f"allowlist `{cid}`: wildcard redirect URI — redirects must be exact")
        if tier == "H":
            if any(not u.startswith("https://") for u in uris):
                f.append(f"allowlist `{cid}`: Tier H redirect must be a fixed https URI (ADR-0385 D1)")
            auth = c.get("auth") or {}
            method = auth.get("method")
            if method == "private_key_jwt":
                path = base / str(auth.get("jwks_file"))
                if not path.is_file():
                    f.append(f"allowlist `{cid}`: jwks_file {auth.get('jwks_file')} does not exist")
                else:
                    raw = path.read_bytes()
                    if hashlib.sha256(raw).hexdigest() != auth.get("jwks_sha256"):
                        f.append(f"allowlist `{cid}`: jwks_sha256 does not match {auth.get('jwks_file')} — "
                                 f"a changed key set is a reviewed re-pin, never silent")
                    jwks[cid] = json.dumps(json.loads(raw), separators=(",", ":"))
                if c.get("secret_exception"):
                    f.append(f"allowlist `{cid}`: secret_exception on a private_key_jwt client")
            elif method == "client_secret":
                ex = c.get("secret_exception")
                if not ex:
                    f.append(f"allowlist `{cid}`: client_secret without a recorded secret_exception "
                             f"(ADR-0385 D1: a secret is an exception only)")
                else:
                    try:
                        accepted = dt.date.fromisoformat(str(ex.get("accepted")))
                        expires = dt.date.fromisoformat(str(ex.get("expires")))
                    except ValueError:
                        f.append(f"allowlist `{cid}`: secret_exception needs ISO `accepted` and `expires` dates")
                    else:
                        if expires < today:
                            f.append(f"allowlist `{cid}`: client-secret exception EXPIRED on {expires} — "
                                     f"disable the client or record a new owner risk acceptance")
                        if (expires - accepted).days > MAX_EXCEPTION_DAYS:
                            f.append(f"allowlist `{cid}`: secret_exception spans {(expires - accepted).days} days, "
                                     f"over the {MAX_EXCEPTION_DAYS}-day (6 month) cap")
                    if not ex.get("risk_acceptance"):
                        f.append(f"allowlist `{cid}`: secret_exception names no risk_acceptance record")
            else:
                f.append(f"allowlist `{cid}`: Tier H auth.method must be private_key_jwt or client_secret, got {method!r}")
        elif tier == "L":
            for u in uris:
                if not u.startswith(LOOPBACK_PREFIXES):
                    f.append(f"allowlist `{cid}`: Tier L redirect {u} must be http://127.0.0.1/... or "
                             f"http://[::1]/... with no port (RFC 8252; `localhost` is not accepted)")
            if c.get("auth"):
                f.append(f"allowlist `{cid}`: a Tier L client is public and declares no auth")
        else:
            f.append(f"allowlist `{cid}`: tier must be H or L, got {tier!r}")
    return f, jwks


def check_party_id_profile(realm: dict) -> list[str]:
    comps = (realm.get("components") or {}).get("org.keycloak.userprofile.UserProfileProvider") or []
    raw = [v for c in comps for v in ((c.get("config") or {}).get("kc.user.profile.config") or [])]
    if len(raw) != 1:
        return [f"realm: exactly one declarative user profile is required, found {len(raw)} — `party_id` "
                f"must be a declared admin-only attribute"]
    try:
        profile = json.loads(raw[0])
    except ValueError:
        return ["realm: the user profile config is not valid JSON"]
    attrs = [a for a in profile.get("attributes") or [] if a.get("name") == "party_id"]
    if len(attrs) != 1:
        return ["realm: user profile does not declare `party_id` — a customer could set the party their MCP "
                "token and SCA consume are bound to"]
    perms = attrs[0].get("permissions") or {}
    out = []
    for kind in ("view", "edit"):
        if sorted(perms.get(kind) or []) != ["admin"]:
            out.append(f"realm: user profile `party_id` {kind} permission is {perms.get(kind)!r}, must be ['admin'] only")
    if attrs[0].get("annotations", {}).get("inputType") or attrs[0].get("selector") or attrs[0].get("required"):
        out.append("realm: user profile `party_id` must carry no inputType, selector or required (no user-facing form)")
    if profile.get("unmanagedAttributePolicy") not in (None, "ADMIN_VIEW", "ADMIN_EDIT"):
        out.append(f"realm: unmanagedAttributePolicy {profile.get('unmanagedAttributePolicy')!r} lets users edit "
                   f"undeclared attributes")
    return out


def check_realm(decl: dict, realm: dict, jwks: dict[str, str]) -> list[str]:
    f: list[str] = []
    clients = {c.get("clientId"): c for c in realm.get("clients") or []}
    declared = {c["client_id"]: c for c in decl.get("clients") or [] if c.get("client_id")}
    flow_id = (decl.get("flow") or {}).get("id")
    flow_alias = (decl.get("flow") or {}).get("alias")

    if realm.get("refreshTokenMaxReuse") != 0:
        f.append(f"realm: refreshTokenMaxReuse is {realm.get('refreshTokenMaxReuse')!r}, must stay 0 (ADR-0385 D1)")
    if realm.get("revokeRefreshToken") is not True:
        f.append("realm: revokeRefreshToken must stay true (reuse revokes the family, ADR-0385 D1)")

    flows = [x for x in realm.get("authenticationFlows") or [] if x.get("alias") == flow_alias]
    if len(flows) != 1:
        f.append(f"realm: flow `{flow_alias}` must exist exactly once, found {len(flows)}")
    else:
        flow = flows[0]
        if flow.get("id") != flow_id:
            f.append(f"realm: flow `{flow_alias}` id is {flow.get('id')!r}, declared {flow_id!r} — overrides bind by id")
        if not flow.get("topLevel"):
            f.append(f"realm: flow `{flow_alias}` must be top-level")
        execs = flow.get("authenticationExecutions") or []
        if not any(e.get("authenticator") == "webauthn-authenticator-passwordless" for e in execs):
            f.append(f"realm: flow `{flow_alias}` has no passwordless WebAuthn step")
        # ADR-0385 D3: the withheld-code step must run AFTER the passkey (it reads the asserted
        # credential and the authenticated party), and be the LAST step: anything after it would
        # run once the SCA challenge is already spent.
        ordered = [e.get("authenticator") for e in sorted(execs, key=lambda e: e.get("priority") or 0)]
        if WITHHELD not in ordered:
            f.append(f"realm: flow `{flow_alias}` has no {WITHHELD} step — the code would be issued "
                     f"without an SCA consume (ADR-0385 D3)")
        elif ordered[-1] != WITHHELD or "webauthn-authenticator-passwordless" not in ordered[:ordered.index(WITHHELD)]:
            f.append(f"realm: flow `{flow_alias}` must run {WITHHELD} last, after the passkey (ADR-0385 D3)")
        for e in execs:
            if e.get("authenticator") == "auth-cookie":
                f.append(f"realm: flow `{flow_alias}` contains auth-cookie — an SSO cookie would skip the "
                         f"fresh passkey (ADR-0385 D2.2)")
            if e.get("authenticatorFlow") or e.get("flowAlias"):
                f.append(f"realm: flow `{flow_alias}` calls a sub-flow ({e.get('flowAlias')}) — keep it flat")
            if e.get("requirement") != "REQUIRED":
                f.append(f"realm: flow `{flow_alias}` execution {e.get('authenticator')} is "
                         f"{e.get('requirement')}, must be REQUIRED (no alternative path)")

    native_flow = decl.get("native_flow") or {}
    native_alias = native_flow.get("alias")
    if native_alias and realm.get("browserFlow") == native_alias:
        f.append("realm: native flow may only be bound to a declared Tier H client")
    for cid, client in clients.items():
        if cid not in declared and native_flow.get("id") and (
            (client.get("authenticationFlowBindingOverrides") or {}).get("browser") == native_flow["id"]
        ):
            f.append(f"realm client `{cid}`: native flow may only be bound to a declared Tier H client")
    matches = [x for x in realm.get("authenticationFlows") or [] if x.get("alias") == native_alias]
    if len(matches) != 1:
        f.append(f"realm: native flow `{native_alias}` must exist exactly once, found {len(matches)}")
    else:
        native = matches[0]
        if native.get("id") != native_flow.get("id") or native.get("topLevel") is not True:
            f.append(f"realm: native flow `{native_alias}` must match the declared ID and be top-level")
        execs = native.get("authenticationExecutions") or []
        ordered = sorted(execs, key=lambda e: e.get("priority") or 0)
        if [e.get("authenticator") for e in ordered] != [NATIVE_HANDOFF, WITHHELD] or any(
            e.get("requirement") != "REQUIRED" or e.get("authenticatorFlow") or e.get("flowAlias")
            for e in ordered
        ):
            f.append(f"realm: native flow `{native_alias}` must contain exactly two REQUIRED flat steps, "
                     f"{NATIVE_HANDOFF} then {WITHHELD}")

    # #16667: the guard executor must be bound to every client by an enabled any-client policy.
    profiles = {p.get("name"): p for p in (realm.get("clientProfiles") or {}).get("profiles") or []}
    bound = False
    for pol in (realm.get("clientPolicies") or {}).get("policies") or []:
        conds = [c.get("condition") for c in pol.get("conditions") or []]
        execs_ = [e.get("executor") for n in pol.get("profiles") or [] for e in (profiles.get(n) or {}).get("executors") or []]
        if pol.get("enabled") is True and conds == ["any-client"] and GUARD in execs_:
            bound = True
    if not bound:
        f.append(f"realm: no enabled any-client client policy binds the {GUARD} executor — MCP clients could "
                 f"token-exchange (#16667) and the withheld-code step would have no request facts")

    # Review of #16672 (6): Keycloak's built-in executors, bound per tier by the tier attribute.
    for tier in sorted({c.get("tier") for c in decl.get("clients") or []} & set(BUILTIN_EXECUTORS)):
        executors: list[dict] = []
        for pol in (realm.get("clientPolicies") or {}).get("policies") or []:
            if pol.get("enabled") is not True or len(pol.get("conditions") or []) != 1:
                continue
            cond = pol["conditions"][0]
            conf = cond.get("configuration") or {}
            try:
                attrs = json.loads(conf.get("attributes")) if isinstance(conf.get("attributes"), str) else None
            except ValueError:
                attrs = None
            if (cond.get("condition") != "client-attributes" or conf.get("is_negative_logic") not in (None, False, "false")
                    or attrs != [{"key": TIER_ATTR, "value": tier}]):
                continue
            executors.extend(e for name in pol.get("profiles") or []
                             for e in (profiles.get(name) or {}).get("executors") or [])
        missing = BUILTIN_EXECUTORS[tier] - {e.get("executor") for e in executors}
        if any(e.get("executor") == "reject-ropc-grant"
               and (e.get("configuration") or {}).get("auto-configure") != "false" for e in executors):
            f.append(f"realm: {TIER_ATTR}={tier} reject-ropc-grant requires explicit auto-configure=false")
        if missing:
            f.append(f"realm: no enabled client-attributes policy on {TIER_ATTR}={tier} binds the built-in "
                     f"executor(s) {sorted(missing)} (review of #16672: a weakened client attribute must not reopen them)")

    # Review of #16672: the MCP token's party_id claim and the withheld-code consume's
    # expectedPartyId both come from the user attribute `party_id`, so a customer must never be able
    # to set it (account console, registration, update-profile required action): it is a declared
    # user-profile attribute that ONLY an admin may view or edit.
    f.extend(check_party_id_profile(realm))

    for cid, c in clients.items():
        if TIER_ATTR in (c.get("attributes") or {}) and cid not in declared:
            f.append(f"realm client `{cid}` carries {TIER_ATTR} but is not in the allowlist")

    mappings = {m.get("client"): sorted(m.get("roles") or []) for m in realm.get("scopeMappings") or []}
    for cid, d in declared.items():
        c = clients.get(cid)
        if c is None:
            f.append(f"`{cid}`: declared in the allowlist but absent from the realm — run gen-mcp-realm-clients.py")
            continue
        a = c.get("attributes") or {}
        tier = d.get("tier")
        p = f"`{cid}` (Tier {tier})"
        if a.get(RESOURCE_ATTR) != decl.get("resource"):
            f.append(f"{p}: {RESOURCE_ATTR} is {a.get(RESOURCE_ATTR)!r}, the allowlist resource is "
                     f"{decl.get('resource')!r} (RFC 8707 binding, ADR-0385 D1)")
        if a.get(TIER_ATTR) != tier:
            f.append(f"{p}: realm {TIER_ATTR}={a.get(TIER_ATTR)!r} disagrees with the allowlist")
        if tier == "H":
            if c.get("publicClient") is not False:
                f.append(f"{p}: publicClient must be false — no public hosted clients (ADR-0385 D1)")
            auth = (d.get("auth") or {}).get("method")
            cat = c.get("clientAuthenticatorType")
            if auth == "private_key_jwt":
                if cat != "client-jwt":
                    f.append(f"{p}: clientAuthenticatorType is {cat!r}, must be client-jwt (private_key_jwt)")
                if not _true(a.get("use.jwks.string")) or _true(a.get("use.jwks.url")):
                    f.append(f"{p}: the key set must be inline (use.jwks.string) — a jwks.url fetch is not pinned")
                elif cid in jwks:
                    try:
                        realm_jwks = json.dumps(json.loads(a.get("jwks.string") or "null"), separators=(",", ":"))
                    except json.JSONDecodeError:
                        realm_jwks = None
                    if realm_jwks != jwks[cid]:
                        f.append(f"{p}: realm jwks.string differs from the pinned committed key set")
            elif auth == "client_secret":
                if cat != "client-secret":
                    f.append(f"{p}: clientAuthenticatorType is {cat!r}, must be client-secret under its exception")
            if cat not in ("client-jwt", "client-secret"):
                f.append(f"{p}: no client authenticator ({cat!r})")
            if _true(a.get("dpop.bound.access.tokens")):
                pass  # DPoP is allowed for Tier H when the platform supports it
        elif tier == "L":
            if c.get("publicClient") is not True:
                f.append(f"{p}: a local client is public; publicClient must be true")
            if not _true(a.get("dpop.bound.access.tokens")):
                f.append(f"{p}: dpop.bound.access.tokens must be true — a local client without DPoP is "
                         f"not admitted (ADR-0385 D1)")
        if c.get("standardFlowEnabled") is not True:
            f.append(f"{p}: standardFlowEnabled must be true (authorization code)")
        for key, why in (("directAccessGrantsEnabled", "direct-access grant"), ("implicitFlowEnabled", "implicit grant"),
                         ("serviceAccountsEnabled", "service account")):
            if c.get(key) is not False:
                f.append(f"{p}: {key} must be false — no {why} (ADR-0385 D1)")
        for key, why in (("oauth2.device.authorization.grant.enabled", "device grant"), ("oidc.ciba.grant.enabled", "CIBA grant"),
                         ("standard.token.exchange.enabled", "token exchange")):
            if _true(a.get(key)) or key not in a:
                f.append(f"{p}: {key} must be explicitly false — no {why}")
        if not _true(a.get("require.pushed.authorization.requests")):
            f.append(f"{p}: require.pushed.authorization.requests must be true (PAR, ADR-0385 D2.1)")
        if a.get("pkce.code.challenge.method") != "S256":
            f.append(f"{p}: pkce.code.challenge.method must be S256, got {a.get('pkce.code.challenge.method')!r}")
        try:
            if int(a.get("access.token.lifespan", "0")) <= 0 or int(a.get("access.token.lifespan")) > 300:
                raise ValueError
        except (TypeError, ValueError):
            f.append(f"{p}: access.token.lifespan must be set and <= 300 s, got {a.get('access.token.lifespan')!r}")
        if sorted(c.get("redirectUris") or []) != sorted(d.get("redirect_uris") or []):
            f.append(f"{p}: realm redirectUris {c.get('redirectUris')} differ from the allowlist {d.get('redirect_uris')}")
        if any("*" in u for u in c.get("redirectUris") or []):
            f.append(f"{p}: wildcard redirect URI in the realm")
        if c.get("defaultClientScopes") != []:
            f.append(f"{p}: defaultClientScopes must be exactly [], got {c.get('defaultClientScopes')}")
        if c.get("optionalClientScopes") != ["offline_access"]:
            f.append(f"{p}: optionalClientScopes must be exactly ['offline_access'], got {c.get('optionalClientScopes')}")
        if c.get("consentRequired") is not False:
            f.append(f"{p}: consentRequired must be false (consent is the D3 SCA challenge, not a Keycloak screen)")
        mapper_names = sorted(str(m.get("name")) for m in c.get("protocolMappers") or [])
        if mapper_names != sorted(EXPECTED_MAPPERS):
            f.append(f"{p}: protocol mappers must be exactly {sorted(EXPECTED_MAPPERS)}, got {mapper_names}")
        if c.get("fullScopeAllowed") is not False:
            f.append(f"{p}: fullScopeAllowed must be false")
        if mappings.get(cid) != ["offline_access"]:
            f.append(f"{p}: scopeMappings must be exactly ['offline_access'], got {mappings.get(cid)}")
        expected_flow_id = native_flow.get("id") if d.get("browser_flow") == "native-passkey" else flow_id
        if (c.get("authenticationFlowBindingOverrides") or {}).get("browser") != expected_flow_id:
            f.append(f"{p}: browser flow override must bind the MCP flow {expected_flow_id} — "
                     "that binding is the control (ADR-0385 D2.2)")
        auds = [m for m in c.get("protocolMappers") or [] if m.get("protocolMapper") == "oidc-audience-mapper"]
        aud_values = sorted({(m.get("config") or {}).get("included.custom.audience") or
                             (m.get("config") or {}).get("included.client.audience") for m in auds})
        if aud_values != [decl.get("audience")]:
            f.append(f"{p}: audience mappers must add exactly [{decl.get('audience')}], got {aud_values}")
    return f


def check_exchange(decl: dict, realm: dict) -> list[str]:
    """ADR-0383 D4: the exchange requester, its audiences and their scopes are exactly as declared."""
    f: list[str] = []
    ex = decl.get("exchange")
    marked = [c for c in realm.get("clients") or [] if EXCHANGE_ATTR in (c.get("attributes") or {})]
    marked_scopes = [s for s in realm.get("clientScopes") or [] if EXCHANGE_ATTR in (s.get("attributes") or {})]
    if not ex:
        if marked or marked_scopes:
            f.append(f"realm carries {EXCHANGE_ATTR} entries but the allowlist declares no `exchange`")
        return f
    req = ex.get("requester") or {}
    rid = req.get("client_id")
    auds = {a.get("client_id"): a.get("scope") for a in ex.get("audiences") or []}
    if rid != decl.get("audience"):
        f.append(f"exchange: requester `{rid}` must equal the MCP audience `{decl.get('audience')}` - Keycloak only "
                 f"exchanges a subject token whose aud names the requester")
    if not auds or any(not k or not v for k, v in auds.items()):
        f.append("exchange: every audience needs a client_id and a scope")
    declared_ids = {rid} | set(auds)
    for c in marked:
        if c.get("clientId") not in declared_ids:
            f.append(f"realm client `{c.get('clientId')}` carries {EXCHANGE_ATTR} but is not declared under `exchange`")
    for s in marked_scopes:
        if s.get("name") not in set(auds.values()):
            f.append(f"realm client scope `{s.get('name')}` carries {EXCHANGE_ATTR} but is not declared")
    clients = {c.get("clientId"): c for c in realm.get("clients") or []}
    mapped = {m.get("client") for m in realm.get("scopeMappings") or []}

    def no_flow(c: dict, p: str) -> None:
        for key in ("standardFlowEnabled", "implicitFlowEnabled", "directAccessGrantsEnabled", "serviceAccountsEnabled"):
            if c.get(key) is not False:
                f.append(f"{p}: {key} must be false - it obtains tokens only by exchange")
        if c.get("publicClient") is True:
            f.append(f"{p}: publicClient must not be true")
        if c.get("fullScopeAllowed") is not False:
            f.append(f"{p}: fullScopeAllowed must be false - no realm role may ride along")
        if c.get("defaultClientScopes") != []:
            f.append(f"{p}: defaultClientScopes must be exactly []")
        if c.get("redirectUris"):
            f.append(f"{p}: must declare no redirect URIs")
        if c.get("clientId") in mapped:
            f.append(f"{p}: must have no scopeMappings (no realm role)")

    r = clients.get(rid)
    if r is None:
        f.append(f"exchange requester `{rid}` absent from the realm - run gen-mcp-realm-clients.py")
    else:
        p = f"exchange requester `{rid}`"
        a = r.get("attributes") or {}
        if a.get(EXCHANGE_ATTR) != "requester":
            f.append(f"{p}: {EXCHANGE_ATTR} must be `requester`")
        no_flow(r, p)
        if not _true(a.get("standard.token.exchange.enabled")):
            f.append(f"{p}: standard.token.exchange.enabled must be true")
        if r.get("publicClient") is not False:
            f.append(f"{p}: publicClient must be false - the requester authenticates")
        if r.get("clientAuthenticatorType") != "client-secret" or r.get("secret") != req.get("secret_placeholder") \
                or not str(r.get("secret", "")).startswith("__"):
            f.append(f"{p}: must authenticate with the declared secret PLACEHOLDER, never a literal secret")
        try:
            life = int(a.get("access.token.lifespan"))
        except (TypeError, ValueError):
            life = 0
        if not 0 < life <= EXCHANGE_MAX_LIFESPAN_S:
            f.append(f"{p}: access.token.lifespan must be set and <= {EXCHANGE_MAX_LIFESPAN_S} s (ADR-0383 D4)")
        if sorted(r.get("optionalClientScopes") or []) != sorted(auds.values()):
            f.append(f"{p}: optionalClientScopes must be exactly the declared read scopes {sorted(auds.values())}")
        if r.get("protocolMappers"):
            f.append(f"{p}: must carry no protocol mappers of its own")
    scopes = {s.get("name"): s for s in realm.get("clientScopes") or []}
    for cid, scope in auds.items():
        c = clients.get(cid)
        p = f"exchange audience `{cid}`"
        if c is None:
            f.append(f"{p}: absent from the realm")
        else:
            a = c.get("attributes") or {}
            if a.get(EXCHANGE_ATTR) != "audience":
                f.append(f"{p}: {EXCHANGE_ATTR} must be `audience`")
            no_flow(c, p)
            if _true(a.get("standard.token.exchange.enabled")):
                f.append(f"{p}: an audience must not itself exchange")
            if c.get("optionalClientScopes"):
                f.append(f"{p}: optionalClientScopes must be empty")
            if c.get("bearerOnly") is not True:
                f.append(f"{p}: must be bearerOnly - an audience holds no credential")
        sc = scopes.get(scope)
        if sc is None:
            f.append(f"{p}: scope `{scope}` absent from the realm")
            continue
        mappers = sc.get("protocolMappers") or []
        targets = [(m.get("protocolMapper"), (m.get("config") or {}).get("included.client.audience")) for m in mappers]
        if targets != [("oidc-audience-mapper", cid)]:
            f.append(f"scope `{scope}`: must carry exactly one audience mapper for `{cid}`, got {targets}")
    for cid, c in clients.items():
        if cid != rid and cid in auds and _true((c.get("attributes") or {}).get("standard.token.exchange.enabled")):
            f.append(f"`{cid}`: exchange enabled outside the declared requester")
    return f


def check(decl: dict, realm: dict, base: Path, today: dt.date) -> list[str]:
    f, jwks = check_allowlist(decl, base, today)
    return f + check_realm(decl, realm, jwks) + check_exchange(decl, realm)


def self_test() -> int:
    decl0 = yaml.safe_load(ALLOWLIST.read_text())
    realm0 = json.loads(TEMPLATE.read_text())
    base = ALLOWLIST.parent
    today = dt.date(2026, 11, 17)
    cases: list[tuple[str, bool]] = []

    def run(name, mutate_decl=None, mutate_realm=None, expect_clean=False, needle=None):
        d, r = copy.deepcopy(decl0), copy.deepcopy(realm0)
        if mutate_decl:
            mutate_decl(d)
        if mutate_realm:
            mutate_realm(r)
        f = check(d, r, base, today)
        ok = (f == []) if expect_clean else (bool(f) and (needle is None or any(needle in x for x in f)))
        cases.append((name, ok))
        print(f"  {'PASS' if ok else 'FAIL'}  {name}" + ("" if ok else f"  -> {f}"))

    def client(r, cid):
        return next(c for c in r["clients"] if c["clientId"] == cid)

    def flow(r):
        return next(x for x in r["authenticationFlows"] if x["alias"] == decl0["flow"]["alias"])

    H = next(c["client_id"] for c in decl0["clients"] if c["tier"] == "H")
    L = next(c["client_id"] for c in decl0["clients"] if c["tier"] == "L")

    run("committed allowlist + realm pass", expect_clean=True)

    hosted_reference = "mcp-hosted-reference"
    native_id = decl0["native_flow"]["id"]
    native_alias = decl0["native_flow"]["alias"]

    def select_native(d, cid):
        next(c for c in d["clients"] if c["client_id"] == cid)["browser_flow"] = "native-passkey"

    opted_in = copy.deepcopy(decl0)
    select_native(opted_in, hosted_reference)
    render = runpy.run_path(str(Path(__file__).with_name("gen-mcp-realm-clients.py")))["render"]
    generated = render(realm0, opted_in, base)
    expected = copy.deepcopy(realm0)
    client(expected, hosted_reference)["authenticationFlowBindingOverrides"]["browser"] = native_id
    generated_clean = generated == expected and check(opted_in, generated, base, today) == []
    cases.append(("generator changes only the selected hosted client's browser flow", generated_clean))
    print(f"  {'PASS' if generated_clean else 'FAIL'}  {cases[-1][0]}")

    run("native opt-in requires realm parity", mutate_decl=lambda d: select_native(d, hosted_reference),
        needle="browser flow override")
    run("native opt-in rejects Tier L", mutate_decl=lambda d: select_native(d, L), needle="on a Tier H client")
    run("native override without declaration rejected",
        mutate_realm=lambda r: client(r, hosted_reference)["authenticationFlowBindingOverrides"].update(browser=native_id),
        needle="browser flow override")
    run("native flow cannot replace the realm browser default",
        mutate_realm=lambda r: r.update(browserFlow=native_alias), needle="only be bound to a declared Tier H client")

    def remove_native_withheld(r):
        native = next(f for f in r["authenticationFlows"] if f["alias"] == native_alias)
        native["authenticationExecutions"].pop()

    run("native flow requires the final withheld-code step", mutate_realm=remove_native_withheld,
        needle="exactly two REQUIRED flat steps")
    run("Tier H publicClient=true flagged", mutate_realm=lambda r: client(r, H).update(publicClient=True), needle="publicClient")
    run("Tier H without client authenticator flagged",
        mutate_realm=lambda r: client(r, H).pop("clientAuthenticatorType"), needle="client authenticator")
    run("Tier H jwks.url instead of pinned inline key set flagged",
        mutate_realm=lambda r: client(r, H)["attributes"].update({"use.jwks.url": "true", "use.jwks.string": "false"}), needle="inline")
    run("Tier H realm key set differing from the pin flagged",
        mutate_realm=lambda r: client(r, H)["attributes"].update({"jwks.string": '{"keys":[]}'}), needle="pinned")
    run("allowlist jwks_sha256 mismatch flagged",
        mutate_decl=lambda d: next(c for c in d["clients"] if c["tier"] == "H")["auth"].update(jwks_sha256="0" * 64), needle="jwks_sha256")
    run("Tier L dpop.bound.access.tokens=false flagged",
        mutate_realm=lambda r: client(r, L)["attributes"].update({"dpop.bound.access.tokens": "false"}), needle="dpop")
    run("Tier L confidential flagged", mutate_realm=lambda r: client(r, L).update(publicClient=False), needle="public")
    for key in ("directAccessGrantsEnabled", "implicitFlowEnabled", "serviceAccountsEnabled"):
        run(f"{key}=true flagged", mutate_realm=lambda r, k=key: client(r, L).update({k: True}), needle=key)
    run("device grant enabled flagged",
        mutate_realm=lambda r: client(r, H)["attributes"].update({"oauth2.device.authorization.grant.enabled": "true"}), needle="device")
    run("PAR not required flagged",
        mutate_realm=lambda r: client(r, H)["attributes"].update({"require.pushed.authorization.requests": "false"}), needle="PAR")
    run("PKCE plain flagged", mutate_realm=lambda r: client(r, L)["attributes"].update({"pkce.code.challenge.method": "plain"}), needle="S256")
    run("access token lifespan 3600 flagged",
        mutate_realm=lambda r: client(r, L)["attributes"].update({"access.token.lifespan": "3600"}), needle="lifespan")
    run("realm redirect not in the allowlist flagged",
        mutate_realm=lambda r: client(r, H)["redirectUris"].append("https://evil.example/cb"), needle="differ")
    run("wildcard redirect flagged", mutate_decl=lambda d: d["clients"][0]["redirect_uris"].append("https://x.example/*"), needle="wildcard")
    run("Tier L `localhost` redirect flagged",
        mutate_decl=lambda d: next(c for c in d["clients"] if c["tier"] == "L")["redirect_uris"].append("http://localhost/callback"),
        needle="localhost")
    run("Tier L pinned-port redirect flagged",
        mutate_decl=lambda d: next(c for c in d["clients"] if c["tier"] == "L")["redirect_uris"].append("http://127.0.0.1:8080/cb"),
        needle="RFC 8252")
    run("Tier H plain-http redirect flagged",
        mutate_decl=lambda d: next(c for c in d["clients"] if c["tier"] == "H")["redirect_uris"].append("http://x.example/cb"),
        needle="https")
    run("browser flow override removed flagged",
        mutate_realm=lambda r: client(r, H).update(authenticationFlowBindingOverrides={}), needle="override")
    run("auth-cookie alternative in the MCP flow flagged",
        mutate_realm=lambda r: flow(r)["authenticationExecutions"].insert(
            0, {"authenticator": "auth-cookie", "requirement": "ALTERNATIVE", "priority": 1}), needle="auth-cookie")
    run("MCP flow execution ALTERNATIVE flagged",
        mutate_realm=lambda r: flow(r)["authenticationExecutions"][0].update(requirement="ALTERNATIVE"), needle="REQUIRED")
    run("withheld-code step removed flagged",
        mutate_realm=lambda r: flow(r).update(authenticationExecutions=[
            e for e in flow(r)["authenticationExecutions"] if e["authenticator"] != WITHHELD]), needle="without an SCA consume")
    run("withheld-code step before the passkey flagged",
        mutate_realm=lambda r: next(e for e in flow(r)["authenticationExecutions"] if e["authenticator"] == WITHHELD).update(priority=1),
        needle="last, after the passkey")
    run("guard policy disabled flagged",
        mutate_realm=lambda r: r["clientPolicies"]["policies"][-1].update(enabled=False), needle=GUARD)
    run("guard policy narrowed to a grant-type condition flagged",
        mutate_realm=lambda r: r["clientPolicies"]["policies"][-1].update(conditions=[{"condition": "grant-type"}]), needle=GUARD)
    run("guard executor dropped from the profile flagged",
        mutate_realm=lambda r: r["clientProfiles"]["profiles"][-1].update(executors=[]), needle=GUARD)
    def tier_policy(r, tier):
        return next(p for p in r["clientPolicies"]["policies"] if p["name"] == f"openbank-mcp-tier-{tier.lower()}")
    run("built-in reject-ropc-grant dropped flagged",
        mutate_realm=lambda r: [p.update(executors=[e for e in p["executors"] if e["executor"] != "reject-ropc-grant"])
                                for p in r["clientProfiles"]["profiles"]], needle="reject-ropc-grant")
    run("reject-ropc-grant without explicit auto-configure flagged",
        mutate_realm=lambda r: [e.update(configuration={}) for p in r["clientProfiles"]["profiles"]
                                for e in p["executors"] if e["executor"] == "reject-ropc-grant"],
        needle="reject-ropc-grant requires explicit auto-configure=false")
    run("Tier L DPoP profile unbound flagged",
        mutate_realm=lambda r: tier_policy(r, "L").update(profiles=["openbank-mcp-oauth-hardening"]), needle="dpop-bind-enforcer")
    run("tier condition with two values for one key (inert in Keycloak) flagged",
        mutate_realm=lambda r: tier_policy(r, "H")["conditions"][0]["configuration"].update(attributes=json.dumps(
            [{"key": TIER_ATTR, "value": "H"}, {"key": TIER_ATTR, "value": "L"}])), needle="=H")
    run("tier condition with negative logic flagged",
        mutate_realm=lambda r: tier_policy(r, "H")["conditions"][0]["configuration"].update(is_negative_logic=True), needle="=H")
    run("bound resource attribute drift flagged",
        mutate_realm=lambda r: client(r, H)["attributes"].update({RESOURCE_ATTR: "https://evil.example/mcp"}), needle="RFC 8707")
    def profile(r, mutate):
        comp = r["components"]["org.keycloak.userprofile.UserProfileProvider"][0]["config"]
        doc = json.loads(comp["kc.user.profile.config"][0])
        mutate(doc)
        comp["kc.user.profile.config"] = [json.dumps(doc)]

    def party(doc):
        return next(a for a in doc["attributes"] if a["name"] == "party_id")

    run("party_id editable by the user flagged",
        mutate_realm=lambda r: profile(r, lambda d: party(d)["permissions"].update(edit=["admin", "user"])), needle="edit permission")
    run("party_id viewable by the user flagged",
        mutate_realm=lambda r: profile(r, lambda d: party(d)["permissions"].update(view=["admin", "user"])), needle="view permission")
    run("party_id undeclared flagged",
        mutate_realm=lambda r: profile(r, lambda d: d.update(attributes=[a for a in d["attributes"] if a["name"] != "party_id"])),
        needle="does not declare")
    run("user profile removed flagged", mutate_realm=lambda r: r["components"].pop("org.keycloak.userprofile.UserProfileProvider"),
        needle="declarative user profile")
    run("unmanaged attributes user-editable flagged",
        mutate_realm=lambda r: profile(r, lambda d: d.update(unmanagedAttributePolicy="ENABLED")), needle="unmanagedAttributePolicy")
    run("MCP flow id drift flagged", mutate_realm=lambda r: flow(r).update(id="other"), needle="overrides bind by id")
    run("undeclared realm client with the MCP marker flagged",
        mutate_realm=lambda r: r["clients"].append({**copy.deepcopy(client(r, L)), "clientId": "mcp-ghost"}), needle="not in the allowlist")
    run("declared client missing from the realm flagged",
        mutate_realm=lambda r: r.update(clients=[c for c in r["clients"] if c["clientId"] != L]), needle="absent from the realm")
    run("tier marker disagreeing with the allowlist flagged",
        mutate_realm=lambda r: client(r, L)["attributes"].update({TIER_ATTR: "H"}), needle="disagrees")
    run("extra realm role scope-mapped flagged",
        mutate_realm=lambda r: next(m for m in r["scopeMappings"] if m["client"] == L)["roles"].append("ROLE_CUSTOMER"),
        needle="scopeMappings")
    run("extra audience mapper flagged",
        mutate_realm=lambda r: client(r, L)["protocolMappers"].append(
            {"protocolMapper": "oidc-audience-mapper", "config": {"included.client.audience": "openbank-edge"}}), needle="audience")
    run("token exchange enabled flagged",
        mutate_realm=lambda r: client(r, L)["attributes"].update({"standard.token.exchange.enabled": "true"}), needle="token exchange")
    run("token exchange attribute absent flagged",
        mutate_realm=lambda r: client(r, H)["attributes"].pop("standard.token.exchange.enabled"), needle="token exchange")
    run("defaultClientScopes non-empty flagged",
        mutate_realm=lambda r: client(r, L).update(defaultClientScopes=["profile"]), needle="defaultClientScopes")
    run("optionalClientScopes extra scope flagged",
        mutate_realm=lambda r: client(r, H).update(optionalClientScopes=["offline_access", "email"]), needle="optionalClientScopes")
    run("consentRequired=true flagged", mutate_realm=lambda r: client(r, L).update(consentRequired=True), needle="consentRequired")
    run("extra protocol mapper flagged",
        mutate_realm=lambda r: client(r, H)["protocolMappers"].append({"name": "email", "protocolMapper": "oidc-usermodel-property-mapper"}),
        needle="protocol mappers")
    run("party-id mapper removed flagged",
        mutate_realm=lambda r: client(r, L).update(protocolMappers=[m for m in client(r, L)["protocolMappers"] if m["name"] != "party-id"]),
        needle="protocol mappers")
    run("realm refreshTokenMaxReuse raised flagged", mutate_realm=lambda r: r.update(refreshTokenMaxReuse=1), needle="refreshTokenMaxReuse")
    run("realm revokeRefreshToken off flagged", mutate_realm=lambda r: r.update(revokeRefreshToken=False), needle="revokeRefreshToken")

    def secret_client(d, expires, accepted="2026-10-01", ra="risk-acceptance #0"):
        c = next(c for c in d["clients"] if c["tier"] == "H")
        c["auth"] = {"method": "client_secret"}
        c["secret_exception"] = {"accepted": accepted, "expires": expires, "risk_acceptance": ra}

    def secret_realm(r):
        client(r, H)["clientAuthenticatorType"] = "client-secret"

    run("client secret under a live exception passes",
        mutate_decl=lambda d: secret_client(d, "2027-03-31"), mutate_realm=secret_realm, expect_clean=True)
    run("client secret past its exception expiry flagged",
        mutate_decl=lambda d: secret_client(d, "2026-11-16"), mutate_realm=secret_realm, needle="EXPIRED")
    run("client secret exception over 6 months flagged",
        mutate_decl=lambda d: secret_client(d, "2027-06-30", accepted="2026-11-01"), mutate_realm=secret_realm, needle="cap")
    run("client secret without an exception flagged",
        mutate_decl=lambda d: next(c for c in d["clients"] if c["tier"] == "H").update(auth={"method": "client_secret"}),
        mutate_realm=secret_realm, needle="exception only")

    RID = decl0["exchange"]["requester"]["client_id"]
    AUD = decl0["exchange"]["audiences"][0]["client_id"]
    SCOPE = decl0["exchange"]["audiences"][0]["scope"]

    def scope_of(r, name):
        return next(s for s in r["clientScopes"] if s["name"] == name)

    run("exchange requester missing flagged",
        mutate_realm=lambda r: r.update(clients=[c for c in r["clients"] if c["clientId"] != RID]), needle="requester")
    run("exchange requester with a direct grant flagged",
        mutate_realm=lambda r: client(r, RID).update(directAccessGrantsEnabled=True), needle="directAccessGrantsEnabled")
    run("exchange requester with a service account flagged",
        mutate_realm=lambda r: client(r, RID).update(serviceAccountsEnabled=True), needle="serviceAccountsEnabled")
    run("exchange requester lifespan 300 flagged",
        mutate_realm=lambda r: client(r, RID)["attributes"].update({"access.token.lifespan": "300"}), needle="lifespan")
    run("exchange requester extra scope (scope escalation) flagged",
        mutate_realm=lambda r: client(r, RID)["optionalClientScopes"].append("accounts:read"), needle="optionalClientScopes")
    run("exchange requester default scope flagged",
        mutate_realm=lambda r: client(r, RID).update(defaultClientScopes=["profile"]), needle="defaultClientScopes")
    run("exchange requester full scope flagged",
        mutate_realm=lambda r: client(r, RID).update(fullScopeAllowed=True), needle="fullScopeAllowed")
    run("exchange requester literal secret flagged",
        mutate_realm=lambda r: client(r, RID).update(secret="s3cr3t"), needle="PLACEHOLDER")
    run("exchange disabled on requester flagged",
        mutate_realm=lambda r: client(r, RID)["attributes"].update({"standard.token.exchange.enabled": "false"}),
        needle="standard.token.exchange.enabled")
    run("exchange requester not the MCP audience flagged",
        mutate_decl=lambda d: d["exchange"]["requester"].update(client_id="other"), needle="must equal the MCP audience")
    run("exchange audience that can itself exchange flagged",
        mutate_realm=lambda r: client(r, AUD)["attributes"].update({"standard.token.exchange.enabled": "true"}), needle="exchange")
    run("exchange audience not bearer-only flagged",
        mutate_realm=lambda r: client(r, AUD).update(bearerOnly=False), needle="bearerOnly")
    run("exchange requester made public flagged",
        mutate_realm=lambda r: client(r, RID).update(publicClient=True), needle="publicClient")
    run("exchange audience with a standard flow flagged",
        mutate_realm=lambda r: client(r, AUD).update(standardFlowEnabled=True), needle="standardFlowEnabled")
    run("scope audience mapper retargeted flagged",
        mutate_realm=lambda r: scope_of(r, SCOPE)["protocolMappers"][0]["config"].update(
            {"included.client.audience": "openbank-ledger-service"}), needle="exactly one audience mapper")
    run("scope with a second audience mapper flagged",
        mutate_realm=lambda r: scope_of(r, SCOPE)["protocolMappers"].append(copy.deepcopy(scope_of(r, SCOPE)["protocolMappers"][0])),
        needle="exactly one audience mapper")
    run("undeclared exchange-marked client flagged",
        mutate_realm=lambda r: r["clients"].append({**copy.deepcopy(client(r, AUD)), "clientId": "openbank-ledger-service"}),
        needle="not declared under `exchange`")
    run("exchange entries without a declaration flagged",
        mutate_decl=lambda d: d.pop("exchange"), needle="declares no `exchange`")

    failed = [n for n, ok in cases if not ok]
    print(f"self-test: {len(cases) - len(failed)}/{len(cases)} passed")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    decl = yaml.safe_load(ALLOWLIST.read_text())
    realm = json.loads(TEMPLATE.read_text())
    findings = check(decl, realm, ALLOWLIST.parent, dt.datetime.now(dt.timezone.utc).date())
    print(f"SUBJECTS={len(decl.get('clients') or [])}")
    for x in findings:
        print(f"::error file={TEMPLATE.relative_to(ROOT)}::{x}")
    if findings:
        print(f"mcp-client-realm-parity: {len(findings)} finding(s). The allowlist is the source; "
              f"regenerate with python3 .github/scripts/gen-mcp-realm-clients.py and fix the declaration.")
        return 1
    print(f"mcp-client-realm-parity: {len(decl.get('clients') or [])} MCP client(s) match their tier.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
