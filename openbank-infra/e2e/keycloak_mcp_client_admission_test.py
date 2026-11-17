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

ADR-0385 D3 / D2.3 / #16667 run against the bank's Keycloak extension
(openbank-infra/docker/keycloak/extension), built from the committed Dockerfile's `extension-jar`
stage and mounted into the pinned image's providers directory. sca-service is a STUB here (an
in-process HTTP server answering initiate/consume like sca-service does, single-use CAS included);
the hash it receives is recomputed below from the shared canonical-context fixture's rules, and
the same fixture holds sca-service's and the extension's own implementations.

Requires Docker (with buildx), OpenSSL and Python's standard library only.
"""

import base64
import hashlib
import html
import http.client
import http.server
import itertools
import json
import pathlib
import re
import secrets
import subprocess
import tempfile
import threading
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
YOUNG_USER, YOUNG_PARTY = "mcp-it-young@example.test", "22222222-2222-4222-8222-222222222222"
YOUNG_USER_ID = "9a7e0c1e-0385-4d00-8000-00000000a11d"
RESOURCE = "https://api.open-bank.tech/mcp"
# The withheld-code session lifespan configured for THIS disposable server. The extension caps it
# at 300 s (ADR-0385 D3.6, unit-tested in ExtensionTests); a short value lets the expiry case run
# in seconds rather than five minutes.
SESSION_LIFESPAN_S = 30


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


class ScaStub:
    """sca-service's initiate/consume contract, in-process: single-use consume (CAS), the not-yet-
    approved 400, party / approvalRequestId / payloadSha256 compared at consume, a bearer token
    required. `approve()` stands in for the customer's device approval."""

    def __init__(self):
        self.lock = threading.Lock()
        self.challenges = {}  # id -> dict
        self.token = secrets.token_urlsafe(24)
        stub = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def reply(self, status, body):
                data = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_POST(self):
                raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                if self.path == "/token":
                    form = urllib.parse.parse_qs(raw.decode())
                    ok = form.get("grant_type") == ["client_credentials"] and form.get("client_id") == ["openbank-keycloak-mcp"]
                    return self.reply(200 if ok else 401, {"access_token": stub.token, "expires_in": 300} if ok else {})
                if self.headers.get("Authorization") != f"Bearer {stub.token}":
                    return self.reply(401, {})
                body = json.loads(raw or b"{}")
                if self.path == "/api/v1/sca/challenges":
                    return self.reply(*stub.initiate(body, self.headers.get("Idempotency-Key")))
                m = re.fullmatch(r"/api/v1/sca/challenges/([0-9a-f-]+)/consume", self.path)
                if m:
                    return self.reply(*stub.consume(m.group(1), body))
                return self.reply(404, {})

        self.server = http.server.ThreadingHTTPServer(("0.0.0.0", 0), Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def initiate(self, body, key):
        dl = body.get("dynamicLinkingData") or {}
        if body.get("purpose") != "MCP_CLIENT_CONNECT" or not dl.get("approvalRequestId", "").startswith("mcp-connect:") \
                or not re.fullmatch(r"[0-9a-f]{64}", dl.get("payloadSha256", "")):
            return 400, {}
        with self.lock:
            for c in self.challenges.values():
                if c["key"] == key and key:
                    return 201, {"id": c["id"]}
            cid = str(uuid.uuid4())
            self.challenges[cid] = {"id": cid, "key": key, "partyId": body["partyId"], "approved": False,
                                    "consumed": False, "consumes": 0, "spent": 0, "corrupt": False, **dl}
            return 201, {"id": cid}

    def consume(self, cid, body):
        with self.lock:
            c = self.challenges.get(cid)
            if c is None:
                return 404, {}
            c["consumes"] += 1
            if c.get("denied"):
                # sca-service: a final refusal is a 400 VALIDATION_ERROR, distinct from "pending".
                return 400, {"code": "VALIDATION_ERROR", "message": "SCA challenge is not approved"}
            if not c["approved"]:
                return 400, {"code": "SCA_CHALLENGE_PENDING", "message": "SCA challenge is not approved"}
            if c["consumed"]:
                return 409, {"message": "SCA challenge already consumed"}
            if (body.get("partyId"), body.get("approvalRequestId"), body.get("payloadSha256")) != \
                    (c["partyId"], c["approvalRequestId"], c["payloadSha256"]):
                return 409, {"message": "dynamic linking mismatch"}
            c["consumed"] = True
            c["spent"] += 1
            done = {"id": cid, "partyId": c["partyId"], "purpose": "MCP_CLIENT_CONNECT", "status": "COMPLETED",
                    "consumedAt": "2026-11-17T10:00:00Z"}
            if c["corrupt"]:
                # The consume DID happen; the response then fails the authenticator's post-consume check.
                done["consumedAt"] = None
            return 200, done

    def for_request(self, request_uri):
        want = "mcp-connect:" + request_uri.split("urn:ietf:params:oauth:request_uri:", 1)[1]
        with self.lock:
            found = [c for c in self.challenges.values() if c["approvalRequestId"] == want]
        return found[0] if found else None

    def deny(self, request_uri):
        c = self.for_request(request_uri)
        assert c is not None, "no challenge was initiated for this request"
        with self.lock:
            c["denied"] = True
        return c

    def approve(self, request_uri, corrupt=False):
        c = self.for_request(request_uri)
        assert c is not None, "no challenge was initiated for this request"
        with self.lock:
            c["approved"], c["corrupt"] = True, corrupt
        return c


class McpClientAdmission(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="ob-mcp-admission-test-")
        cls.addClassCleanup(cls.tmp.cleanup)
        work = pathlib.Path(cls.tmp.name)
        cls.hkey, cls.passkey, cls.dpop = EcKey(work / "h.pem"), EcKey(work / "p.pem"), EcKey(work / "d.pem")
        cls.cred_id = secrets.token_bytes(32)
        cls.young_passkey, cls.young_cred_id = EcKey(work / "y.pem"), secrets.token_bytes(32)
        cls.sca = ScaStub()
        cls.addClassCleanup(cls.sca.server.shutdown)

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
                                 # test-only: a token the account API accepts (aud + account roles)
                                 "protocolMappers": [
                                     {"name": "aud-account", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
                                      "config": {"included.client.audience": "account", "access.token.claim": "true"}},
                                     {"name": "account-roles", "protocol": "openid-connect",
                                      "protocolMapper": "oidc-usermodel-client-role-mapper",
                                      "config": {"usermodel.clientRoleMapping.clientId": "account", "multivalued": "true",
                                                 "claim.name": "resource_access.${client_id}.roles", "access.token.claim": "true"}}],
                                 "authenticationFlowBindingOverrides": {"browser": "it-password-flow"}})
        cls.exchanger_secret = secrets.token_urlsafe(24)
        realm["clients"].append({"clientId": "it-exchanger", "publicClient": False, "enabled": True,
                                 "clientAuthenticatorType": "client-secret", "secret": cls.exchanger_secret,
                                 "serviceAccountsEnabled": True, "standardFlowEnabled": False,
                                 "directAccessGrantsEnabled": False, "protocol": "openid-connect"})
        realm["authenticationFlows"].append({
            "id": "it-password-flow", "alias": "it-password", "providerId": "basic-flow", "topLevel": True, "builtIn": False,
            "authenticationExecutions": [
                {"authenticator": "auth-cookie", "requirement": "ALTERNATIVE", "priority": 5, "authenticatorFlow": False},
                {"authenticator": "auth-username-password-form", "requirement": "ALTERNATIVE", "priority": 10, "authenticatorFlow": False}]})
        def passkey_credential(key, cred_id, age_hours):
            cose = cbor({1: 2, 3: -7, -1: 1, -2: key.x, -3: key.y})
            data = {"aaguid": "00000000-0000-0000-0000-000000000000", "credentialId": base64.b64encode(cred_id).decode(),
                    "counter": 0, "attestationStatement": None, "credentialPublicKey": base64.b64encode(cose).decode(),
                    "attestationStatementFormat": "none", "transports": ["internal", "hybrid"]}
            # ADR-0385 D2.3: the extension reads the credential's creation time.
            return {"type": "webauthn-passwordless", "userLabel": "it", "credentialData": json.dumps(data), "secretData": "{}",
                    "createdDate": int((time.time() - age_hours * 3600) * 1000)}
        cls.password = secrets.token_urlsafe(18)
        realm.setdefault("users", []).append({
            "id": USER_ID, "username": USER, "email": USER, "firstName": "Mcp", "lastName": "It",
            "enabled": True, "emailVerified": True, "attributes": {"party_id": [PARTY]},
            "credentials": [{"type": "password", "value": cls.password, "temporary": False},
                            passkey_credential(cls.passkey, cls.cred_id, 100)],
            "clientRoles": {"account": ["manage-account", "view-profile"]},
            "realmRoles": ["ROLE_CUSTOMER", "offline_access", "default-roles-openbank-customers"]})
        realm["users"].append({
            "id": YOUNG_USER_ID, "username": YOUNG_USER, "email": YOUNG_USER, "firstName": "Young", "lastName": "It",
            "enabled": True, "emailVerified": True, "attributes": {"party_id": [YOUNG_PARTY]},
            "credentials": [passkey_credential(cls.young_passkey, cls.young_cred_id, 71)],
            "realmRoles": ["ROLE_CUSTOMER", "offline_access", "default-roles-openbank-customers"]})
        realm_file = work / "realm.json"
        realm_file.write_text(json.dumps(realm))
        realm_file.chmod(0o644)

        dockerfile = (ROOT / "openbank-infra/docker/keycloak/Dockerfile").read_text()
        image = re.search(r"FROM (quay\.io/keycloak/keycloak:\$\{KEYCLOAK_VERSION\}@sha256:[a-f0-9]+)", dockerfile).group(1)
        version = re.search(r"ARG KEYCLOAK_VERSION=([0-9.]+)", dockerfile).group(1)
        features = re.search(r"ENV KC_FEATURES=(\S+)", dockerfile).group(1)
        image = image.replace("${KEYCLOAK_VERSION}", version)
        # The extension, from the committed Dockerfile (compiled and unit-tested in that stage).
        providers = work / "providers"
        run("docker", "buildx", "build", "--target", "extension-jar", "--output", f"type=local,dest={providers}",
            str(ROOT / "openbank-infra/docker/keycloak"))
        providers.chmod(0o755)
        cls.admin_password = secrets.token_urlsafe(18)
        cls.container = "ob-mcp-admission-test-" + secrets.token_hex(6)
        cls.addClassCleanup(lambda: run("docker", "rm", "-f", cls.container))
        sca = f"http://host.docker.internal:{cls.sca.port}"
        spi = "--spi-authenticator--openbank-mcp-withheld-code--"
        run("docker", "run", "--detach", "--name", cls.container, "--memory=1g",
            "--add-host=host.docker.internal:host-gateway",
            "-e", "JAVA_OPTS_APPEND=-XX:ActiveProcessorCount=2", "-p", "127.0.0.1::8080",
            "-e", "KC_BOOTSTRAP_ADMIN_USERNAME=it-admin", "-e", f"KC_BOOTSTRAP_ADMIN_PASSWORD={cls.admin_password}",
            "-v", f"{realm_file}:/opt/keycloak/data/import/realm.json:ro",
            "-v", f"{providers}:/opt/keycloak/providers:ro", image,
            "start-dev", "--import-realm", f"--features={features}",
            f"{spi}sca-url={sca}", f"{spi}token-url={sca}/token", f"{spi}client-id=openbank-keycloak-mcp",
            f"{spi}client-secret={secrets.token_urlsafe(16)}", f"{spi}session-lifespan-seconds={SESSION_LIFESPAN_S}")
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

    def assert_passkey(self, browser, page, key=None, cred_id=None, user_id=USER_ID):
        """The software authenticator answers Keycloak's real challenge."""
        key, cred_id = key or self.passkey, cred_id or self.cred_id
        challenge = re.search(r'challenge\s*:\s*"([^"]+)"', page.text).group(1)
        rp_id = re.search(r'rpId\s*:\s*"([^"]*)"', page.text).group(1)
        action = html.unescape(re.search(r'id="webauth" action="([^"]+)"', page.text).group(1))
        client_data = json.dumps({"type": "webauthn.get", "challenge": challenge, "origin": self.origin,
                                  "crossOrigin": False}).encode()
        auth_data = hashlib.sha256(rp_id.encode()).digest() + bytes([0x05]) + next(COUNTER).to_bytes(4, "big")
        signature = key.sign_der(auth_data + hashlib.sha256(client_data).digest())
        return browser.follow(browser.request("POST", action, {
            "clientDataJSON": b64(client_data), "authenticatorData": b64(auth_data), "signature": b64(signature),
            "credentialId": b64(cred_id), "userHandle": b64(user_id.encode()), "error": ""}))

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

    @staticmethod
    def is_waiting_page(resp):
        return resp.status in (200, 429) and 'id="mcp-connect-poll"' in resp.text

    def poll(self, browser, page, **form):
        action = html.unescape(re.search(r'id="mcp-connect-poll" method="post" action="([^"]+)"', page.text).group(1))
        return browser.follow(browser.request("POST", action, {"check": "1", **form}))

    def connect(self, client, redirect, **extra):
        """Passkey asserted; returns (browser, waiting page, request_uri, verifier) — no code yet."""
        browser = self.sso_browser()
        uri, verifier = self.request_uri(client, redirect, **extra)
        page = self.assert_passkey(browser, self.authorize(browser, client, uri))
        self.assertTrue(self.is_waiting_page(page), f"passkey must lead to the SCA wait: {page.status} {page.location[:120]}")
        self.assertNotIn("code", page.query())
        return browser, page, uri, verifier

    def code_for(self, client, redirect, **extra):
        browser, page, uri, verifier = self.connect(client, redirect, **extra)
        self.sca.approve(uri)
        resp = self.poll(browser, page)
        query = resp.query()
        self.assertIn("code", query, f"fresh passkey + consumed SCA must yield a code: {resp.status} {resp.location[:120]}")
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

    def test_foreign_resource_is_invalid_target(self):
        # ADR-0385 D1 / D7.4. Keycloak 26.8.1 does NOT implement RFC 8707 itself; the bank's
        # openbank-mcp-client-guard executor refuses a resource other than the client's bound one
        # at PAR. The audience half of D1: see test_tier_l_dpop_bound_tokens (aud == mcp-service only).
        for client, redirect in ((L, L_CB), (H, H_CB)):
            with self.subTest(client=client):
                resp, _ = self.par(client, redirect, resource="https://evil.example/mcp")
                self.assertEqual(resp.status, 400, resp.text[:200])
                self.assertIn("invalid_target", resp.text)
        self.assertEqual(self.par(L, L_CB, resource=RESOURCE)[0].status, 201, "the bound resource is accepted")

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


    # ---- D1: no token exchange for an MCP client ----------------------------------------------
    def test_token_exchange_refused_for_mcp_client(self):
        # #16667: the image enables the legacy `token-exchange` (v1) feature for the bank's own app,
        # and v1 lets a confidential client exchange ITS OWN access token for a fresh access +
        # refresh token whatever `standard.token.exchange.enabled` says. The bank's
        # openbank-mcp-client-guard executor (any-client policy) refuses it for MCP clients.
        query, verifier = self.code_for(H, H_CB)
        resp = self.post(self.token_url, {"grant_type": "authorization_code", "client_id": H, "code": query["code"][0],
                                          "redirect_uri": H_CB, "code_verifier": verifier, **self.client_auth()})
        self.assertEqual(resp.status, 200, resp.text[:200])
        exchange = self.post(self.token_url, {
            "grant_type": "urn:ietf:params:oauth:grant-type:token-exchange", "client_id": H,
            "subject_token": resp.json()["access_token"],
            "subject_token_type": "urn:ietf:params:oauth:token-type:access_token",
            "requested_token_type": "urn:ietf:params:oauth:token-type:access_token", **self.client_auth()})
        self.assertIn(exchange.status, (400, 401, 403), exchange.text[:200])
        self.assertNotIn("access_token", exchange.text)
        self.assertNotIn("refresh_token", exchange.text)

    # ---- ADR-0385 D3: the withheld code ---------------------------------------------------------
    def expected_context(self, client, redirect_host, uri, page):
        expires = re.search(r'id="mcp-expires">([^<]+)<', page.text).group(1)
        expires_at = int(time.mktime(time.strptime(expires, "%Y-%m-%dT%H:%M:%SZ")) - time.timezone)
        canon = "\n".join(["openbank-mcp-connect-context/v1", f"client_id={client}", f"redirect_host={redirect_host}",
                           f"resource={RESOURCE}", "scopes=offline_access openid",
                           f"request_uri_sha256={hashlib.sha256(uri.encode()).hexdigest()}", f"expires_at={expires_at}"])
        return hashlib.sha256(canon.encode()).hexdigest()

    def test_code_not_issued_before_consume(self):
        browser, page, uri, _ = self.connect(L, L_CB)
        challenge = self.sca.for_request(uri)
        self.assertIsNotNone(challenge, "the passkey step must raise the SCA challenge")
        self.assertEqual(challenge["partyId"], PARTY, "the challenge belongs to the passkey user's party")
        self.assertEqual(challenge["payloadSha256"], self.expected_context(L, "127.0.0.1", uri, page),
                         "payloadSha256 is the canonical context of THIS request")
        self.assertNotIn(challenge["id"], page.text, "the challenge id never reaches the browser (D3.1)")
        self.assertIn("127.0.0.1", page.text)
        self.assertIn(RESOURCE, page.text)
        time.sleep(2.1)
        pending = self.poll(browser, page)
        self.assertTrue(self.is_waiting_page(pending), f"unapproved: still waiting, got {pending.status}")
        self.assertNotIn("code", pending.query())
        self.assertFalse(challenge["consumed"])
        # a forged challenge id in the form is ignored: the note is the only source
        time.sleep(2.1)
        forged = self.poll(browser, pending, challengeId=str(uuid.uuid4()))
        self.assertNotIn("code", forged.query())

    def test_poll_is_rate_limited(self):
        browser, page, _uri, _ = self.connect(L, L_CB)
        first = self.poll(browser, page)
        second = self.poll(browser, first)
        self.assertEqual(second.status, 429, "a poll within 2 s of the last is refused")
        self.assertNotIn("code", second.query())

    def test_two_concurrent_polls_issue_at_most_one_code(self):
        # ADR-0385 D3.5: a forked session issues at most one code. Measured on 26.8.1: Keycloak's own
        # session-code check already rejects the second POST of one page (400) or restarts the flow,
        # and in a minority of runs the flow restart lands on the winner too, so NO code is issued
        # although the challenge was spent — fail-closed, and a fresh connect is required. Both
        # outcomes satisfy "at most one"; "two codes" never may. The extension's cluster-wide
        # single-use claim is the line that holds across Keycloak nodes, which one container cannot
        # show (see the PR's known-positive notes).
        browser, page, uri, _ = self.connect(L, L_CB)
        challenge = self.sca.approve(uri)
        results, barrier = [], threading.Barrier(2)

        def racer():
            twin = Browser(self.origin)
            twin.cookies = dict(browser.cookies)
            barrier.wait()
            results.append(self.poll(twin, page))
        threads = [threading.Thread(target=racer) for _ in range(2)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(60)
        codes = [r for r in results if "code" in r.query()]
        self.assertEqual(len(results), 2)
        self.assertLessEqual(len(codes), 1, f"at most one code, got {[(r.status, r.location[:60]) for r in results]}")
        self.assertLessEqual(challenge["spent"], 1, "the challenge is spent at most once")
        if codes:
            self.assertEqual(challenge["spent"], 1, "a code only after a consume")
        time.sleep(2.1)
        for r in results:
            if self.is_waiting_page(r):
                self.assertNotIn("code", self.poll(browser, r).query(), "no second code after the race")

    def test_consume_then_issue_failure_yields_no_code_and_no_retry(self):
        browser, page, uri, _ = self.connect(L, L_CB)
        challenge = self.sca.approve(uri, corrupt=True)
        resp = self.poll(browser, page)
        self.assertNotIn("code", resp.query())
        self.assertTrue(challenge["consumed"], "the consume happened")
        consumes = challenge["consumes"]
        time.sleep(2.1)
        retry = self.poll(browser, page)
        self.assertNotIn("code", retry.query(), "a burned session never issues a code")
        self.assertEqual(challenge["consumes"], consumes, "and never consumes again")

    def test_session_past_its_lifespan_yields_no_code(self):
        browser, page, uri, _ = self.connect(L, L_CB)
        challenge = self.sca.approve(uri)
        time.sleep(SESSION_LIFESPAN_S + 1)
        resp = self.poll(browser, page)
        self.assertNotIn("code", resp.query())
        self.assertEqual(challenge["consumes"], 0, "an expired session never reaches the consume")
        self.assertFalse(challenge["consumed"])

    def test_app_denial_ends_the_session(self):
        # Review of #16672: only sca-service's specific "pending" code keeps the session waiting; a
        # final refusal (here: the customer denied in the app) ends it at once, fail-closed.
        browser, page, uri, _ = self.connect(L, L_CB)
        challenge = self.sca.deny(uri)
        resp = self.poll(browser, page)
        self.assertNotIn("code", resp.query())
        self.assertFalse(self.is_waiting_page(resp), "a denial must not keep the session waiting")
        consumes = challenge["consumes"]
        time.sleep(2.1)
        self.assertNotIn("code", self.poll(browser, page).query())
        self.assertEqual(challenge["consumes"], consumes, "a burned session never consumes again")

    def test_no_exchange_may_target_the_mcp_resource(self):
        # Review of #16672: whoever asks, a token for the MCP resource / audience is never minted
        # by exchange. Control first: the same non-MCP client's self-exchange succeeds (v1), so the
        # refusal below is the guard and not Keycloak's general refusal.
        def exchange(**extra):
            tok = self.post(self.token_url, {"grant_type": "client_credentials", "client_id": "it-exchanger",
                                              "client_secret": self.exchanger_secret}).json()["access_token"]
            return self.post(self.token_url, {
                "grant_type": "urn:ietf:params:oauth:grant-type:token-exchange", "client_id": "it-exchanger",
                "client_secret": self.exchanger_secret, "subject_token": tok,
                "subject_token_type": "urn:ietf:params:oauth:token-type:access_token",
                "requested_token_type": "urn:ietf:params:oauth:token-type:access_token", **extra})
        control = exchange()
        self.assertEqual(control.status, 200, f"control: a non-MCP self-exchange must succeed: {control.text[:200]}")
        for extra in ({"resource": RESOURCE}, {"audience": "openbank-mcp-service"}):
            with self.subTest(**extra):
                resp = exchange(**extra)
                self.assertIn(resp.status, (400, 401, 403), resp.text[:200])
                self.assertNotIn("access_token", resp.text)

    def admin_token(self):
        return self.post(f"{self.origin}/realms/master/protocol/openid-connect/token", {
            "grant_type": "password", "client_id": "admin-cli", "username": "it-admin",
            "password": self.admin_password}).json()["access_token"]

    def test_customer_cannot_set_party_id(self):
        # Review of #16672: party_id binds the MCP token and the SCA consume; the user profile
        # declares it admin-only, so the account API (what the account console and the
        # update-profile forms use) refuses or ignores a customer's attempt to set it.
        browser = Browser(self.origin)
        verifier = b64(secrets.token_bytes(32))
        page = browser.request("GET", self.auth_url + "?" + urllib.parse.urlencode({
            "client_id": "local-cookie-seeder", "response_type": "code", "redirect_uri": "https://client.test/x",
            "scope": "openid", "code_challenge": b64(hashlib.sha256(verifier.encode()).digest()),
            "code_challenge_method": "S256"}))
        action = html.unescape(re.search(r'id="kc-form-login"[^>]*action="([^"]+)"', page.text).group(1))
        code = browser.follow(browser.request("POST", action, {"username": USER, "password": self.password})).query()["code"][0]
        user_token = self.post(self.token_url, {"grant_type": "authorization_code", "client_id": "local-cookie-seeder",
                                                "code": code, "redirect_uri": "https://client.test/x",
                                                "code_verifier": verifier}).json()["access_token"]
        account = f"{self.iss}/account"
        auth = {"Authorization": f"Bearer {user_token}", "Accept": "application/json"}
        me = json.loads(urllib.request.urlopen(urllib.request.Request(account, headers=auth), timeout=30).read())
        self.assertNotIn("party_id", me.get("attributes") or {}, "party_id must not even be visible to the user")
        attempt = {**me, "attributes": {**(me.get("attributes") or {}), "party_id": [YOUNG_PARTY]}}
        req = urllib.request.Request(account, data=json.dumps(attempt).encode(), method="POST",
                                     headers={**auth, "Content-Type": "application/json"})
        try:
            status = urllib.request.urlopen(req, timeout=30).status
        except urllib.error.HTTPError as error:
            status = error.code
        users = json.loads(urllib.request.urlopen(urllib.request.Request(
            f"{self.origin}/admin/realms/openbank-customers/users?username={urllib.parse.quote(USER)}&exact=true",
            headers={"Authorization": f"Bearer {self.admin_token()}"}), timeout=30).read())
        self.assertEqual(users[0]["attributes"]["party_id"], [PARTY],
                         f"party_id must be unchanged after a user attempt (account API answered {status})")

    def test_young_passkey_refused(self):
        browser = Browser(self.origin)
        uri, _ = self.request_uri(L, L_CB)
        resp = self.assert_passkey(browser, self.authorize(browser, L, uri), key=self.young_passkey,
                                   cred_id=self.young_cred_id, user_id=YOUNG_USER_ID)
        self.assertNotIn("code", resp.query())
        self.assertFalse(self.is_waiting_page(resp), "a passkey younger than 72 h must not reach the SCA step")
        self.assertIsNone(self.sca.for_request(uri), "no challenge is raised for a young passkey")

if __name__ == "__main__":
    unittest.main(verbosity=2)
