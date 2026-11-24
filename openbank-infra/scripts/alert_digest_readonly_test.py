# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""The in-cluster standing-critical digest can READ Alertmanager and cannot WRITE it (ADR-0382).

Two independent layers keep POST /api/v2/silences out of reach of the digest pod, and each is
tested here by the case it must REJECT, not by the case it must allow:

  * L7 — the GET-only relay. The code under test is extracted from the deployed ConfigMap
    (`relay.py` in observability/alertmanager-read-proxy.yaml), so this cannot pass against a
    copy that has drifted from what runs. A fake Alertmanager records every request it receives;
    every write verb must be refused by the relay AND leave the fake untouched.
  * L4 — the NetworkPolicies. The digest namespace must not be admitted to Alertmanager, the
    digest's egress must not reach :9093 or any private range, and the relay's egress must reach
    only DNS and Alertmanager.

Plus one end-to-end run of the real `.github/scripts/standing-critical-digest.py` through the
relay, so the envelope the weekly RCA consumes is produced by the path that will run in-cluster.
"""

from __future__ import annotations

import http.client
import http.server
import importlib.util
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
GITOPS = ROOT / "openbank-infra" / "gitops" / "components"
RELAY_YAML = GITOPS / "observability" / "alertmanager-read-proxy.yaml"
RELAY_EGRESS = GITOPS / "observability" / "networkpolicy-alertmanager-read-proxy.yaml"
AM_INGRESS = GITOPS / "observability" / "networkpolicy-tools-gate.yaml"
DIGEST_DIR = GITOPS / "alert-digest"
DIGEST_SCRIPT = ROOT / ".github" / "scripts" / "standing-critical-digest.py"
NOISE_REPORT = ROOT / ".github" / "scripts" / "alert-noise-report.py"
DIGEST_SCRIPT_CM = GITOPS / "alert-digest" / "configmap-digest-script.yaml"
DIGEST_NS = "alert-digest"

ALERTS = [{"labels": {"severity": "critical", "alertname": "LedgerDown", "service": "ledger"},
           "startsAt": "2026-11-16T06:00:00Z"}]
NOISE_ALERTS = [
    {"labels": {"alertname": "Watchdog", "severity": "none"}},
    {"labels": {"alertname": "AlertRuleFlapping", "severity": "warning",
                "alert_name": "LedgerLatency", "alert_severity": "warning"},
     "annotations": {"summary": "LedgerLatency (warning) fired 22 separate times in 7 days"}},
]
UPSTREAM_PATH = "/tools/alertmanager/api/v2/alerts?active=true&silenced=true&inhibited=true"
UNRELATED_ALERT = {"labels": {"alertname": "Unrelated", "severity": "warning"}}



def docs(path: Path) -> list[dict]:
    return [d for d in yaml.safe_load_all(path.read_text(encoding="utf-8")) if isinstance(d, dict)]


def find(path_or_docs, kind: str, name: str) -> dict:
    items = docs(path_or_docs) if isinstance(path_or_docs, Path) else path_or_docs
    for d in items:
        if d.get("kind") == kind and d.get("metadata", {}).get("name") == name:
            return d
    raise AssertionError(f"{kind}/{name} not found")


def load_relay():
    source = find(RELAY_YAML, "ConfigMap", "alertmanager-read-proxy")["data"]["relay.py"]
    tmp = Path(tempfile.mkdtemp()) / "relay.py"
    tmp.write_text(source, encoding="utf-8")
    spec = importlib.util.spec_from_file_location("relay_under_test", tmp)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FakeAlertmanager(http.server.BaseHTTPRequestHandler):
    seen: list[tuple[str, str]] = []
    alerts: object = ALERTS + NOISE_ALERTS + [UNRELATED_ALERT]

    def _record(self):
        FakeAlertmanager.seen.append((self.command, self.path))
        body = json.dumps(FakeAlertmanager.alerts).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = _record

    def log_message(self, *args):
        pass


def start(server):
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


class RelayRefusesWrites(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.upstream = start(http.server.ThreadingHTTPServer(("127.0.0.1", 0), FakeAlertmanager))
        os.environ["UPSTREAM_BASE"] = f"http://127.0.0.1:{cls.upstream.server_port}/tools/alertmanager"
        cls.relay_module = load_relay()
        cls.relay = start(cls.relay_module.serve(0))
        cls.port = cls.relay.server_port

    @classmethod
    def tearDownClass(cls):
        cls.relay.shutdown()
        cls.upstream.shutdown()

    def setUp(self):
        FakeAlertmanager.seen.clear()
        FakeAlertmanager.alerts = ALERTS + NOISE_ALERTS + [UNRELATED_ALERT]

    def call(self, method, path, body=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        headers = {"Content-Type": "application/json"} if body is not None else {}
        conn.request(method, path, body=body, headers=headers)
        response = conn.getresponse()
        data = response.read()
        conn.close()
        return response.status, data

    def test_get_alerts_is_relayed_to_the_fixed_upstream(self):
        status, data = self.call("GET", "/api/v2/alerts?filter=severity%3D%22critical%22")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(data), ALERTS + NOISE_ALERTS)
        self.assertEqual(FakeAlertmanager.seen, [("GET", UPSTREAM_PATH)])

    def test_caller_query_cannot_steer_the_upstream(self):
        self.call("GET", "/api/v2/alerts?filter=x&silenced=true")
        self.assertEqual(FakeAlertmanager.seen, [("GET", UPSTREAM_PATH)])

    def test_every_write_verb_is_refused_and_never_reaches_alertmanager(self):
        silence = json.dumps({"matchers": [{"name": "alertname", "value": ".*", "isRegex": True}],
                              "startsAt": "2026-11-16T00:00:00Z", "endsAt": "2027-11-16T00:00:00Z",
                              "createdBy": "x", "comment": "mute everything"})
        cases = [("POST", "/api/v2/silences", silence), ("POST", "/api/v2/alerts", "[]"),
                 ("PUT", "/api/v2/silences", silence), ("DELETE", "/api/v2/silence/abc", None),
                 ("PATCH", "/api/v2/alerts", "{}"), ("HEAD", "/api/v2/alerts", None)]
        for method, path, body in cases:
            with self.subTest(method=method, path=path):
                status, _ = self.call(method, path, body)
                self.assertEqual(status, 405)
        self.assertEqual(FakeAlertmanager.seen, [], "a write verb reached Alertmanager")

    def test_other_read_paths_are_not_relayed(self):
        for path in ("/api/v2/silences", "/api/v2/status", "/api/v2/alerts/../silences",
                     "/tools/alertmanager/api/v2/silences"):
            with self.subTest(path=path):
                status, _ = self.call("GET", path)
                self.assertEqual(status, 404)
        self.assertEqual(FakeAlertmanager.seen, [])

    def test_pipelined_write_after_read_on_one_connection_never_reaches_alertmanager(self):
        # http.client cannot pipeline; a raw socket can. A relay that parsed only the FIRST
        # request line of a keep-alive connection would forward the trailing POST verbatim.
        silence = json.dumps({"matchers": [{"name": "alertname", "value": ".*", "isRegex": True}],
                              "createdBy": "x", "comment": "mute"}).encode()
        payload = (b"GET /api/v2/alerts HTTP/1.1\r\nHost: relay\r\n\r\n"
                   b"POST /api/v2/silences HTTP/1.1\r\nHost: relay\r\n"
                   b"Content-Type: application/json\r\nContent-Length: "
                   + str(len(silence)).encode() + b"\r\nConnection: close\r\n\r\n" + silence)
        with socket.create_connection(("127.0.0.1", self.port), timeout=10) as sock:
            sock.sendall(payload)
            received = b""
            while True:
                try:
                    chunk = sock.recv(65536)
                except (ConnectionResetError, socket.timeout):
                    break
                if not chunk:
                    break
                received += chunk
        self.assertTrue(received.startswith(b"HTTP/1."), received[:80])
        methods = [m for m, _ in FakeAlertmanager.seen]
        self.assertNotIn("POST", methods, f"pipelined POST reached Alertmanager: {FakeAlertmanager.seen}")
        self.assertTrue(all(path.startswith("/tools/alertmanager/api/v2/alerts") for _, path in FakeAlertmanager.seen))

    def test_real_digest_script_produces_the_envelope_through_the_relay(self):
        with tempfile.TemporaryDirectory() as tmp:
            env = {k: v for k, v in os.environ.items() if not k.startswith("ALERTMANAGER")}
            env.update(ALERTMANAGER_URL=f"http://127.0.0.1:{self.port}", ALERTMANAGER_AUTH="none",
                       DIGEST_OUTPUT=f"{tmp}/digest.md", DIGEST_ALERTS_OUTPUT=f"{tmp}/obs.json")
            run = subprocess.run([sys.executable, "-I", str(DIGEST_SCRIPT)], env=env,
                                 capture_output=True, text=True, timeout=60)
            self.assertEqual(run.returncode, 0, run.stderr)
            envelope = json.loads(Path(tmp, "obs.json").read_text())
            self.assertEqual(envelope["alerts"], ALERTS)
            self.assertEqual(envelope["alertNoise"], NOISE_ALERTS)
            self.assertEqual(FakeAlertmanager.seen, [("GET", UPSTREAM_PATH)])
            noise_report = Path(tmp, "noise.md")
            classified = subprocess.run([sys.executable, str(NOISE_REPORT), f"{tmp}/obs.json", str(noise_report)],
                                        env=env, capture_output=True, text=True, timeout=10)
            self.assertEqual(classified.returncode, 0, classified.stdout + classified.stderr)
            self.assertIn("| 22 | `LedgerLatency` | warning |", noise_report.read_text())
            envelope["alertNoise"] = []
            Path(tmp, "obs.json").write_text(json.dumps(envelope))
            classified = subprocess.run([sys.executable, str(NOISE_REPORT), f"{tmp}/obs.json", str(noise_report)],
                                        env=env, capture_output=True, text=True, timeout=10)
            self.assertNotEqual(classified.returncode, 0)
            self.assertIn("UNREADABLE", classified.stdout)
            self.assertIn("observedAt", envelope)
            self.assertIn("Open criticals: **1**", Path(tmp, "digest.md").read_text())

    def test_malformed_upstream_does_not_write_an_envelope(self):
        FakeAlertmanager.alerts = {"error": "bad upstream payload"}
        with tempfile.TemporaryDirectory() as tmp:
            env = {k: v for k, v in os.environ.items() if not k.startswith("ALERTMANAGER")}
            envelope = Path(tmp, "obs.json")
            env.update(ALERTMANAGER_URL=f"http://127.0.0.1:{self.port}", ALERTMANAGER_AUTH="none",
                       DIGEST_OUTPUT=f"{tmp}/digest.md", DIGEST_ALERTS_OUTPUT=str(envelope))
            result = subprocess.run([sys.executable, "-I", str(DIGEST_SCRIPT)], env=env,
                                    capture_output=True, text=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse(envelope.exists())

    def test_missing_token_without_explicit_opt_out_still_fails_closed(self):
        env = {k: v for k, v in os.environ.items() if not k.startswith("ALERTMANAGER")}
        env["ALERTMANAGER_URL"] = f"http://127.0.0.1:{self.port}"
        run = subprocess.run([sys.executable, "-I", str(DIGEST_SCRIPT)], env=env,
                             capture_output=True, text=True, timeout=60)
        self.assertEqual(run.returncode, 2)
        self.assertEqual(FakeAlertmanager.seen, [])


def namespaces_admitted(policy: dict) -> set[str]:
    out = set()
    for rule in policy["spec"].get("ingress", []) or []:
        for peer in rule.get("from", []) or []:
            ns = (peer.get("namespaceSelector") or {}).get("matchLabels", {})
            if "kubernetes.io/metadata.name" in ns:
                out.add(ns["kubernetes.io/metadata.name"])
            elif "namespaceSelector" in peer:
                out.add("*")  # an unlabelled namespaceSelector admits every namespace
    return out


class NetworkLayerBlocksTheDirectPath(unittest.TestCase):
    def test_alertmanager_does_not_admit_the_digest_namespace(self):
        policy = find(AM_INGRESS, "NetworkPolicy", "alertmanager-ingress-allow-list")
        admitted = namespaces_admitted(policy)
        self.assertNotIn(DIGEST_NS, admitted)
        self.assertNotIn("*", admitted)

    def test_digest_egress_cannot_reach_alertmanager_or_private_ranges(self):
        policy = find(DIGEST_DIR / "networkpolicy-alert-digest-egress.yaml", "NetworkPolicy",
                      "standing-critical-digest-egress-allow-list")
        self.assertIn("Egress", policy["spec"]["policyTypes"])
        credential_rules = []
        for rule in policy["spec"]["egress"]:
            ports = {p["port"] for p in rule.get("ports", [])}
            self.assertNotIn(9093, ports)
            self.assertTrue(ports, "an egress rule without ports opens every port")
            for peer in rule["to"]:
                if "ipBlock" in peer and peer["ipBlock"]["cidr"] == "169.254.170.23/32":
                    credential_rules.append(rule)
                elif "ipBlock" in peer:
                    excepted = set(peer["ipBlock"].get("except", []))
                    self.assertTrue({"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"} <= excepted)
                if "podSelector" in peer:
                    self.assertEqual(peer["podSelector"]["matchLabels"]["app.kubernetes.io/name"],
                                     "alertmanager-read-proxy")
        self.assertEqual(credential_rules, [{
            "to": [{"ipBlock": {"cidr": "169.254.170.23/32"}}],
            "ports": [{"protocol": "TCP", "port": 80}],
        }])

    def test_relay_egress_is_dns_and_alertmanager_only(self):
        policy = find(RELAY_EGRESS, "NetworkPolicy", "alertmanager-read-proxy-egress-allow-list")
        ports = sorted(p["port"] for r in policy["spec"]["egress"] for p in r["ports"])
        self.assertEqual(ports, [53, 53, 9093])
        for rule in policy["spec"]["egress"]:
            for peer in rule["to"]:
                self.assertNotIn("ipBlock", peer)

    def test_digest_is_wired_to_the_relay_and_nothing_else(self):
        cron = find(DIGEST_DIR / "standing-critical-digest.yaml", "CronJob", "standing-critical-digest")
        env = {e["name"]: e.get("value") for c in
               cron["spec"]["jobTemplate"]["spec"]["template"]["spec"]["containers"] for e in c["env"]}
        self.assertEqual(env["ALERTMANAGER_URL"], "http://alertmanager-read-proxy.observability.svc:8080")
        self.assertNotIn("ALERTMANAGER_DIGEST_TOKEN", env)
        self.assertEqual(cron["metadata"]["namespace"], DIGEST_NS)

    def test_digest_configmap_is_byte_identical_to_the_canonical_script(self):
        shipped = find(DIGEST_SCRIPT_CM, "ConfigMap", "standing-critical-digest-script")["data"][
            "standing-critical-digest.py"]
        canonical = DIGEST_SCRIPT.read_text(encoding="utf-8")
        self.assertEqual(shipped, canonical,
                         "configmap-digest-script.yaml drifted from .github/scripts/standing-critical-digest.py"
                         " — copy the canonical script in, indented by four spaces")

    def test_digest_runs_the_mounted_configmap_and_fetches_no_code(self):
        cron = find(DIGEST_DIR / "standing-critical-digest.yaml", "CronJob", "standing-critical-digest")
        spec = cron["spec"]["jobTemplate"]["spec"]["template"]["spec"]
        container = spec["containers"][0]
        script = "\n".join(container["args"])
        self.assertIn("python3 -I /app/standing-critical-digest.py", script)
        for forbidden in ("git clone", "github.com", "curl ", "wget ", "pip install"):
            self.assertNotIn(forbidden, script)
        mount = next(m for m in container["volumeMounts"] if m["mountPath"] == "/app")
        self.assertTrue(mount.get("readOnly"))
        volume = next(v for v in spec["volumes"] if v["name"] == mount["name"])
        self.assertEqual(volume["configMap"]["name"], "standing-critical-digest-script")

    def test_relay_runs_the_tested_configmap(self):
        dep = find(RELAY_YAML, "Deployment", "alertmanager-read-proxy")
        spec = dep["spec"]["template"]["spec"]
        self.assertEqual(spec["containers"][0]["command"], ["python3", "-I", "/app/relay.py"])
        self.assertEqual(spec["volumes"][0]["configMap"]["name"], "alertmanager-read-proxy")


if __name__ == "__main__":
    unittest.main()
