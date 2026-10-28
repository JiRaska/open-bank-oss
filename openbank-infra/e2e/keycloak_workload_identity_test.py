#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""ADR-0177 native JWT grant -> standard exchange against real, disposable Keycloak.

Requires Docker, OpenSSL and Python's standard library. Uses the committed pilot
configuration, a local synthetic JWKS issuer and ephemeral test credentials only.
No cluster, Vault, production client secret or custom provider is used.
"""

import base64
import copy
import http.server
import http.client
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

ROOT = pathlib.Path(__file__).resolve().parents[2]
KC = ROOT / "openbank-infra/gitops/components/keycloak"


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


class WorkloadIdentity(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="ob-workload-test-")
        cls.addClassCleanup(cls.tmp.cleanup)
        cls.work = pathlib.Path(cls.tmp.name)
        cls.key = cls.work / "issuer.pem"
        run("openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(cls.key))
        modulus = run("openssl", "rsa", "-in", str(cls.key), "-noout", "-modulus").decode().strip().split("=", 1)[1]
        jwks = {"keys": [{"kty": "RSA", "kid": "synthetic-issuer", "alg": "RS256", "use": "sig", "n": b64(bytes.fromhex(modulus)), "e": "AQAB"}]}

        class Jwks(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                data = json.dumps(jwks).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *_args):
                pass

        cls.server = http.server.ThreadingHTTPServer(("0.0.0.0", 0), Jwks)
        cls.addClassCleanup(cls.server.server_close)
        cls.addClassCleanup(cls.server.shutdown)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.issuer = f"http://host.docker.internal:{cls.server.server_port}"
        template = json.loads((KC / "realm-template.json").read_text())
        provider = copy.deepcopy(next(p for p in template["identityProviders"] if p["alias"] == "kubernetes"))
        provider["config"].update(issuer=cls.issuer, jwksUrl=cls.issuer + "/keys", clientSecret=secrets.token_urlsafe(24))
        cls.secrets = {name: secrets.token_urlsafe(32) for name in ("openbank-agent", "openbank-copilot")}
        clients = []
        for name, secret in cls.secrets.items():
            client = copy.deepcopy(next(c for c in template["clients"] if c["clientId"] == name))
            client["secret"] = secret
            clients.append(client)
        clients.append({"clientId": "workload-browser", "enabled": True, "publicClient": True,
                        "standardFlowEnabled": True, "redirectUris": ["http://127.0.0.1/callback"]})
        workload = copy.deepcopy(next(u for u in template["users"] if u["username"] == "wl-openbank-agent"))
        cls.subject = workload["federatedIdentities"][0]["userId"]
        realm = {
            "realm": "workload-test", "enabled": True, "sslRequired": "none",
            "roles": {"realm": [{"name": "ROLE_API"}]},
            "clients": clients,
            "clientScopes": [copy.deepcopy(s) for s in template["clientScopes"] if s["name"] in ("openid", "profile", "roles")],
            "scopeMappings": [{"client": "openbank-agent", "roles": ["ROLE_API"]}],
            "identityProviders": [provider],
            "authenticationFlows": template["authenticationFlows"],
            "authenticatorConfig": template["authenticatorConfig"],
            "browserFlow": template["browserFlow"],
            "users": [workload, {"username": "service-account-openbank-agent", "enabled": True,
                       "serviceAccountClientId": "openbank-agent", "realmRoles": ["ROLE_API"]}],
        }
        realm_file = cls.work / "realm.json"
        realm_file.write_text(json.dumps(realm))
        # The disposable realm contains synthetic credentials only. Keycloak runs as UID 1000,
        # which differs from the Linux runner owner; the private temporary directory stays 0700.
        realm_file.chmod(0o644)
        dockerfile = (ROOT / "openbank-infra/docker/keycloak/Dockerfile").read_text()
        image = re.search(r"FROM (quay\.io/keycloak/keycloak:\$\{KEYCLOAK_VERSION\}@sha256:[a-f0-9]+)", dockerfile).group(1)
        version = re.search(r"ARG KEYCLOAK_VERSION=([0-9.]+)", dockerfile).group(1)
        image = image.replace("${KEYCLOAK_VERSION}", version)
        cls.container = "ob-workload-test-" + secrets.token_hex(6)
        cls.addClassCleanup(lambda: run("docker", "rm", "-f", cls.container))
        run("docker", "run", "--detach", "--name", cls.container,
            "--memory=1g", "-e", "JAVA_OPTS_APPEND=-XX:ActiveProcessorCount=2",
            "--add-host=host.docker.internal:host-gateway", "-p", "127.0.0.1::8080",
            "-v", f"{realm_file}:/opt/keycloak/data/import/realm.json:ro", image,
            "start-dev", "--import-realm", "--features=token-exchange,admin-fine-grained-authz:v1")
        port = run("docker", "port", cls.container, "8080/tcp").decode().strip().rsplit(":", 1)[1]
        cls.base = f"http://127.0.0.1:{port}/realms/workload-test"
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(cls.base, timeout=2) as response:
                    if response.status == 200:
                        return
            except (urllib.error.URLError, TimeoutError, http.client.RemoteDisconnected, ConnectionError):
                time.sleep(1)
        raise RuntimeError("Disposable Keycloak did not become ready; inspect its local logs")

    def assertion(self, **overrides):
        now = int(time.time())
        payload = {"iss": self.issuer, "sub": self.subject, "aud": ["keycloak"],
                   "iat": now, "nbf": now - 1, "exp": now + 600}
        payload.update(overrides)
        content = b64(json.dumps({"alg": "RS256", "typ": "JWT", "kid": "synthetic-issuer"}).encode()) + "." + b64(json.dumps(payload).encode())
        signature = run("openssl", "dgst", "-sha256", "-sign", str(self.key), data=content.encode())
        return content + "." + b64(signature)

    def token_request(self, client="openbank-agent", **params):
        params.update(client_id=client, client_secret=self.secrets[client])
        request = urllib.request.Request(self.base + "/protocol/openid-connect/token",
                                        data=urllib.parse.urlencode(params).encode())
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            with error:
                return error.code, json.load(error)

    def grant(self, token, client="openbank-agent"):
        return self.token_request(client, grant_type="urn:ietf:params:oauth:grant-type:jwt-bearer", assertion=token)

    def assert_workload(self, response):
        payload = claims(response["access_token"])
        self.assertTrue(payload.get("sub"), "workload token must carry a subject")
        self.assertEqual(payload["preferred_username"], "wl-openbank-agent")
        self.assertIn("ROLE_API", payload["realm_access"]["roles"])
        self.assertFalse(set(payload["realm_access"]["roles"]) & {"ROLE_ADMIN", "ROLE_OPERATOR"})
        self.assertNotIn("refresh_token", response)
        return payload

    def test_native_grant_then_standard_exchange(self):
        status, first = self.grant(self.assertion())
        self.assertEqual(status, 200, first.get("error_description"))
        first_claims = self.assert_workload(first)
        status, second = self.token_request(grant_type="urn:ietf:params:oauth:grant-type:token-exchange",
            subject_token=first["access_token"], subject_token_type="urn:ietf:params:oauth:token-type:access_token",
            requested_token_type="urn:ietf:params:oauth:token-type:access_token")
        self.assertEqual(status, 200, second.get("error_description"))
        self.assertEqual(self.assert_workload(second)["sub"], first_claims["sub"])

    def test_rejects_invalid_assertions(self):
        for name, changes, message in (
            ("unknown subject", {"sub": "system:serviceaccount:example:unprovisioned"}, "User not found"),
            ("wrong audience", {"aud": ["other-recipient"]}, "Invalid token audience"),
            ("multiple audiences", {"aud": ["keycloak", "other-recipient"]}, "Multiple audiences not allowed"),
            ("expired", {"exp": int(time.time()) - 600}, "Token is not active"),
            ("wrong issuer", {"iss": "https://untrusted.example.invalid"}, "No Identity Provider"),
        ):
            with self.subTest(name=name):
                status, error = self.grant(self.assertion(**changes))
                self.assertEqual(status, 400)
                self.assertIn(message, error["error_description"])

    def test_rejects_signature_tampering(self):
        parts = self.assertion().split(".")
        payload = claims(".".join(parts))
        payload["sub"] = "tampered"
        parts[1] = b64(json.dumps(payload).encode())
        status, error = self.grant(".".join(parts))
        self.assertEqual(status, 400)
        self.assertIn("Invalid signature", error["error_description"])

    def test_rejects_unauthorized_client(self):
        status, error = self.grant(self.assertion(), client="openbank-copilot")
        self.assertEqual(status, 400)
        self.assertIn("not supported for the requested client", error["error_description"])

    def test_assertion_reuse_and_lifetime_cap(self):
        expires = int(time.time()) + 60
        token = self.assertion(exp=expires)
        for _ in range(2):
            status, response = self.grant(token)
            self.assertEqual(status, 200, response.get("error_description"))
            self.assertLessEqual(self.assert_workload(response)["exp"], expires)

    def test_client_credentials_fallback(self):
        status, response = self.token_request(grant_type="client_credentials")
        self.assertEqual(status, 200, response.get("error_description"))
        self.assertIn("ROLE_API", claims(response["access_token"])["realm_access"]["roles"])

    def test_workload_provider_hidden_on_login(self):
        query = urllib.parse.urlencode({"client_id": "workload-browser", "response_type": "code",
                                      "redirect_uri": "http://127.0.0.1/callback", "scope": "openid",
                                      "code_challenge": b64(secrets.token_bytes(32)), "code_challenge_method": "S256"})
        with urllib.request.urlopen(self.base + "/protocol/openid-connect/auth?" + query) as response:
            html = response.read().decode()
        self.assertTrue('id="kc-form-login"' in html, "expected a real login form")
        self.assertFalse("social-kubernetes" in html, "workload IdP must be hidden")


if __name__ == "__main__":
    unittest.main(verbosity=2)
