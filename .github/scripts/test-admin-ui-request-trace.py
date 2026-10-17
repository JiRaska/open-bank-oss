#!/usr/bin/env python3
"""Offline real-client regressions for Admin UI request-send tracing."""

# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors.

from __future__ import annotations

import http.server
import importlib.util
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from collections import Counter
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).resolve().parents[2]
CURL_TRACE = ROOT / ".github/scripts/trace-admin-ui-curl.py"
ARTIFACT_SCRIPT = ROOT / ".github/scripts/find-latest-main-artifacts.py"
AGGREGATOR_SCRIPT = ROOT / ".github/scripts/aggregate-admin-ui-http-trace.py"
IPC_SCRIPT = ROOT / ".github/scripts/admin_ui_trace_ipc.py"
STAGE_WRAPPER = ROOT / ".github/scripts/trace-admin-ui-stage.py"
SENTINELS = (
    "TRACE_SENTINEL_AUTH_8f4e",
    "TRACE_SENTINEL_QUERY_71ad",
    "TRACE_SENTINEL_BODY_5c23",
    "TRACE_SENTINEL_HEADER_2b9f",
    "TRACE_SENTINEL_ERROR_0a61",
)


def cleanup_stage_process(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=2)


def start_stage(test_case: unittest.TestCase, trace_dir: Path, stage: str, env: dict[str, str]) -> subprocess.Popen:
    process = subprocess.Popen(
        [sys.executable, str(IPC_SCRIPT), "serve", "--stage", stage],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        env=env,
        start_new_session=True,
    )
    test_case.addCleanup(cleanup_stage_process, process)
    env["ADMIN_UI_TRACE_PID"] = str(process.pid)
    os.environ["ADMIN_UI_TRACE_PID"] = str(process.pid)
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if (trace_dir / f"{stage}.sock").exists() and (trace_dir / f"{stage}.pid").exists():
            return process
        if process.poll() is not None:
            raise AssertionError(f"trace supervisor exited with {process.returncode}")
        time.sleep(0.01)
    if process.poll() is None:
        process.terminate()
        process.wait(timeout=2)
    raise AssertionError(f"trace supervisor did not become ready (rc={process.returncode})")


def finish_stage(trace_dir: Path, stage: str, env: dict[str, str], process: subprocess.Popen | None = None) -> dict:
    result = subprocess.run(
        [sys.executable, str(IPC_SCRIPT), "finish", "--stage", stage],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env,
        check=False,
        timeout=6,
    )
    if process:
        process.wait(timeout=5)
    if result.returncode not in {0, 2}:
        raise AssertionError(f"trace finish failed: {result.stderr}")
    return json.loads((trace_dir / f"admin-ui-stage-{stage}.json").read_text())


class FixtureHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self) -> None:
        self.server.oracle.append((self.command, self.path, self.headers.get("Host", "")))
        path = urlsplit(self.path).path
        if path == "/retry":
            self.server.retry_count += 1
            status = 503 if self.server.retry_count < 3 else 200
            body = b"retry-ok" if status == 200 else SENTINELS[4].encode()
            self._respond(status, body, headers={"X-Sentinel": SENTINELS[3]})
            return
        if path == "/redirect-api":
            self._redirect(f"http://api.github.com:{self.server.server_port}/redirect-api-2")
            return
        if path == "/redirect-api-2":
            self._redirect(f"http://storage.test:{self.server.server_port}/redirect-final")
            return
        if path == "/redirect-final":
            self._respond(200, SENTINELS[2].encode())
            return
        if path == "/trace-failure":
            self._respond(503, b"failure-response", headers={"X-Sentinel": SENTINELS[3]})
            return
        if path == "/binary":
            self._respond(200, bytes(range(256)))
            return
        if path == "/unchanged":
            self._respond(200, b"same-response")
            return
        if path == "/redirect-python":
            self._redirect(f"http://localhost:{self.server.server_port}/python-final")
            return
        if path == "/python-final":
            self._respond(200, getattr(self.server, "python_redirect_body", json.dumps({"artifacts": []}).encode()))
            return
        if path.startswith("/repos/r/actions/runs/"):
            run_id = int(path.rsplit("/", 1)[-1])
            workflow = ".github/workflows/verify-provider.yml" if run_id == 3000 else ".github/workflows/services-ci.yml"
            self._respond(200, json.dumps({"path": workflow}).encode())
            return
        query = parse_qs(urlsplit(self.path).query)
        page = int((query.get("page") or ["1"])[0])
        if page < 3:
            artifacts = [
                {"id": index, "expired": False, "workflow_run": {"head_branch": "feature"}}
                for index in range(100)
            ]
        else:
            artifacts = [
                {"id": 300, "expired": False, "workflow_run": {"head_branch": "main", "id": 3000}},
                {"id": 301, "expired": False, "workflow_run": {"head_branch": "main", "id": 3010}},
            ]
        body = json.dumps({"artifacts": artifacts}).encode()
        if page == 3:
            # urllib follows this redirect; the trace must observe both sends.
            self.server.python_redirect_body = body
            self._redirect(f"http://localhost:{self.server.server_port}/python-final", body=body)
            return
        self._respond(200, body)

    def do_HEAD(self) -> None:
        self.server.oracle.append((self.command, self.path, self.headers.get("Host", "")))
        self._respond(304, b"")

    def _redirect(self, location: str, body: bytes = b"") -> None:
        self.send_response(302)
        self.send_header("Location", location)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def _respond(self, status: int, body: bytes, headers: dict[str, str] | None = None) -> None:
        self.send_response(status)
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def log_message(self, _format: str, *_args: object) -> None:
        return


class FixtureServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self) -> None:
        super().__init__(("127.0.0.1", 0), FixtureHandler)
        self.oracle: list[tuple[str, str, str]] = []
        self.retry_count = 0


class RequestTraceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.server = FixtureServer()
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.origin = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)

    def run_curl(self, *curl_args: str) -> tuple[subprocess.CompletedProcess[bytes], list[dict]]:
        with tempfile.TemporaryDirectory() as directory:
            env = os.environ.copy()
            trace_dir = Path(directory)
            env["ADMIN_UI_TRACE_DIR"] = str(trace_dir)
            env["NO_PROXY"] = "*"
            env["no_proxy"] = "*"
            for key in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"):
                env.pop(key, None)
            supervisor = start_stage(self, trace_dir, "sbom", env)
            result = subprocess.run(
                [sys.executable, str(CURL_TRACE), "--stage", "sbom", "--auth", "github", "--", "curl", *curl_args],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=env,
                check=False,
                timeout=15,
            )
            stage = finish_stage(trace_dir, "sbom", env, supervisor)
            records = [
                {
                    "destinationClass": row["destinationClass"],
                    "numericSendCount": row["numericSendCount"],
                    "complete": stage["complete"],
                    "reasonEnum": stage["reasonEnum"],
                }
                for row in stage["records"]
            ]
            if not records:
                records.append({
                    "destinationClass": "none",
                    "numericSendCount": 0,
                    "complete": stage["complete"],
                    "reasonEnum": stage["reasonEnum"],
                })
            return result, records

    def test_signal_exit_matches_direct_shell_status(self) -> None:
        code = "import os, signal; os.kill(os.getpid(), signal.SIGTERM)"
        with tempfile.TemporaryDirectory() as directory:
            trace_dir = Path(directory)
            env = os.environ.copy()
            env["ADMIN_UI_TRACE_DIR"] = str(trace_dir)
            supervisor = start_stage(self, trace_dir, "sbom", env)
            direct_env = {**env, "PYTHON": sys.executable, "KILL_SELF": code}
            direct = subprocess.run(
                ["bash", "-c", '"$PYTHON" -c "$KILL_SELF"; status=$?; exit "$status"'],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=direct_env, check=False, timeout=5,
            )
            wrapped = subprocess.run(
                [sys.executable, str(CURL_TRACE), "--stage", "sbom", "--auth", "github", "--",
                 sys.executable, "-c", code],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, check=False, timeout=5,
            )
            stage = finish_stage(trace_dir, "sbom", env, supervisor)
            self.assertEqual(direct.returncode, 143)
            self.assertEqual((wrapped.returncode, wrapped.stdout), (direct.returncode, direct.stdout))
            self.assertFalse(stage["complete"])
            self.assertEqual(stage["reasonEnum"], "pre_send_failure")
            self.assertEqual(stage["records"], [])

    def test_curl_retries_redirects_304_keepalive_binary_and_no_send(self) -> None:
        retry, records = self.run_curl(
            "--noproxy", "*", "-sf", "--retry", "2", "--retry-all-errors", "--retry-delay", "0",
            "--connect-timeout", "2", f"{self.origin}/retry", "-o", os.devnull,
        )
        self.assertEqual(retry.returncode, 0)
        self.assertEqual(sum(r["numericSendCount"] for r in records), 3)
        self.assertTrue(all(r["complete"] for r in records))

        port = str(self.server.server_port)
        redirected, records = self.run_curl(
            "--noproxy", "*", "-sf", "-L",
            "--connect-to", f"api.github.com:{port}:127.0.0.1:{port}",
            "--connect-to", f"storage.test:{port}:127.0.0.1:{port}",
            f"http://api.github.com:{port}/redirect-api", "-o", os.devnull,
        )
        self.assertEqual(redirected.returncode, 0)
        counts = {r["destinationClass"]: r["numericSendCount"] for r in records}
        self.assertEqual(counts.get("api"), 2)
        self.assertEqual(counts.get("non_api"), 1)
        self.assertEqual(sum(counts.values()), 3)

        not_modified, records = self.run_curl("--noproxy", "*", "-sfI", f"{self.origin}/not-modified")
        self.assertEqual(not_modified.returncode, 0)
        self.assertEqual(sum(r["numericSendCount"] for r in records), 1)

        repeated, records = self.run_curl(
            "--noproxy", "*", "-sf", f"{self.origin}/keep-a", f"{self.origin}/keep-b",
        )
        self.assertEqual(repeated.returncode, 0)
        self.assertEqual(sum(r["numericSendCount"] for r in records), 2)

        binary, records = self.run_curl("--noproxy", "*", "-s", f"{self.origin}/binary")
        self.assertEqual(binary.returncode, 0)
        self.assertEqual(binary.stdout, bytes(range(256)))
        self.assertEqual(sum(r["numericSendCount"] for r in records), 1)

        closed = socket.socket()
        closed.bind(("127.0.0.1", 0))
        closed_port = closed.getsockname()[1]
        closed.close()
        failed, records = self.run_curl(
            "--noproxy", "*", "-sf", "--connect-timeout", "1", f"http://127.0.0.1:{closed_port}/fail",
        )
        self.assertNotEqual(failed.returncode, 0)
        self.assertEqual(sum(r["numericSendCount"] for r in records), 0)
        self.assertTrue(all(not r["complete"] for r in records))
        self.assertEqual({r["reasonEnum"] for r in records}, {"pre_send_failure"})

    def test_curl_stdout_file_exit_and_secret_redaction(self) -> None:
        direct = subprocess.run(
            ["curl", "--noproxy", "*", "-s", f"{self.origin}/unchanged"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False, timeout=10,
        )
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "body.bin"
            env = os.environ.copy()
            trace_dir = Path(directory) / "trace"
            trace_dir.mkdir()
            env["ADMIN_UI_TRACE_DIR"] = str(trace_dir)
            env["NO_PROXY"] = env["no_proxy"] = "*"
            supervisor = start_stage(self, trace_dir, "sbom", env)
            wrapped = subprocess.run(
                [sys.executable, str(CURL_TRACE), "--stage", "sbom", "--auth", "github", "--", "curl",
                 "--noproxy", "*", "-s", "-H", f"Authorization: Bearer {SENTINELS[0]}",
                 f"{self.origin}/unchanged?marker={SENTINELS[1]}", "-o", str(output)],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, check=False, timeout=10,
            )
            stage = finish_stage(trace_dir, "sbom", env, supervisor)
            self.assertEqual(wrapped.returncode, direct.returncode)
            self.assertEqual(wrapped.stdout, b"")
            self.assertEqual(wrapped.stderr, b"")
            self.assertEqual(output.read_bytes(), direct.stdout)
            receipt_text = json.dumps(stage)
            captured = wrapped.stdout + wrapped.stderr + receipt_text.encode()
            for sentinel in SENTINELS:
                self.assertNotIn(sentinel.encode(), captured)

    def test_curl_failure_emits_only_safe_notice_and_preserves_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "body.bin"
            arguments = [
                "curl", "--noproxy", "*", "-sS", "--fail-with-body",
                "-H", f"Authorization: Bearer {SENTINELS[0]}",
                f"{self.origin}/trace-failure?marker={SENTINELS[1]}", "-o", str(output),
            ]
            direct = subprocess.run(arguments, capture_output=True, check=False, timeout=10)
            direct_body = output.read_bytes()
            output.unlink()
            trace_dir = Path(directory) / "trace"
            trace_dir.mkdir()
            env = {**os.environ, "ADMIN_UI_TRACE_DIR": str(trace_dir)}
            supervisor = start_stage(self, trace_dir, "sbom", env)
            wrapped = subprocess.run(
                [sys.executable, str(CURL_TRACE), "--stage", "sbom", "--auth", "github", "--", *arguments],
                capture_output=True, env=env, check=False, timeout=10,
            )
            stage = finish_stage(trace_dir, "sbom", env, supervisor)
            self.assertEqual(direct.returncode, 22)
            self.assertEqual((wrapped.returncode, wrapped.stdout), (direct.returncode, direct.stdout))
            self.assertEqual(output.read_bytes(), direct_body)
            self.assertEqual(wrapped.stderr, b"WARN: traced curl command failed; request details withheld.\n")
            captured = wrapped.stdout + wrapped.stderr + json.dumps(stage).encode()
            for sentinel in (*SENTINELS, self.origin, "failure-response"):
                self.assertNotIn(sentinel.encode(), captured)

    def test_curl_parser_rejects_unsupported_or_truncated_verbose_stream(self) -> None:
        spec = importlib.util.spec_from_file_location("trace_admin_ui_curl", CURL_TRACE)
        assert spec and spec.loader
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        unsupported = module.VerboseParser()
        unsupported.feed(b"=> Send header, 48 bytes\n")
        self.assertTrue(unsupported.incomplete)
        truncated = module.VerboseParser()
        truncated.feed(b"> GET / HTTP/1.1\n")
        truncated.feed(b"> Host: api.github.com\n")
        truncated.finish()
        self.assertTrue(truncated.incomplete)
        self.assertEqual(truncated.counts["api"], 1)

    def test_urllib_proxy_target_classification_does_not_count_proxy_as_api(self) -> None:
        spec = importlib.util.spec_from_file_location("artifact_collector", ARTIFACT_SCRIPT)
        assert spec and spec.loader
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

        class ProxyConnection:
            host = "proxy.internal"
            _tunnel_host = None

        trace = module.SendTrace("service_ti", "github")
        trace.audit(
            "http.client.send",
            (ProxyConnection(), b"GET http://api.github.com/repos/a/b?token=secret HTTP/1.1\r\n"),
        )
        self.assertEqual(trace.counts, Counter(api=1))
        self.assertFalse(trace.incomplete)

        class TunnelConnection:
            host = "proxy.internal"
            _tunnel_host = "api.github.com"

        tunnel = module.SendTrace("service_ti", "github")
        tunnel.audit("http.client.send", (TunnelConnection(), b"GET /repos/a/b HTTP/1.1\r\n"))
        self.assertEqual(tunnel.counts, Counter(api=1))
        self.assertFalse(tunnel.incomplete)

    def test_real_urllib_pagination_redirect_and_no_send_failure(self) -> None:
        code = r'''
import importlib.util, json, os, sys
path, origin, mode, trace_dir = sys.argv[1:]
spec = importlib.util.spec_from_file_location("artifact_collector", path)
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
trace = module.SendTrace("service_ti", "github"); trace.install()
real_urlopen = module.urlopen
# Keep the real provenance helper and urllib transport; route its fixed API origin to the oracle.
def loopback_urlopen(request, **kwargs):
    prefix = "https://api.github.com"
    if request.full_url.startswith(prefix + "/"):
        request = module.Request(
            origin + request.full_url[len(prefix):],
            headers=dict(request.header_items()), method=request.get_method(),
        )
    return real_urlopen(request, **kwargs)
module.urlopen = loopback_urlopen
seen_run_ids = []
def run_path(run_id):
    seen_run_ids.append(run_id)
    return module.github_run_path("r", "TRACE_SENTINEL_AUTH_8f4e", run_id)
try:
    if mode == "pages":
        result = module.latest_main_artifact(
            "TRACE_SENTINEL_QUERY_71ad",
            lambda name, page: module.github_page("r", "TRACE_SENTINEL_AUTH_8f4e", name, page, api_origin=origin),
            run_path,
        )
        assert seen_run_ids == [3000, 3010]
        assert result == "301"
    else:
        try:
            module.github_page("r", "TRACE_SENTINEL_AUTH_8f4e", "x", 1, api_origin=origin)
        except module.ArtifactApiError:
            trace.request_failed = True
        else:
            raise AssertionError("closed port unexpectedly succeeded")
finally:
    trace.write("service_ti", "github")
print(json.dumps({"counts": dict(trace.counts), "complete": trace.invocation.started and trace.installed and not trace.incomplete}))
'''
        with tempfile.TemporaryDirectory() as directory:
            env = os.environ.copy()
            env["ADMIN_UI_TRACE_DIR"] = directory
            env["NO_PROXY"] = env["no_proxy"] = "*"
            trace_dir = Path(directory)
            supervisor = start_stage(self, trace_dir, "service_ti", env)
            curl_result = subprocess.run(
                [sys.executable, str(CURL_TRACE), "--stage", "service_ti", "--auth", "github", "--",
                 "curl", "--noproxy", "*", "-sf", f"{self.origin}/unchanged", "-o", os.devnull],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, check=False, timeout=10,
            )
            self.assertEqual(curl_result.returncode, 0, curl_result.stderr)
            self.server.oracle.clear()
            child = subprocess.run(
                [sys.executable, "-c", code, str(ARTIFACT_SCRIPT), self.origin, "pages", directory],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env, check=False, timeout=15,
            )
            self.assertEqual(child.returncode, 0, child.stderr)
            result = json.loads(child.stdout)
            self.assertEqual(result["counts"], {"non_api": 6})
            self.assertTrue(result["complete"])
            self.assertEqual(len(self.server.oracle), 6)
            provenance_paths = [urlsplit(path).path for _method, path, _host in self.server.oracle if "/actions/runs/" in path]
            self.assertEqual(provenance_paths, ["/repos/r/actions/runs/3000", "/repos/r/actions/runs/3010"])
            stage = finish_stage(trace_dir, "service_ti", env, supervisor)
            self.assertTrue(stage["complete"], stage)
            self.assertEqual(sum(r["numericSendCount"] for r in stage["records"]), 7)
            receipt_text = json.dumps(stage) + child.stdout + child.stderr
            for sentinel in SENTINELS:
                self.assertNotIn(sentinel, receipt_text)

        closed = socket.socket()
        closed.bind(("127.0.0.1", 0))
        port = closed.getsockname()[1]
        closed.close()
        with tempfile.TemporaryDirectory() as directory:
            env = os.environ.copy()
            env["ADMIN_UI_TRACE_DIR"] = directory
            env["NO_PROXY"] = env["no_proxy"] = "*"
            trace_dir = Path(directory)
            supervisor = start_stage(self, trace_dir, "service_ti", env)
            child = subprocess.run(
                [sys.executable, "-c", code, str(ARTIFACT_SCRIPT), f"http://127.0.0.1:{port}", "failure", directory],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env, check=False, timeout=15,
            )
            self.assertEqual(child.returncode, 0, child.stderr)
            stage = finish_stage(trace_dir, "service_ti", env, supervisor)
            self.assertEqual(sum(r["numericSendCount"] for r in stage["records"]), 0)
            self.assertFalse(stage["complete"])


class AggregatorTests(unittest.TestCase):
    def run_aggregator(self, trace_dir: Path, *, fixture_status: str = "passed") -> dict:
        env = os.environ.copy()
        env["ADMIN_UI_TRACE_DIR"] = str(trace_dir)
        env["ADMIN_UI_TRACE_TESTS"] = fixture_status
        env["TRACE_APP_RO_CONFIGURED"] = "false"
        env["TRACE_PACT_BROKER_CONFIGURED"] = "false"
        env["TRACE_STATUS_CUSTOMER_TI"] = "skipped"
        env["TRACE_STATUS_QUALITY_REPORT"] = "skipped"
        env["TRACE_STATUS_INFRA_LIFECYCLE"] = "success"
        for stage in ("sbom", "service_ti", "immutable_ti", "pit", "performance", "gate_health", "deploy_ti"):
            env[f"TRACE_STATUS_{stage.upper()}"] = "success"
        result = subprocess.run(
            [sys.executable, str(AGGREGATOR_SCRIPT)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=env,
            check=False,
            timeout=5,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads((trace_dir / "admin-ui-api-request-trace.json").read_text())
        return report

    def write_complete_receipts(self, trace_dir: Path) -> None:
        from importlib.util import spec_from_file_location, module_from_spec

        spec = spec_from_file_location("admin_ui_trace_ipc", IPC_SCRIPT)
        assert spec and spec.loader
        module = module_from_spec(spec)
        spec.loader.exec_module(module)
        for stage, clients in module.STAGES.items():
            if stage == "customer_ti":
                continue
            envelope = {
                "schemaVersion": 1,
                "stage": stage,
                "authAlias": module.AUTH[stage],
                "complete": True,
                "reasonEnum": None,
                "records": [
                    {"client": client, "destinationClass": "api", "numericSendCount": 2}
                    for client in sorted(clients)
                ],
            }
            (trace_dir / f"admin-ui-stage-{stage}.json").write_text(
                json.dumps(envelope), encoding="utf-8"
            )

    def test_optional_missing_client_is_not_reported_as_zero_quota(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            trace_dir = Path(directory)
            self.write_complete_receipts(trace_dir)
            report = self.run_aggregator(trace_dir)
            self.assertTrue(report["complete"])
            optional_rows = [row for row in report["records"] if row["reasonEnum"] == "optional_not_configured"]
            self.assertEqual(len(optional_rows), 2)
            self.assertEqual(optional_rows[0]["numericSendCount"], 0)
            self.assertFalse(optional_rows[0]["complete"])

    def test_wrong_client_for_stage_and_failed_fixture_are_incomplete(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            trace_dir = Path(directory)
            self.write_complete_receipts(trace_dir)
            path = trace_dir / "admin-ui-stage-gate_health.json"
            wrong = json.loads(path.read_text(encoding="utf-8"))
            wrong["records"].append({
                "client": "curl", "destinationClass": "api", "numericSendCount": 1
            })
            path.write_text(json.dumps(wrong), encoding="utf-8")
            report = self.run_aggregator(trace_dir, fixture_status="failed")
            self.assertFalse(report["complete"])
            self.assertEqual(report["localFixtureStatus"], "failed")
            self.assertTrue(any(row["reasonEnum"] == "malformed_receipt" for row in report["records"]))
            self.assertTrue(any(row["reasonEnum"] == "trace_fixture_failed" for row in report["records"]))

    def test_missing_infra_lifecycle_stage_is_incomplete_and_scope_is_explicit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            trace_dir = Path(directory)
            self.write_complete_receipts(trace_dir)
            (trace_dir / "admin-ui-stage-infra_lifecycle.json").unlink()
            report = self.run_aggregator(trace_dir)
            self.assertFalse(report["complete"])
            self.assertEqual(report["measurementScope"]["id"], "admin-ui-admitted-build-repository-owned-collector-fetches-v1")
            self.assertIn("quality_report", {row["stage"] for row in report["measurementScope"]["coveredClientStages"]})
            self.assertTrue(report["measurementScope"]["excludedTraffic"])

    def test_configured_broker_with_no_send_is_incomplete(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            trace_dir = Path(directory)
            self.write_complete_receipts(trace_dir)
            empty = {
                "schemaVersion": 1, "stage": "quality_report", "authAlias": "pact_broker",
                "complete": False, "reasonEnum": "no_send_observed", "records": [],
            }
            (trace_dir / "admin-ui-stage-quality_report.json").write_text(json.dumps(empty))
            env = os.environ.copy()
            env["ADMIN_UI_TRACE_DIR"] = str(trace_dir)
            env["ADMIN_UI_TRACE_TESTS"] = "passed"
            env["TRACE_APP_RO_CONFIGURED"] = "false"
            env["TRACE_PACT_BROKER_CONFIGURED"] = "true"
            env["TRACE_STATUS_CUSTOMER_TI"] = "skipped"
            env["TRACE_STATUS_QUALITY_REPORT"] = "success"
            env["TRACE_STATUS_INFRA_LIFECYCLE"] = "success"
            for stage in ("sbom", "service_ti", "immutable_ti", "pit", "performance", "gate_health", "deploy_ti"):
                env[f"TRACE_STATUS_{stage.upper()}"] = "success"
            result = subprocess.run([sys.executable, str(AGGREGATOR_SCRIPT)], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads((trace_dir / "admin-ui-api-request-trace.json").read_text())
            self.assertFalse(report["complete"])

    def test_failed_collector_retains_observed_sends_as_incomplete(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            trace_dir = Path(directory)
            self.write_complete_receipts(trace_dir)
            failed = {
                "schemaVersion": 1, "stage": "quality_report", "authAlias": "pact_broker",
                "complete": False, "reasonEnum": "trace_parse_failure",
                "records": [{"client": "node_fetch", "destinationClass": "api", "numericSendCount": 2}],
            }
            (trace_dir / "admin-ui-stage-quality_report.json").write_text(json.dumps(failed))
            env = os.environ.copy()
            env.update({
                "ADMIN_UI_TRACE_DIR": str(trace_dir), "ADMIN_UI_TRACE_TESTS": "passed",
                "TRACE_APP_RO_CONFIGURED": "false", "TRACE_PACT_BROKER_CONFIGURED": "true",
                "TRACE_STATUS_CUSTOMER_TI": "skipped", "TRACE_STATUS_QUALITY_REPORT": "failure",
                "TRACE_STATUS_INFRA_LIFECYCLE": "success",
            })
            for stage in ("sbom", "service_ti", "immutable_ti", "pit", "performance", "gate_health", "deploy_ti"):
                env[f"TRACE_STATUS_{stage.upper()}"] = "success"
            result = subprocess.run([sys.executable, str(AGGREGATOR_SCRIPT)], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads((trace_dir / "admin-ui-api-request-trace.json").read_text())
            rows = [row for row in report["records"] if row["stage"] == "quality_report" and row["client"] == "node_fetch"]
            self.assertEqual(sum(row["numericSendCount"] for row in rows), 2)
            self.assertTrue(all(not row["complete"] for row in rows))


class SupervisorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.previous_trace_dir = os.environ.get("ADMIN_UI_TRACE_DIR")
        cls.server = FixtureServer()
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.origin = f"http://127.0.0.1:{cls.server.server_port}"

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=2)
        if cls.previous_trace_dir is None:
            os.environ.pop("ADMIN_UI_TRACE_DIR", None)
        else:
            os.environ["ADMIN_UI_TRACE_DIR"] = cls.previous_trace_dir

    def test_lost_registration_taints_successful_client_without_changing_output(self) -> None:
        direct = subprocess.run(
            ["curl", "--noproxy", "*", "-s", f"{self.origin}/unchanged"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False, timeout=10,
        )
        with tempfile.TemporaryDirectory() as directory:
            trace_dir = Path(directory)
            env = os.environ.copy()
            env["ADMIN_UI_TRACE_DIR"] = str(trace_dir)
            os.environ["ADMIN_UI_TRACE_DIR"] = str(trace_dir)
            env["NO_PROXY"] = env["no_proxy"] = "*"
            server = start_stage(self, trace_dir, "sbom", env)
            socket_path = trace_dir / "sbom.sock"
            hidden_path = trace_dir / "sbom.sock.hidden"
            socket_path.rename(hidden_path)
            wrapped = subprocess.run(
                [sys.executable, str(CURL_TRACE), "--stage", "sbom", "--auth", "github", "--",
                 "curl", "--noproxy", "*", "-s", f"{self.origin}/unchanged"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, check=False, timeout=10,
            )
            hidden_path.rename(socket_path)
            stage = finish_stage(trace_dir, "sbom", env, server)
            self.assertEqual((wrapped.returncode, wrapped.stdout), (direct.returncode, direct.stdout))
            self.assertFalse(stage["complete"])
            self.assertEqual(stage["reasonEnum"], "ipc_failure")

    def test_taint_signal_does_not_depend_on_reading_pid_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            trace_dir = Path(directory)
            env = os.environ.copy()
            env["ADMIN_UI_TRACE_DIR"] = str(trace_dir)
            os.environ["ADMIN_UI_TRACE_DIR"] = str(trace_dir)
            server = start_stage(self, trace_dir, "sbom", env)
            spec = importlib.util.spec_from_file_location("admin_ui_trace_ipc", IPC_SCRIPT)
            assert spec and spec.loader
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            first = module.Invocation("sbom", "curl", "github")
            first.end(Counter(api=1), complete=True, reason=None)

            socket_path = trace_dir / "sbom.sock"
            hidden_path = trace_dir / "sbom.sock.hidden"
            socket_path.rename(hidden_path)
            with patch.object(Path, "read_text", side_effect=OSError("file reads unavailable")):
                missing = module.Invocation("sbom", "curl", "github")
                self.assertFalse(missing.started)
            hidden_path.rename(socket_path)

            stage = finish_stage(trace_dir, "sbom", env, server)
            self.assertFalse(stage["complete"])
            self.assertEqual(stage["reasonEnum"], "ipc_failure")
            self.assertEqual(stage["records"], [
                {"client": "curl", "destinationClass": "api", "numericSendCount": 1}
            ])

    def test_missing_terminal_frame_and_partial_counts_are_never_complete(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            trace_dir = Path(directory)
            env = os.environ.copy()
            env["ADMIN_UI_TRACE_DIR"] = str(trace_dir)
            os.environ["ADMIN_UI_TRACE_DIR"] = str(trace_dir)
            env["ADMIN_UI_TRACE_EOF_GRACE_SECONDS"] = "0.1"
            server = start_stage(self, trace_dir, "sbom", env)
            spec = importlib.util.spec_from_file_location("admin_ui_trace_ipc", IPC_SCRIPT)
            assert spec and spec.loader
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            invocation = module.Invocation("sbom", "curl", "github")
            self.assertTrue(invocation.started)
            invocation.close()
            stage = finish_stage(trace_dir, "sbom", env, server)
            self.assertFalse(stage["complete"])
            self.assertEqual(stage["reasonEnum"], "missing_terminal_frame")
            self.assertLessEqual(server.returncode, 2)

        with tempfile.TemporaryDirectory() as directory:
            trace_dir = Path(directory)
            env = os.environ.copy()
            env["ADMIN_UI_TRACE_DIR"] = str(trace_dir)
            os.environ["ADMIN_UI_TRACE_DIR"] = str(trace_dir)
            server = start_stage(self, trace_dir, "sbom", env)
            spec = importlib.util.spec_from_file_location("admin_ui_trace_ipc", IPC_SCRIPT)
            assert spec and spec.loader
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            invocation = module.Invocation("sbom", "curl", "github")
            invocation.end(Counter(api=3), complete=False, reason="trace_parse_failure")
            stage = finish_stage(trace_dir, "sbom", env, server)
            self.assertFalse(stage["complete"])
            self.assertEqual(stage["records"], [
                {"client": "curl", "destinationClass": "api", "numericSendCount": 3}
            ])

    def test_generated_exit_trap_preserves_bash_e_without_enabling_pipefail(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            trace_dir = Path(directory)
            env = os.environ.copy()
            env["ADMIN_UI_TRACE_DIR"] = str(trace_dir)
            env["GITHUB_WORKSPACE"] = str(ROOT)
            begin = subprocess.run(
                [sys.executable, str(IPC_SCRIPT), "begin", "--stage", "sbom"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env, check=False, timeout=5,
            )
            self.assertEqual(begin.returncode, 0, begin.stderr)
            shell = subprocess.run(
                ["bash", "-e", "-c", begin.stdout + "false | true\nprintf 'continued\\n'\n"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env, check=False, timeout=5,
            )
            self.assertEqual((shell.returncode, shell.stdout), (0, "continued\n"))
            stage = json.loads((trace_dir / "admin-ui-stage-sbom.json").read_text())
            self.assertFalse(stage["complete"])

        with tempfile.TemporaryDirectory() as directory:
            trace_dir = Path(directory)
            env = os.environ.copy()
            env["ADMIN_UI_TRACE_DIR"] = str(trace_dir)
            env["GITHUB_WORKSPACE"] = str(ROOT)
            begin = subprocess.run(
                [sys.executable, str(IPC_SCRIPT), "begin", "--stage", "sbom"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env, check=False, timeout=5,
            )
            shell = subprocess.run(
                ["bash", "-e", "-c", begin.stdout + "exit 17\n"],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env, check=False, timeout=5,
            )
            self.assertEqual(shell.returncode, 17)
            self.assertTrue((trace_dir / "admin-ui-stage-sbom.json").is_file())


if __name__ == "__main__":
    unittest.main(verbosity=2)
