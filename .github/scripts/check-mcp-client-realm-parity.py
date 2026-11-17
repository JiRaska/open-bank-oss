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
    `auth-cookie` and no sub-flow anywhere (ADR-0385 D2.2);
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
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
ALLOWLIST = ROOT / "openbank-mcp-service/src/main/resources/mcp-client-allowlist.yaml"
TEMPLATE = ROOT / "openbank-infra/gitops/components/keycloak/customers-realm-template.json"
TIER_ATTR = "openbank.mcp.tier"
MAX_EXCEPTION_DAYS = 183
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
    seen = set()
    for c in decl.get("clients") or []:
        cid = c.get("client_id")
        if cid in seen:
            f.append(f"allowlist: client `{cid}` declared twice")
        seen.add(cid)
        tier = c.get("tier")
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
        for e in execs:
            if e.get("authenticator") == "auth-cookie":
                f.append(f"realm: flow `{flow_alias}` contains auth-cookie — an SSO cookie would skip the "
                         f"fresh passkey (ADR-0385 D2.2)")
            if e.get("authenticatorFlow") or e.get("flowAlias"):
                f.append(f"realm: flow `{flow_alias}` calls a sub-flow ({e.get('flowAlias')}) — keep it flat")
            if e.get("requirement") != "REQUIRED":
                f.append(f"realm: flow `{flow_alias}` execution {e.get('authenticator')} is "
                         f"{e.get('requirement')}, must be REQUIRED (no alternative path)")

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
        for key, why in (("oauth2.device.authorization.grant.enabled", "device grant"), ("oidc.ciba.grant.enabled", "CIBA grant")):
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
        if c.get("fullScopeAllowed") is not False:
            f.append(f"{p}: fullScopeAllowed must be false")
        if mappings.get(cid) != ["offline_access"]:
            f.append(f"{p}: scopeMappings must be exactly ['offline_access'], got {mappings.get(cid)}")
        if (c.get("authenticationFlowBindingOverrides") or {}).get("browser") != flow_id:
            f.append(f"{p}: browser flow override must bind the MCP flow {flow_id} — that binding is the control (ADR-0385 D2.2)")
        auds = [m for m in c.get("protocolMappers") or [] if m.get("protocolMapper") == "oidc-audience-mapper"]
        aud_values = sorted({(m.get("config") or {}).get("included.custom.audience") or
                             (m.get("config") or {}).get("included.client.audience") for m in auds})
        if aud_values != [decl.get("audience")]:
            f.append(f"{p}: audience mappers must add exactly [{decl.get('audience')}], got {aud_values}")
    return f


def check(decl: dict, realm: dict, base: Path, today: dt.date) -> list[str]:
    f, jwks = check_allowlist(decl, base, today)
    return f + check_realm(decl, realm, jwks)


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
