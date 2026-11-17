#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""ADR-0385 D1/D2/D7 against a real, disposable Keycloak running the COMMITTED customers realm.

Imports `customers-realm-template.json` as committed (placeholders replaced by throwaway values),
on the exact image digest `openbank-infra/docker/keycloak/Dockerfile` pins, and drives the MCP
reference clients end to end: PAR, PKCE, exact/loopback redirects, the fresh-passkey flow override
against a live SSO cookie, a real WebAuthn assertion, DPoP-bound tokens (Tier L), private_key_jwt
at the token AND refresh endpoints (Tier H), offline refresh, rotation reuse.

Test-only fixtures, added to the disposable realm and nowhere else:
  * the MCP reference clients are enabled (they are `enabled: false` in the template) and the
    Tier H client's committed key set is replaced by an ephemeral one whose private half the
    test holds;
  * one user with a password AND a passkey. The password is reachable only through a separate
    `local-cookie-seeder` client, whose sole job is to plant a genuine SSO cookie so the test can
    prove the MCP flow ignores it. The passkey is a software authenticator: an ephemeral P-256
    key registered as a `webauthn-passwordless` credential, answering Keycloak's real challenge.

Requires Docker, OpenSSL and Python's standard library only.
"""

import base64
import hashlib
import html
import http.client
import itertools
import json
import pathlib
import re
import secrets
import subprocess
import tempfile
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
KC = ROOT / "openbank-infra/gitops/components/keycloak"
ALLOWLIST = ROOT / "openbank-mcp-service/src/main/resources/mcp-client-allowlist.yaml"
H, L = "mcp-hosted-reference", "mcp-local-reference"
H_CB, L_CB = "https://client.test/cb", "http://127.0.0.1:53682/callback"
USER, PARTY = "mcp-it@example.test", "11111111-1111-4111-8111-111111111111"
USER_ID = "9a7e0c1e-0385-4d00-8000-00000000a11c"
COUNTER = itertools.count(int(time.time()))


def run(*args, data=None):
    result = subprocess.run(args, input=data, capture_output=True, timeout=120)
    if result.returncode:
        raise RuntimeError(f"{args[0]} failed; output suppressed to avoid leaking credentials")
    return result.stdout


def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


def claims(token):
    part = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))


def cbor(o):
    def head(major, n):
        if n < 24:
            return bytes([major << 5 | n])
        if n < 256:
            return bytes([major << 5 | 24, n])
        return bytes([major << 5 | 25]) + n.to_bytes(2, "big")
    if isinstance(o, int):
        return head(0, o) if o >= 0 else head(1, -1 - o)
    if isinstance(o, bytes):
        return head(2, len(o)) + o
    return head(5, len(o)) + b"".join(cbor(k) + cbor(v) for k, v in o.items())


class EcKey:
    """An ephemeral P-256 key driven through the openssl CLI."""

    def __init__(self, path):
        self.path = str(path)
        run("openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", self.path)
        point = run("openssl", "ec", "-in", self.path, "-pubout", "-outform", "DER")[-64:]
        self.x, self.y = point[:32], point[32:]

    def jwk(self):
        return {"kty": "EC", "crv": "P-256", "x": b64(self.x), "y": b64(self.y)}

    def thumbprint(self):
        canon = json.dumps({"crv": "P-256", "kty": "EC", "x": b64(self.x), "y": b64(self.y)}, separators=(",", ":"))
        return b64(hashlib.sha256(canon.encode()).digest())

    def sign_der(self, data):
        return run("openssl", "dgst", "-sha256", "-sign", self.path, data=data)

    def jws(self, header, payload):
        content = b64(json.dumps(header).encode()) + "." + b64(json.dumps(payload).encode())
        der = self.sign_der(content.encode())
        # DER ECDSA-Sig-Value -> raw r||s (RFC 7518 3.4)
        i = 4 if der[1] < 0x80 else 5
        rlen = der[i - 1]
        r = der[i:i + rlen]
        slen = der[i + rlen + 1]
        s = der[i + rlen + 2:i + rlen + 2 + slen]
        raw = r.lstrip(b"\0").rjust(32, b"\0") + s.lstrip(b"\0").rjust(32, b"\0")
        return content + "." + b64(raw)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


class Response:
    def __init__(self, status, headers, body):
        self.status, self.headers, self.text = status, headers, body

    @property
    def location(self):
        return self.headers.get("Location", "") or ""

    def json(self):
        return json.loads(self.text)

    def query(self):
        return urllib.parse.parse_qs(urllib.parse.urlparse(self.location).query)


class Browser:
    """A minimal cookie-carrying user agent. Keycloak marks its cookies Secure; this disposable
    instance is plain http, so the jar returns them regardless (test transport only)."""

    def __init__(self, base):
        self.base, self.cookies = base, {}
        self.opener = urllib.request.build_opener(NoRedirect)

    def request(self, method, url, data=None, headers=None):
        hdrs = dict(headers or {})
        if url.startswith(self.base) and self.cookies:
            hdrs["Cookie"] = "; ".join(f"{k}={v}" for k, v in self.cookies.items())
        body = urllib.parse.urlencode(data).encode() if data is not None else None
        req = urllib.request.Request(url, data=body, headers=hdrs, method=method)
        try:
            resp = self.opener.open(req, timeout=30)
        except urllib.error.HTTPError as error:
            resp = error
        with resp:
            text = resp.read().decode(errors="replace")
            for raw in resp.headers.get_all("Set-Cookie") or []:
                name, _, value = raw.split(";", 1)[0].partition("=")
                if value:
                    self.cookies[name.strip()] = value
                else:
                    self.cookies.pop(name.strip(), None)
            return Response(resp.status, resp.headers, text)

    def follow(self, resp):
        """Follow redirects that stay on Keycloak; stop at the client's redirect_uri."""
        for _ in range(10):
            if resp.status not in (301, 302, 303) or not resp.location.startswith(self.base):
                return resp
            resp = self.request("GET", resp.location)
        return resp


class McpClientAdmission(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="ob-mcp-admission-test-")
        cls.addClassCleanup(cls.tmp.cleanup)
        work = pathlib.Path(cls.tmp.name)
        cls.hkey, cls.passkey, cls.dpop = EcKey(work / "h.pem"), EcKey(work / "p.pem"), EcKey(work / "d.pem")
        cls.cred_id = secrets.token_bytes(32)

        text = (KC / "customers-realm-template.json").read_text()
        text = re.sub(r"__([A-Z0-9_]+)__", lambda _m: secrets.token_urlsafe(24), text)
        realm = json.loads(text)
        realm["sslRequired"] = "none"
        clients = {c["clientId"]: c for c in realm["clients"]}
        for cid in (H, L):
            assert "openbank.mcp.tier" in clients[cid]["attributes"], f"{cid} is not a derived MCP client"
            clients[cid]["enabled"] = True
        clients[H]["redirectUris"].append(H_CB)
        clients[H]["attributes"]["jwks.string"] = json.dumps({"keys": [{**cls.hkey.jwk(), "alg": "ES256", "use": "sig", "kid": "it-1"}]})
        realm["clients"].append({"clientId": "local-cookie-seeder", "publicClient": True, "enabled": True,
                                 "standardFlowEnabled": True, "directAccessGrantsEnabled": False,
                                 "redirectUris": ["https://client.test/*"], "protocol": "openid-connect",
                                 "authenticationFlowBindingOverrides": {"browser": "it-password-flow"}})
        realm["authenticationFlows"].append({
            "id": "it-password-flow", "alias": "it-password", "providerId": "basic-flow", "topLevel": True, "builtIn": False,
            "authenticationExecutions": [
                {"authenticator": "auth-cookie", "requirement": "ALTERNATIVE", "priority": 5, "authenticatorFlow": False},
                {"authenticator": "auth-username-password-form", "requirement": "ALTERNATIVE", "priority": 10, "authenticatorFlow": False}]})
        cose = cbor({1: 2, 3: -7, -1: 1, -2: cls.passkey.x, -3: cls.passkey.y})
        cred = {"aaguid": "00000000-0000-0000-0000-000000000000", "credentialId": base64.b64encode(cls.cred_id).decode(),
                "counter": 0, "attestationStatement": None, "credentialPublicKey": base64.b64encode(cose).decode(),
                "attestationStatementFormat": "none", "transports": ["internal", "hybrid"]}
        cls.password = secrets.token_urlsafe(18)
        realm.setdefault("users", []).append({
            "id": USER_ID, "username": USER, "email": USER, "firstName": "Mcp", "lastName": "It",
            "enabled": True, "emailVerified": True, "attributes": {"party_id": [PARTY]},
            "credentials": [{"type": "password", "value": cls.password, "temporary": False},
                            {"type": "webauthn-passwordless", "userLabel": "it", "credentialData": json.dumps(cred), "secretData": "{}"}],
            "realmRoles": ["ROLE_CUSTOMER", "offline_access", "default-roles-openbank-customers"]})
        realm_file = work / "realm.json"
        realm_file.write_text(json.dumps(realm))
        realm_file.chmod(0o644)

        dockerfile = (ROOT / "openbank-infra/docker/keycloak/Dockerfile").read_text()
        image = re.search(r"FROM (quay\.io/keycloak/keycloak:\$\{KEYCLOAK_VERSION\}@sha256:[a-f0-9]+)", dockerfile).group(1)
        version = re.search(r"ARG KEYCLOAK_VERSION=([0-9.]+)", dockerfile).group(1)
        features = re.search(r"ENV KC_FEATURES=(\S+)", dockerfile).group(1)
        image = image.replace("${KEYCLOAK_VERSION}", version)
        cls.container = "ob-mcp-admission-test-" + secrets.token_hex(6)
        cls.addClassCleanup(lambda: run("docker", "rm", "-f", cls.container))
        run("docker", "run", "--detach", "--name", cls.container, "--memory=1g",
            "-e", "JAVA_OPTS_APPEND=-XX:ActiveProcessorCount=2", "-p", "127.0.0.1::8080",
            "-v", f"{realm_file}:/opt/keycloak/data/import/realm.json:ro", image,
            "start-dev", "--import-realm", f"--features={features}")
        port = run("docker", "port", cls.container, "8080/tcp").decode().strip().splitlines()[0].rsplit(":", 1)[1]
        cls.origin = f"http://127.0.0.1:{port}"
        cls.iss = f"{cls.origin}/realms/openbank-customers"
        oidc = f"{cls.iss}/protocol/openid-connect"
        cls.par_url, cls.auth_url, cls.token_url = f"{oidc}/ext/par/request", f"{oidc}/auth", f"{oidc}/token"
        cls.registrations_url = f"{oidc}/registrations"
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(cls.iss, timeout=2) as response:
                    if response.status == 200:
                        return
            except (urllib.error.URLError, TimeoutError, http.client.RemoteDisconnected, ConnectionError):
                time.sleep(1)
        raise RuntimeError("Disposable Keycloak did not become ready; inspect its local logs")

    # ---- protocol helpers -------------------------------------------------------------------
    def post(self, url, data, headers=None):
        return Browser(self.origin).request("POST", url, data, headers)

    def client_auth(self):
        now = int(time.time())
        assertion = self.hkey.jws({"alg": "ES256", "typ": "JWT", "kid": "it-1"},
                                  {"iss": H, "sub": H, "aud": self.iss, "jti": str(uuid.uuid4()), "iat": now, "exp": now + 60})
        return {"client_assertion_type": "urn:ietf:params:oauth:client-assertion-type:jwt-bearer", "client_assertion": assertion}

    def proof(self, key=None, htu=None):
        key = key or self.dpop
        return key.jws({"typ": "dpop+jwt", "alg": "ES256", "jwk": key.jwk()},
                       {"jti": str(uuid.uuid4()), "htm": "POST", "htu": htu or self.token_url, "iat": int(time.time())})

    def par(self, client, redirect, authenticate=True, method="S256", pkce=True, **extra):
        verifier = b64(secrets.token_bytes(32))
        data = {"client_id": client, "response_type": "code", "redirect_uri": redirect,
                "scope": "openid offline_access", "state": b64(secrets.token_bytes(8))}
        if pkce:
            data["code_challenge"] = b64(hashlib.sha256(verifier.encode()).digest()) if method == "S256" else verifier
            data["code_challenge_method"] = method
        if client == H and authenticate:
            data.update(self.client_auth())
        data.update(extra)
        return self.post(self.par_url, data), verifier

    def request_uri(self, client, redirect, **extra):
        resp, verifier = self.par(client, redirect, **extra)
        self.assertEqual(resp.status, 201, resp.text[:200])
        return resp.json()["request_uri"], verifier

    def authorize(self, browser, client, request_uri, **extra):
        return browser.follow(browser.request("GET", self.auth_url + "?" + urllib.parse.urlencode(
            {"client_id": client, "request_uri": request_uri, **extra})))

    @staticmethod
    def is_passkey_challenge(resp):
        return resp.status == 200 and 'id="webauth"' in resp.text

    def assert_passkey_challenge(self, resp, why):
        self.assertTrue(self.is_passkey_challenge(resp), f"{why}: expected the WebAuthn challenge, got "
                                                         f"{resp.status} {resp.location[:120]}")
        self.assertNotIn("code", resp.query())

    def assert_passkey(self, browser, page):
        """The software authenticator answers Keycloak's real challenge."""
        challenge = re.search(r'challenge\s*:\s*"([^"]+)"', page.text).group(1)
        rp_id = re.search(r'rpId\s*:\s*"([^"]*)"', page.text).group(1)
        action = html.unescape(re.search(r'id="webauth" action="([^"]+)"', page.text).group(1))
        client_data = json.dumps({"type": "webauthn.get", "challenge": challenge, "origin": self.origin,
                                  "crossOrigin": False}).encode()
        auth_data = hashlib.sha256(rp_id.encode()).digest() + bytes([0x05]) + next(COUNTER).to_bytes(4, "big")
        signature = self.passkey.sign_der(auth_data + hashlib.sha256(client_data).digest())
        return browser.follow(browser.request("POST", action, {
            "clientDataJSON": b64(client_data), "authenticatorData": b64(auth_data), "signature": b64(signature),
            "credentialId": b64(self.cred_id), "userHandle": b64(USER_ID.encode()), "error": ""}))

    def sso_browser(self):
        """A browser holding a genuine SSO session for the user (via the test-only password client)."""
        browser = Browser(self.origin)
        page = browser.request("GET", self.auth_url + "?" + urllib.parse.urlencode(
            {"client_id": "local-cookie-seeder", "response_type": "code", "redirect_uri": "https://client.test/x", "scope": "openid"}))
        action = html.unescape(re.search(r'id="kc-form-login"[^>]*action="([^"]+)"', page.text).group(1))
        resp = browser.follow(browser.request("POST", action, {"username": USER, "password": self.password}))
        self.assertIn("code", resp.query(), "fixture: the password client must log in")
        self.assertIn("KEYCLOAK_IDENTITY", browser.cookies, "fixture: an SSO cookie must exist")
        return browser

    def code_for(self, client, redirect, **extra):
        browser = self.sso_browser()
        uri, verifier = self.request_uri(client, redirect, **extra)
        resp = self.assert_passkey(browser, self.authorize(browser, client, uri))
        query = resp.query()
        self.assertIn("code", query, f"fresh passkey must yield a code: {resp.status} {resp.location[:120]}")
        return query, verifier

    # ---- D2.1 PAR / PKCE / redirects ----------------------------------------------------------
    def test_front_channel_authorize_without_par_refused(self):
        for client, redirect in ((H, H_CB), (L, L_CB)):
            with self.subTest(client=client):
                challenge = b64(hashlib.sha256(b"x" * 43).digest())
                resp = Browser(self.origin).request("GET", self.auth_url + "?" + urllib.parse.urlencode({
                    "client_id": client, "response_type": "code", "redirect_uri": redirect, "scope": "openid",
                    "code_challenge": challenge, "code_challenge_method": "S256"}))
                self.assertEqual(resp.query().get("error"), ["invalid_request"])
                self.assertNotIn("code", resp.query())

    def test_tier_h_par_requires_client_authentication(self):
        resp, _ = self.par(H, H_CB, authenticate=False)
        self.assertEqual(resp.status, 401, resp.text[:200])
        resp, _ = self.par(H, H_CB)
        self.assertEqual(resp.status, 201, resp.text[:200])
        self.assertLessEqual(resp.json()["expires_in"], 60, "request_uri must live <= 60 s")

    def test_pkce_absent_or_plain_rejected(self):
        for client, redirect in ((H, H_CB), (L, L_CB)):
            for name, kwargs in (("absent", {"pkce": False}), ("plain", {"method": "plain"})):
                with self.subTest(client=client, pkce=name):
                    resp, _ = self.par(client, redirect, **kwargs)
                    self.assertEqual(resp.status, 400, resp.text[:200])

    def test_redirects_exact_and_loopback_only(self):
        for redirect in ("http://127.0.0.1:53682/callback", "http://127.0.0.1:61001/callback", "http://[::1]:53682/callback"):
            with self.subTest(accepted=redirect):
                self.assertEqual(self.par(L, redirect)[0].status, 201)
        for client, redirect in ((L, "http://localhost:53682/callback"), (L, "http://127.0.0.1:53682/other"),
                                 (H, "https://client.test/cb/extra"), (H, "https://mcp-client.example.evil/oauth/callback")):
            with self.subTest(rejected=redirect):
                self.assertEqual(self.par(client, redirect)[0].status, 400)

    def test_request_uri_bound_to_its_client_and_request(self):
        browser = self.sso_browser()
        uri, _ = self.request_uri(L, L_CB)
        resp = self.authorize(browser, H, uri)
        self.assertNotIn("code", resp.query())
        self.assertFalse(self.is_passkey_challenge(resp), "another client_id must not reach the login page")
        # parameters beside the request_uri cannot change the stored request
        uri, _ = self.request_uri(L, L_CB)
        resp = self.authorize(browser, L, uri, prompt="none", redirect_uri="http://127.0.0.1:1/x")
        self.assertNotIn("code", resp.query())
        self.assertNotIn("127.0.0.1:1/", resp.location)

    @unittest.expectedFailure
    def test_foreign_resource_is_invalid_target(self):
        # ADR-0385 D1 / D7.4. Keycloak 26.8.1 does NOT implement RFC 8707: a foreign `resource` is
        # silently ignored (PAR 201). Kept as an expected failure so the day Keycloak (or a provider
        # we add) enforces it, this flips to an unexpected success and the gap is re-examined. The
        # audience half of D1 IS enforced: see test_tier_l_dpop_bound_tokens (aud == mcp-service only).
        resp, _ = self.par(L, L_CB, resource="https://evil.example/mcp")
        self.assertEqual(resp.status, 400)
        self.assertIn("invalid_target", resp.text)

    # ---- D2.2 fresh passkey: an SSO cookie never skips it -------------------------------------
    def test_sso_cookie_does_not_skip_the_passkey(self):
        browser = self.sso_browser()
        for client, redirect in ((L, L_CB), (H, H_CB)):
            with self.subTest(client=client):
                uri, _ = self.request_uri(client, redirect)
                self.assert_passkey_challenge(self.authorize(browser, client, uri), "plain authorize with SSO cookie")

    def test_sso_cookie_prompt_none_is_login_required(self):
        browser = self.sso_browser()
        uri, _ = self.request_uri(L, L_CB, prompt="none")
        query = self.authorize(browser, L, uri).query()
        self.assertEqual(query.get("error"), ["login_required"])
        self.assertNotIn("code", query)

    def test_sso_cookie_prompt_create_reaches_no_registration(self):
        # Keycloak answers 500 here today: the realm's registration form flow carries
        # `webauthn-register-passwordless` (a required action, not a FormAction). The assertion is
        # the security property only — no registration form, no code — not the status code.
        browser = self.sso_browser()
        uri, _ = self.request_uri(L, L_CB, prompt="create")
        resp = self.authorize(browser, L, uri)
        self.assertNotIn("kc-register-form", resp.text)
        self.assertNotIn("code", resp.query())

    def test_sso_cookie_kc_action_and_hints_do_not_skip(self):
        browser = self.sso_browser()
        for extra in ({"kc_action": "UPDATE_PASSWORD"}, {"kc_action": "delete_account"}, {"kc_action": "UPDATE_EMAIL"},
                      {"kc_action": "webauthn-register-passwordless"}, {"login_hint": USER},
                      {"kc_idp_hint": "google"}, {"max_age": "999999"}):
            with self.subTest(**extra):
                uri, _ = self.request_uri(L, L_CB, **extra)
                self.assert_passkey_challenge(self.authorize(browser, L, uri), str(extra))

    def test_registration_unreachable_from_mcp_authorize(self):
        browser = self.sso_browser()
        uri, _ = self.request_uri(L, L_CB)
        self.assertNotIn("registrations", self.authorize(browser, L, uri).text)
        resp = browser.follow(browser.request("GET", self.registrations_url + "?" + urllib.parse.urlencode(
            {"client_id": L, "response_type": "code", "redirect_uri": L_CB, "scope": "openid"})))
        self.assertNotIn("kc-register-form", resp.text)

    # ---- D1 / D7.1 Tier L: DPoP ---------------------------------------------------------------
    def exchange_l(self, query, verifier, proof=True, key=None):
        headers = {"DPoP": self.proof(key)} if proof else {}
        return self.post(self.token_url, {"grant_type": "authorization_code", "client_id": L, "code": query["code"][0],
                                          "redirect_uri": L_CB, "code_verifier": verifier}, headers)

    def test_tier_l_requires_dpop_without_dpop_jkt(self):
        # The client attribute itself: no dpop_jkt at PAR, no proof at the token endpoint.
        query, verifier = self.code_for(L, L_CB)
        resp = self.exchange_l(query, verifier, proof=False)
        self.assertEqual(resp.status, 400, resp.text[:200])
        self.assertIn("DPoP", resp.text)

    def test_tier_l_dpop_jkt_at_par_binds_the_key(self):
        query, verifier = self.code_for(L, L_CB, dpop_jkt=self.dpop.thumbprint())
        other = EcKey(pathlib.Path(self.tmp.name) / "other.pem")
        resp = self.exchange_l(query, verifier, key=other)
        self.assertEqual(resp.status, 400, resp.text[:200])

    def test_tier_l_dpop_bound_tokens(self):
        query, verifier = self.code_for(L, L_CB, dpop_jkt=self.dpop.thumbprint())
        self.assertEqual(query.get("iss"), [self.iss], "RFC 9207 iss in the authorization response")
        resp = self.exchange_l(query, verifier)
        self.assertEqual(resp.status, 200, resp.text[:200])
        tokens = resp.json()
        access = claims(tokens["access_token"])
        self.assertEqual(tokens["token_type"], "DPoP")
        self.assertEqual(access["cnf"]["jkt"], self.dpop.thumbprint())
        self.assertEqual(access["aud"] if isinstance(access["aud"], list) else [access["aud"]], ["openbank-mcp-service"])
        self.assertEqual(access["azp"], L)
        self.assertEqual(access["party_id"], PARTY)
        self.assertNotIn("realm_access", access, "no realm role may reach an MCP token")
        self.assertLessEqual(access["exp"] - access["iat"], 300)
        refresh = tokens["refresh_token"]
        self.assertEqual(claims(refresh)["typ"], "Offline", "offline_access must yield an offline refresh token")
        no_proof = self.post(self.token_url, {"grant_type": "refresh_token", "client_id": L, "refresh_token": refresh})
        self.assertEqual(no_proof.status, 400)
        rotated = self.post(self.token_url, {"grant_type": "refresh_token", "client_id": L, "refresh_token": refresh},
                            {"DPoP": self.proof()})
        self.assertEqual(rotated.status, 200, rotated.text[:200])
        reuse = self.post(self.token_url, {"grant_type": "refresh_token", "client_id": L, "refresh_token": refresh},
                          {"DPoP": self.proof()})
        self.assertEqual(reuse.status, 400, "a rotated refresh token must not be reusable")
        successor = self.post(self.token_url, {"grant_type": "refresh_token", "client_id": L,
                                               "refresh_token": rotated.json()["refresh_token"]}, {"DPoP": self.proof()})
        self.assertEqual(successor.status, 400, "reuse must revoke the family")

    # ---- D1 / D7.2 Tier H: private_key_jwt at token AND refresh --------------------------------
    def test_tier_h_client_authentication_at_refresh(self):
        query, verifier = self.code_for(H, H_CB)
        resp = self.post(self.token_url, {"grant_type": "authorization_code", "client_id": H, "code": query["code"][0],
                                          "redirect_uri": H_CB, "code_verifier": verifier, **self.client_auth()})
        self.assertEqual(resp.status, 200, resp.text[:200])
        tokens = resp.json()
        self.assertEqual(tokens["token_type"], "Bearer")
        access = claims(tokens["access_token"])
        self.assertEqual(access["aud"] if isinstance(access["aud"], list) else [access["aud"]], ["openbank-mcp-service"])
        refresh = tokens["refresh_token"]
        unauth = self.post(self.token_url, {"grant_type": "refresh_token", "client_id": H, "refresh_token": refresh})
        self.assertIn(unauth.status, (400, 401))
        self.assertEqual(unauth.json().get("error"), "invalid_client")
        rotated = self.post(self.token_url, {"grant_type": "refresh_token", "client_id": H, "refresh_token": refresh,
                                             **self.client_auth()})
        self.assertEqual(rotated.status, 200, rotated.text[:200])
        reuse = self.post(self.token_url, {"grant_type": "refresh_token", "client_id": H, "refresh_token": refresh,
                                           **self.client_auth()})
        self.assertEqual(reuse.status, 400)
        successor = self.post(self.token_url, {"grant_type": "refresh_token", "client_id": H,
                                               "refresh_token": rotated.json()["refresh_token"], **self.client_auth()})
        self.assertEqual(successor.status, 400, "reuse must revoke the family (confidential client too)")


if __name__ == "__main__":
    unittest.main(verbosity=2)
