#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run a localhost settlement-to-ledger-to-balance proof with optional audit ingestion."""

from __future__ import annotations

import argparse
import datetime
import hashlib
import http.client
import http.server
import json
import os
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from decimal import Decimal
from collections.abc import Callable
from pathlib import Path
from typing import Any

import yaml

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(tempfile.mkdtemp(prefix="openbank-real-settlement-"))
OUT.chmod(0o700)
PREFIX = "ob-proof-" + uuid.uuid4().hex[:10]
RUN_TIMEOUT_SECONDS = 180
REQUEST_TIMEOUT_SECONDS = 10
containers: list[str] = []
processes: list[subprocess.Popen[str]] = []
process_logs: list[Any] = []
response_loss_proxy: CommittedResponseLossProxy | None = None


class CommittedResponseLossProxy:
    """Forward real writes, losing bounded successful replies after a journal or hold commits."""

    def __init__(self, upstream_port: int, drop_count: int, resource: str = "journal"):
        assert resource in {"journal", "hold"}
        self.on_drop = None
        self.resource = resource
        self.drop_count = drop_count
        self.lock = threading.Lock()
        self.successful_resource_ids: list[str] = []
        self.dropped_responses = 0
        proxy = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self) -> None:
                if not (self.path == "/api/v1/journals" if resource == "journal" else
                        self.path.startswith("/api/v1/balances/") and self.path.endswith("/holds")):
                    self.send_error(404)
                    return
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                upstream = http.client.HTTPConnection("127.0.0.1", upstream_port, timeout=REQUEST_TIMEOUT_SECONDS)
                try:
                    upstream.request("POST", self.path, body, {
                        "Content-Type": "application/json",
                        "Authorization": self.headers.get("Authorization", ""),
                    })
                    response = upstream.getresponse()
                    payload = response.read()
                    status = response.status
                    content_type = response.getheader("Content-Type", "application/json")
                    drop = False
                    if 200 <= status < 300:
                        committed = json.loads(payload)
                        if resource == "journal" and committed.get("status") != "POSTED":
                            raise ValueError("Fault injection requires a confirmed POSTED journal")
                        if resource == "hold" and (committed.get("releasedAt") is not None or not committed.get("referenceId")):
                            raise ValueError("Fault injection requires a committed active hold")
                        with proxy.lock:
                            proxy.successful_resource_ids.append(committed["id"])
                            if proxy.dropped_responses < proxy.drop_count:
                                proxy.dropped_responses += 1
                                drop = True
                    if drop:
                        if proxy.on_drop is not None:
                            proxy.on_drop()
                        # Only lose a successful response from the real upstream.
                        self.close_connection = True
                        self.connection.shutdown(socket.SHUT_RDWR)
                        return
                    self.send_response(status)
                    self.send_header("Content-Type", content_type)
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)
                except (OSError, ValueError, KeyError, http.client.HTTPException):
                    self.send_error(502, "Local fault proxy upstream failed")
                finally:
                    upstream.close()

            def log_message(self, format: str, *args: Any) -> None:
                pass  # Never retain authorization headers or request payloads.

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def port(self) -> int:
        return self.server.server_address[1]

    def evidence(self, resource_id: str) -> dict[str, Any]:
        with self.lock:
            assert self.dropped_responses == self.drop_count, "Expected committed replies were not lost"
            expected_posts = 2 if self.drop_count == 1 else 5
            assert len(self.successful_resource_ids) == expected_posts, "Unexpected settlement retry count"
            assert set(self.successful_resource_ids) == {resource_id}, "Retry created a different resource"
            return {
                "droppedResponses": self.dropped_responses,
                "successfulUpstreamPosts": len(self.successful_resource_ids),
                ("journalId" if self.resource == "journal" else "holdId"): resource_id,
            }

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)


def run(*args: str, input_text: str | None = None, timeout: int = RUN_TIMEOUT_SECONDS) -> str:
    result = subprocess.run(
        args,
        input=input_text,
        text=True,
        capture_output=True,
        timeout=timeout,
        check=False,
        cwd=ROOT,
    )
    if result.returncode:
        raise RuntimeError(f"{args[:3]} failed: {result.stderr[-1800:]}")
    return result.stdout.strip()


def port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def docker(
    name: str,
    image: str,
    inner_port: int,
    *,
    args: tuple[str, ...] = (),
    env: dict[str, str] | None = None,
    mount: str | None = None,
) -> tuple[str, int]:
    container_name = f"{PREFIX}-{name}"
    host_port = port()
    command = [
        "docker",
        "run",
        "-d",
        "--name",
        container_name,
        "--label",
        f"openbank.proof={PREFIX}",
        "-p",
        f"127.0.0.1:{host_port}:{inner_port}",
    ]
    for key, value in (env or {}).items():
        command.extend(["-e", f"{key}={value}"])
    if mount:
        command.extend(["-v", mount])
    command.extend([image, *args])
    run(*command)
    containers.append(container_name)
    return container_name, host_port


def request(
    url: str,
    body: dict[str, Any] | None = None,
    token: str | Callable[[], str] | None = None,
    *,
    form: bool = False,
    method: str | None = None,
    approval_id: str | None = None,
) -> Any:
    headers: dict[str, str] = {}
    if token:
        headers["Authorization"] = f"Bearer {token() if callable(token) else token}"
    if approval_id:
        headers["X-Approval-Id"] = approval_id
    payload = None
    if body is not None:
        payload = (
            urllib.parse.urlencode(body) if form else json.dumps(body)
        ).encode()
        headers["Content-Type"] = (
            "application/x-www-form-urlencoded" if form else "application/json"
        )
    try:
        with urllib.request.urlopen(
            urllib.request.Request(url, data=payload, headers=headers, method=method),
            timeout=REQUEST_TIMEOUT_SECONDS,
        ) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        detail = error.read().decode(errors="replace")[:1200]
        raise RuntimeError(f"HTTP {error.code} {url}: {detail}") from None


def operator_session(issuer: str, username: str, password: str) -> Callable[[], str]:
    """Obtain/renew before a request; never replay a business request after a 401."""
    access_token = ""
    renew_at = 0.0

    def current_token() -> str:
        nonlocal access_token, renew_at
        if not access_token or time.monotonic() >= renew_at:
            started = time.monotonic()
            response = request(
                f"{issuer}/protocol/openid-connect/token",
                {"grant_type": "password", "client_id": "proof-browser", "username": username, "password": password},
                form=True,
            )
            lifetime = float(response["expires_in"])
            if lifetime <= 0:
                raise RuntimeError("OIDC returned a non-positive token lifetime")
            access_token = response["access_token"]
            renew_at = started + lifetime - min(30.0, lifetime / 2)
        return access_token

    return current_token


def expect_http_status(
    url: str,
    expected_status: int,
    body: dict[str, Any] | None = None,
    token: str | Callable[[], str] | None = None,
    *,
    method: str | None = None,
) -> None:
    try:
        request(url, body, token, method=method)
    except RuntimeError as error:
        assert f"HTTP {expected_status} " in str(error), str(error)
        return
    raise AssertionError(f"Expected HTTP {expected_status} from {url}")


def assert_owned_containers_running() -> None:
    for container_name in containers:
        state = run(
            "docker",
            "inspect",
            "--format",
            "{{.State.Status}}",
            container_name,
            timeout=5,
        )
        if state != "running":
            raise RuntimeError(f"Owned container {container_name} exited ({state}); inspect {OUT}")


def until(check: Any, label: str, seconds: int = 120) -> Any:
    deadline = time.monotonic() + seconds
    last_error = None
    while time.monotonic() < deadline:
        assert_owned_containers_running()
        try:
            value = check()
            if value:
                return value
        except Exception as error:  # readiness can transiently fail while a service starts
            last_error = str(error)
        for process in processes:
            if process.poll() is not None:
                raise RuntimeError(f"Process exited {process.returncode}; inspect {OUT}")
        time.sleep(1)
    raise RuntimeError(f"Timed out waiting for {label}: {last_error}")


def sql(db: str, query: str) -> str:
    return run(
        "docker",
        "exec",
        postgres,
        "psql",
        "-U",
        "proof",
        "-d",
        db,
        "-qAt",
        "-v",
        "ON_ERROR_STOP=1",
        "-c",
        query,
    )


def start_process(args: list[str], name: str, env: dict[str, str]) -> subprocess.Popen[str]:
    log = (OUT / f"{name}.log").open("w")
    process_logs.append(log)
    process = subprocess.Popen(
        args,
        stdout=log,
        stderr=subprocess.STDOUT,
        env=env,
        cwd=ROOT,
        text=True,
    )
    processes.append(process)
    return process


def child_environment() -> dict[str, str]:
    allowed = ("PATH", "HOME", "TMPDIR", "LANG", "TZ")
    return {key: os.environ[key] for key in allowed if key in os.environ}


def java_executable() -> str:
    java_home = os.environ.get("JAVA_HOME")
    candidate = Path(java_home) / "bin" / "java" if java_home else None
    if candidate and candidate.is_file():
        return str(candidate)
    java = shutil.which("java")
    if java:
        return java
    raise RuntimeError("Java not found; set JAVA_HOME or add java to PATH")


def create_realm(secret: str, password: str, with_audit: bool = False) -> Path:
    realm = {
        "realm": "settlement-proof",
        "enabled": True,
        "roles": {"realm": [{"name": role} for role in ["ROLE_OPERATOR", "ROLE_API", "ROLE_AUDITOR"]]},
        "clients": [
            {
                "clientId": "proof-browser",
                "publicClient": True,
                "directAccessGrantsEnabled": True,
                "defaultClientScopes": ["profile", "roles"],
            }
        ],
        "users": [
            {
                "username": "proof-operator",
                "enabled": True,
                "firstName": "Synthetic",
                "lastName": "Operator",
                "email": "proof@example.invalid",
                "emailVerified": True,
                "realmRoles": ["ROLE_OPERATOR", "ROLE_AUDITOR"] if with_audit else ["ROLE_OPERATOR"],
                "credentials": [{"type": "password", "value": password, "temporary": False}],
            }
        ],
    }
    realm["users"].append(dict(realm["users"][0], username="proof-checker", email="checker@example.invalid"))
    for client_id in ["openbank-services", "openbank-settlement", "proof-unrelated"]:
        realm["clients"].append(
            {
                "clientId": client_id,
                "secret": secret,
                "publicClient": False,
                "serviceAccountsEnabled": True,
                "defaultClientScopes": ["profile", "roles"],
            }
        )
        realm["users"].append(
            {
                "username": f"service-account-{client_id}",
                "enabled": True,
                "serviceAccountClientId": client_id,
                "realmRoles": ["ROLE_API"],
            }
        )
    realm_file = OUT / "realm.json"
    realm_file.write_text(json.dumps(realm))
    return realm_file


def start_opa(name: str, component: str) -> int:
    bundle = yaml.safe_load(
        (ROOT / f"openbank-infra/gitops/components/{component}/{name}-opa-bundle.yaml").read_text()
    )["data"]
    bundle_dir = OUT / f"{name}-opa"
    bundle_dir.mkdir()
    for key, value in bundle.items():
        destination = bundle_dir / {
            "agents-data.yaml": "agents/data.yaml",
            "rules-data.yaml": "rules/data.yaml",
            "manifest.json": ".manifest",
        }.get(key, key)
        destination.parent.mkdir(exist_ok=True)
        destination.write_text(value)
    _, host_port = docker(
        f"{name}-opa",
        "openpolicyagent/opa:1.17.0",
        8181,
        args=("run", "--server", "--addr=0.0.0.0:8181", "--bundle", "/bundle"),
        mount=f"{bundle_dir}:/bundle:ro",
    )
    until(lambda: request(f"http://127.0.0.1:{host_port}/health?bundles=true") is not None, f"{name} OPA")
    return host_port


def runtime_digest(directory: Path) -> str:
    """Identify the complete fast-jar runtime, including application and shared libraries."""
    digest = hashlib.sha256()
    for path in sorted(directory.rglob("*")):
        if path.is_file():
            digest.update(path.relative_to(directory).as_posix().encode() + b"\0")
            digest.update(hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def main() -> None:
    global response_loss_proxy
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--drop-ledger-response", nargs="?", const=1, default=0, type=int, choices=(1, 5),
        help="Lose one committed journal reply, or all five activity attempts",
    )
    parser.add_argument("--recover-after-loss", action="store_true", help="Reset the exhausted local workflow before journal booking")
    parser.add_argument("--with-audit", action="store_true", help="Prove real settlement outbox ingestion and audit chain integrity")
    parser.add_argument("--reject-cover", action="store_true", help="Verify insufficient cover never reaches ledger booking")
    parser.add_argument("--drop-cover-responses", action="store_true", help="Lose all five committed cover replies and verify safe uncertainty")
    parser.add_argument("--crash-worker-after-ledger-commit", action="store_true", help="Kill and restart the settlement JVM after confirmed journal commit")
    parser.add_argument("--with-operator-approval", action="store_true", help="Enforce settlement maker/checker approval using two real OIDC principals")
    args = parser.parse_args()
    if args.crash_worker_after_ledger_commit:
        if args.drop_ledger_response or args.drop_cover_responses or args.recover_after_loss:
            parser.error("worker crash cannot be combined with another fault mode")
        args.drop_ledger_response = 1
    if args.drop_cover_responses and (args.drop_ledger_response or args.reject_cover or args.with_audit):
        parser.error("--drop-cover-responses is a standalone three-service proof")
    if args.recover_after_loss and args.drop_ledger_response != 5:
        parser.error("--recover-after-loss requires --drop-ledger-response 5")
    global postgres

    java = java_executable()
    service_names = ["ledger", "balance", "settlement"] + (["audit"] if args.with_audit else [])
    service_jars = {
        name: ROOT / f"openbank-{name}-service/build/quarkus-app/quarkus-run.jar"
        for name in service_names
    }
    source_metadata = {
        "git_head": run("git", "rev-parse", "HEAD"),
        "working_tree_dirty": bool(run("git", "status", "--porcelain")),
        "service_runtime_sha256": {
            name: runtime_digest(jar_path.parent)
            for name, jar_path in service_jars.items()
        },
    }
    secret = uuid.uuid4().hex
    password = uuid.uuid4().hex
    realm_file = create_realm(secret, password, args.with_audit)
    print(f"Evidence directory: {OUT}", flush=True)

    postgres, postgres_port = docker(
        "postgres",
        "postgres:18-alpine",
        5432,
        env={"POSTGRES_USER": "proof", "POSTGRES_PASSWORD": secret},
    )
    until(
        lambda: run(
            "docker", "exec", postgres, "pg_isready", "-h", "127.0.0.1", "-U", "proof", "-d", "proof"
        ),
        "postgres",
    )
    if args.with_audit:
        sql("proof", "CREATE ROLE openbank NOLOGIN")  # Existing audit migration grants, isolated fixture only.
    for database in service_names:
        sql("proof", f"CREATE DATABASE {database}")

    redis, redis_port = docker("redis", "valkey/valkey:8-alpine", 6379)
    keycloak, keycloak_port = docker(
        "keycloak",
        "quay.io/keycloak/keycloak:26.6.3",
        8080,
        args=("start-dev", "--import-realm"),
        mount=f"{realm_file}:/opt/keycloak/data/import/settlement-proof-realm.json:ro",
    )

    # JVMs use the published Kafka endpoint; all published container ports bind to loopback.
    kafka_port = port()
    kafka = f"{PREFIX}-kafka"
    run(
        "docker",
        "run",
        "-d",
        "--name",
        kafka,
        "--label",
        f"openbank.proof={PREFIX}",
        "-p",
        f"127.0.0.1:{kafka_port}:19092",
        "redpandadata/redpanda:v24.1.2",
        "redpanda",
        "start",
        "--overprovisioned",
        "--smp",
        "1",
        "--memory",
        "512M",
        "--reserve-memory",
        "0M",
        "--node-id",
        "0",
        "--check=false",
        "--kafka-addr",
        "internal://0.0.0.0:9092,external://0.0.0.0:19092",
        "--advertise-kafka-addr",
        f"internal://127.0.0.1:9092,external://127.0.0.1:{kafka_port}",
    )
    containers.append(kafka)
    until(lambda: run("docker", "exec", kafka, "rpk", "cluster", "info", "-X", "brokers=127.0.0.1:9092"), "Kafka")
    audit_topics = []
    if args.with_audit:
        audit_config = yaml.safe_load((ROOT / "openbank-audit-service/src/main/resources/application.yaml").read_text())
        audit_topics = audit_config["mp"]["messaging"]["incoming"]["audit-events-in"]["topics"].split(",")
        audit_topics.append("openbank.dlq.audit.audit-events-in")
    for topic in sorted(set([
        "openbank.ledger.journal.posted",
        "openbank.account.events",
        "openbank.balance.events",
        "openbank.settlement.events",
        "openbank.dlq.balance.ledger-events-in",
        "openbank.dlq.balance.balance-init-in",
    ] + audit_topics)):
        run("docker", "exec", kafka, "rpk", "topic", "create", topic, "-X", "brokers=127.0.0.1:9092")

    temporal, temporal_port = docker(
        "temporal",
        "temporalio/temporal:1.7.3",
        7233,
        args=("server", "start-dev", "--ip", "0.0.0.0", "--namespace", "openbank-settlement"),
    )
    until(
        lambda: run("docker", "exec", temporal, "temporal", "operator", "cluster", "health", "--address", "127.0.0.1:7233"),
        "Temporal",
    )
    issuer = f"http://127.0.0.1:{keycloak_port}/realms/settlement-proof"
    until(lambda: request(f"{issuer}/.well-known/openid-configuration"), "Keycloak")
    token = operator_session(issuer, "proof-operator", password)
    checker_token = None
    if args.with_operator_approval:
        checker_token = operator_session(issuer, "proof-checker", password)
    print("Local infrastructure ready; synthetic OIDC sessions configured", flush=True)

    service_ports = {name: port() for name in service_names}
    ledger_client_port = service_ports["ledger"]
    if args.drop_ledger_response:
        response_loss_proxy = CommittedResponseLossProxy(ledger_client_port, args.drop_ledger_response)
        ledger_client_port = response_loss_proxy.port
    balance_client_port = service_ports["balance"]
    if args.drop_cover_responses:
        response_loss_proxy = CommittedResponseLossProxy(balance_client_port, 5, resource="hold")
        balance_client_port = response_loss_proxy.port
    opa_ports = {
        name: start_opa(name, component)
        for name, component in ([("ledger", "ledger"), ("balance", "balances"), ("settlement", "payments")]
                                + ([("audit", "audit")] if args.with_audit else []))
    }
    service_processes = {}
    service_commands = {}
    service_environments = {}
    for name in service_names:
        env = child_environment()
        env.update(
            {
                "QUARKUS_HTTP_PORT": str(service_ports[name]),
                "QUARKUS_HTTP_HOST": "127.0.0.1",
                "QUARKUS_MANAGEMENT_HOST": "127.0.0.1",
                "QUARKUS_MANAGEMENT_PORT": str(port()),
                "QUARKUS_DATASOURCE_USERNAME": "proof",
                "QUARKUS_DATASOURCE_PASSWORD": secret,
                "QUARKUS_DATASOURCE_JDBC_URL": f"jdbc:postgresql://127.0.0.1:{postgres_port}/{name}",
                "QUARKUS_DATASOURCE_REACTIVE_URL": f"postgresql://127.0.0.1:{postgres_port}/{name}",
                "QUARKUS_OIDC_AUTH_SERVER_URL": issuer,
                "OIDC_CLIENT_SECRET": secret,
                "OIDC_M2M_CLIENT_SECRET": secret,
                "QUARKUS_REDIS_HOSTS": f"redis://127.0.0.1:{redis_port}",
                "KAFKA_BOOTSTRAP_SERVERS": f"127.0.0.1:{kafka_port}",
                "AUTHZ_ENFORCE": "true",
                "AUTHZ_FOUR_EYES_ENFORCE": "true" if name == "settlement" and args.with_operator_approval else "false",
                "OPA_URL": f"http://127.0.0.1:{opa_ports[name]}",
                "OPA_TIMEOUT_MS": "5000",
                "OPENBANK_TEMPORAL_SERVER_URL": f"127.0.0.1:{temporal_port}",
                "OPENBANK_TEMPORAL_NAMESPACE": "openbank-settlement",
                "BALANCE_SERVICE_URL": f"http://127.0.0.1:{balance_client_port}",
                "LEDGER_SERVICE_URL": f"http://127.0.0.1:{ledger_client_port}",
                "SETTLEMENT_LEDGER_PROJECTION_ENABLED": "true",
                "QUARKUS_OTEL_SDK_DISABLED": "true",
            }
        )
        properties = ["-Dmp.messaging.incoming.ledger-events-in.auto.offset.reset=earliest"] if name == "balance" else []
        if name == "audit":
            properties.append("-Dmp.messaging.incoming.audit-events-in.auto.offset.reset=earliest")
        command = [java, "-Xmx384m", *properties, "-jar", str(service_jars[name])]
        service_commands[name] = command
        service_environments[name] = env
        service_processes[name] = start_process(command, name, env)
        management_port = env["QUARKUS_MANAGEMENT_PORT"]
        until(
            lambda management_port=management_port: request(
                f"http://127.0.0.1:{management_port}/q/health/ready"
            ).get("status")
            == "UP",
            f"{name} readiness",
        )
        print(f"{name} ready with OIDC and OPA enforcement", flush=True)

    # Prove the live HTTP security boundaries with synthetic credentials, without retaining tokens.
    balance_base = f"http://127.0.0.1:{service_ports['balance']}/api/v1/balances"
    expect_http_status(f"{balance_base}/{uuid.uuid4()}/CZK", 401)
    service_token = request(
        f"{issuer}/protocol/openid-connect/token",
        {
            "grant_type": "client_credentials",
            "client_id": "proof-unrelated",
            "client_secret": secret,
        },
        form=True,
    )["access_token"]
    expect_http_status(
        f"{balance_base}/{uuid.uuid4()}/holds",
        403,
        {
            "amount": 1,
            "currency": "CZK",
            "reason": "authorization-denial-proof",
            "referenceId": "denial-proof-" + uuid.uuid4().hex,
        },
        service_token,
    )
    del service_token
    print("Confirmed unauthenticated read is 401 and unrelated ROLE_API hold attempt is 403", flush=True)

    payer, payee = str(uuid.uuid4()), str(uuid.uuid4())
    balance = balance_base
    for account, initial_amount in [(payer, 0), (payee, 0)]:
        request(f"{balance}/{account}/initialize", {"currency": "CZK", "initialAmount": initial_amount}, token)

    ledger_endpoint = f"http://127.0.0.1:{service_ports['ledger']}/api/v1/journals"
    today = datetime.date.today().isoformat()
    funding_id = str(uuid.uuid4())
    funding = {
        "idempotencyKey": f"funding-{funding_id}",
        "transactionId": funding_id,
        "entryDate": today,
        "valueDate": today,
        "description": "Isolated proof funding",
        "createdBy": str(uuid.uuid4()),
        "lines": [],
    }
    for side, gl_account, sub_account in [
        ("DEBIT", "a0000000-0000-0000-0000-000000000001", None),
        ("CREDIT", "a0000000-0000-0000-0000-000000000002", payer),
    ]:
        funding["lines"].append(
            {
                "glAccountId": gl_account,
                "side": side,
                "amount": 100,
                "currencyCode": "CZK",
                "baseAmount": 100,
                "baseCurrencyCode": "CZK",
                "subAccountId": sub_account,
            }
        )
    request(ledger_endpoint, funding, token)
    until(
        lambda: Decimal(str(request(f"{balance}/{payer}/CZK", token=token)["bookedAmount"])) == Decimal("100"),
        "ledger funding projected",
    )

    # Confirm the ledger rejects excess NUMERIC scale before settlement normalizes insignificant zeros.
    invalid = json.loads(json.dumps(funding))
    invalid["idempotencyKey"] = "precision-" + uuid.uuid4().hex
    invalid["transactionId"] = str(uuid.uuid4())
    for line in invalid["lines"]:
        line["amount"] = "40.0000"
        line["baseAmount"] = "40.0000"
    try:
        request(ledger_endpoint, invalid, token)
        raise AssertionError("Precision reproduction unexpectedly accepted")
    except RuntimeError as error:
        assert "HTTP 400" in str(error) and "scale 4" in str(error), str(error)
        print("Confirmed ledger rejects database scale 4", flush=True)

    settlement_body = {
        "idempotencyKey": "proof-" + uuid.uuid4().hex,
        "payerAccountId": payer,
        "payeeAccountId": payee,
        "amount": 40,
        "currency": "CZK",
    }
    settlement_endpoint = f"http://127.0.0.1:{service_ports['settlement']}/api/v1/settlements"
    approval_evidence = []

    def originate_with_approval(instruction: dict[str, Any]) -> dict[str, Any]:
        if not args.with_operator_approval:
            return request(settlement_endpoint, instruction, token)
        before = sql("settlement", "SELECT count(*) FROM settlements")
        pending = request(settlement_endpoint, instruction, token)
        assert pending["status"] == "PENDING_APPROVAL", pending
        approval_id = str(uuid.UUID(pending["approvalId"]))
        assert sql("settlement", "SELECT count(*) FROM settlements") == before
        decision_url = f"{settlement_endpoint}/approvals/{approval_id}"
        detail = request(decision_url, token=checker_token)
        proposal_id = str(uuid.UUID(detail["proposalId"]))
        assert detail["id"] == approval_id and detail["makerId"] == "proof-operator"
        reviewed_instruction = detail["instruction"]
        assert isinstance(reviewed_instruction["amount"], str), "Reviewable amounts must be exact decimal text"
        assert Decimal(reviewed_instruction["amount"]) == Decimal(str(instruction["amount"]))
        assert {k: v for k, v in reviewed_instruction.items() if k != "amount"} == {
            k: v for k, v in instruction.items() if k != "amount"
        }, "Stored proposal differs from the maker's instruction"
        expect_http_status(decision_url, 400, {}, checker_token, method="PATCH")
        expect_http_status(decision_url, 400, {"approve": None}, checker_token, method="PATCH")
        decision = {"approve": True, "instruction": detail["instruction"]}
        expect_http_status(decision_url, 403, decision, token, method="PATCH")
        changed = {"approve": True, "instruction": dict(instruction, amount=instruction["amount"] + 1)}
        expect_http_status(decision_url, 400, changed, checker_token, method="PATCH")
        approved = request(decision_url, decision, checker_token, method="PATCH")
        assert approved["status"] == "APPROVED" and approved["makerId"] == "proof-operator"
        assert approved["decidedBy"] == "proof-checker", approved
        result = request(settlement_endpoint, instruction, token, approval_id=approval_id)
        assert sql("settlement", f"SELECT status FROM settlement_operator_approvals WHERE id='{approval_id}'") == "EXECUTED"
        states = json.loads(sql("settlement", "SELECT json_agg(payload::jsonb->>'status' ORDER BY id) "
                               f"FROM settlement_outbox WHERE aggregate_id='{approval_id}'"))
        assert states == ["PENDING", "APPROVED", "EXECUTED"], states
        approval_evidence.append({"approvalId": approval_id, "proposalId": proposal_id,
                                  "settlementId": result["id"], "states": states})
        return result

    crash_evidence = None
    worker_killed = threading.Event()
    if args.crash_worker_after_ledger_commit:
        assert response_loss_proxy is not None
        original_worker = service_processes["settlement"]

        def kill_worker() -> None:
            assert original_worker.poll() is None, "Worker exited before fault injection"
            original_worker.kill()
            assert original_worker.wait(timeout=10) == -9, "Expected SIGKILL"
            worker_killed.set()

        response_loss_proxy.on_drop = kill_worker
    created = originate_with_approval(settlement_body)
    settlement_id = created["id"]
    print(f"Originated settlement {settlement_id}", flush=True)
    if args.crash_worker_after_ledger_commit:
        assert worker_killed.wait(timeout=60), "No committed journal reached the crash hook"
        processes.remove(original_worker)  # Already reaped and verified as the intentional SIGKILL.
        restarted = start_process(service_commands["settlement"], "settlement-restarted", service_environments["settlement"])
        management = service_environments["settlement"]["QUARKUS_MANAGEMENT_PORT"]
        until(lambda: request(f"http://127.0.0.1:{management}/q/health/ready").get("status") == "UP", "restarted worker readiness")
        crash_evidence = {"signal": "SIGKILL", "oldExitCode": original_worker.returncode, "restarted": restarted.poll() is None}
        # Retain real server timing even when the baseline cannot recover within the proof deadline.
        history = json.loads(run("docker", "exec", temporal, "temporal", "workflow", "show",
            "--workflow-id", f"settlement-{settlement_id}", "--namespace", "openbank-settlement",
            "--address", "127.0.0.1:7233", "--output", "json", "--command-timeout", "10s"))
        crash_evidence["originalRunId"] = history["events"][0]["workflowExecutionStartedEventAttributes"]["originalExecutionRunId"]
        (OUT / "workflow-after-worker-crash.json").write_text(json.dumps(history, indent=2))
        print("Settlement worker killed after commit and restarted", flush=True)
    if args.drop_cover_responses:
        until(
            lambda: sql("settlement", f"SELECT status FROM settlements WHERE id='{settlement_id}'") == "BALANCE_STATE_UNKNOWN",
            "lost cover replies recorded as uncertain", 90,
        )

        def closed_history() -> dict[str, Any] | None:
            history = json.loads(run(
                "docker", "exec", temporal, "temporal", "workflow", "show",
                "--workflow-id", f"settlement-{settlement_id}", "--namespace", "openbank-settlement",
                "--address", "127.0.0.1:7233", "--output", "json", "--command-timeout", "10s",
            ))
            return history if history["events"][-1]["eventType"] == "EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED" else None

        history = until(closed_history, "lost-cover workflow completion", 30)
        (OUT / "workflow-lost-cover.json").write_text(json.dumps(history, indent=2))
        scheduled = [e["activityTaskScheduledEventAttributes"]["activityType"]["name"]
                     for e in history["events"] if "activityTaskScheduledEventAttributes" in e]
        assert "ReserveSettlementCover" in scheduled and "BookToLedger" not in scheduled, scheduled
        holds = json.loads(sql("balance", "SELECT json_agg(h) FROM (SELECT hold_id AS id, amount, released_at "
                              f"FROM balance_holds WHERE reference_id='{settlement_id}' AND account_id='{payer}' AND currency='CZK') h"))
        assert len(holds) == 1 and Decimal(str(holds[0]["amount"])) == Decimal("40") and holds[0]["released_at"] is None
        journals = request(f"{ledger_endpoint}/transaction/{settlement_id}", token=token)
        assert journals == [], journals
        balances = [request(f"{balance}/{account}/CZK", token=token) for account in (payer, payee)]
        for observed, expected in zip(balances, [(100, 40, 60), (0, 0, 0)], strict=True):
            assert tuple(Decimal(str(observed[k])) for k in ("bookedAmount", "reservedAmount", "availableAmount")) == expected
        assert originate_with_approval(settlement_body)["id"] == settlement_id
        replayed = [request(f"{balance}/{account}/CZK", token=token) for account in (payer, payee)]
        assert [b["version"] for b in replayed] == [b["version"] for b in balances]
        assert sql("settlement", "SELECT count(*) FROM settlement_outbox "
                   f"WHERE aggregate_id='{settlement_id}' AND payload::jsonb->>'status'='BALANCE_STATE_UNKNOWN'") == "1"
        assert response_loss_proxy is not None
        evidence = response_loss_proxy.evidence(holds[0]["id"])
        (OUT / "result.json").write_text(json.dumps({
            "status": "PASS", "settlementId": settlement_id, "settlementStatus": "BALANCE_STATE_UNKNOWN",
            "coverResponseLoss": evidence, "holds": holds, "journals": journals,
            "balances": balances, "scheduledActivities": scheduled, "source": source_metadata,
        }, indent=2))
        print("PASS lost cover replies", json.dumps(evidence), flush=True)
        return
    expected_status = "LEDGER_STATE_UNKNOWN" if args.drop_ledger_response == 5 else "BOOKED"
    until(
        lambda: sql("settlement", f"SELECT status FROM settlements WHERE id='{settlement_id}'") == expected_status,
        f"settlement {expected_status}",
        90,
    )

    def balances_correct() -> tuple[dict[str, Any], dict[str, Any]] | None:
        payer_balance = request(f"{balance}/{payer}/CZK", token=token)
        payee_balance = request(f"{balance}/{payee}/CZK", token=token)
        correct = (
            Decimal(str(payer_balance["bookedAmount"])) == Decimal("60")
            and Decimal(str(payee_balance["bookedAmount"])) == Decimal("40")
            and Decimal(str(payer_balance["reservedAmount"])) == Decimal("0")
            and Decimal(str(payer_balance["availableAmount"])) == Decimal("60")
            and Decimal(str(payee_balance["reservedAmount"])) == Decimal("0")
            and Decimal(str(payee_balance["availableAmount"])) == Decimal("40")
        )
        return (payer_balance, payee_balance) if correct else None

    final_balances = until(balances_correct, "projected balances", 60)
    assert sql(
        "settlement",
        f"SELECT settlement_protocol FROM settlements WHERE id='{settlement_id}'",
    ) == "LEDGER_PROJECTION"
    assert sql(
        "balance",
        "SELECT count(*) FROM balance_holds "
        f"WHERE reference_id='{settlement_id}' AND account_id='{payer}' "
        "AND amount=40 AND released_at IS NOT NULL",
    ) == "1"

    duplicate = originate_with_approval(settlement_body)
    assert duplicate["id"] == settlement_id
    journals = request(
        f"http://127.0.0.1:{service_ports['ledger']}/api/v1/journals/transaction/{settlement_id}",
        token=token,
    )
    assert len(journals) == 1 and journals[0]["status"] == "POSTED", journals
    assert journals[0]["transactionId"] == settlement_id
    assert len(journals[0]["lines"]) == 2
    for line in journals[0]["lines"]:
        assert Decimal(str(line["amount"])) == Decimal("40")
        assert Decimal(str(line["baseAmount"])) == Decimal("40")
        assert line["currencyCode"] == "CZK" and line["baseCurrencyCode"] == "CZK"
        assert line["subAccountId"] == (payer if line["side"] == "DEBIT" else payee)
    assert {line["side"] for line in journals[0]["lines"]} == {"DEBIT", "CREDIT"}
    for service, action, reason in [
        ("balance", "balance.hold", "service-settlement-balance-cover"),
        ("ledger", "ledger.create", "service-settlement-ledger-post"),
    ]:
        service_log = (OUT / f"{service}.log").read_text()
        expected = f"outcome=allow action={action} principal=service-account-openbank-settlement reason={reason}"
        assert expected in service_log, f"Missing authorization evidence for {service}: {expected}"
    assert balances_correct(), "Duplicate request changed balances"

    if args.crash_worker_after_ledger_commit:
        def recovered_crash_history() -> dict[str, Any] | None:
            history = json.loads(run("docker", "exec", temporal, "temporal", "workflow", "show",
                "--workflow-id", f"settlement-{settlement_id}", "--namespace", "openbank-settlement",
                "--address", "127.0.0.1:7233", "--output", "json", "--command-timeout", "10s"))
            return history if history["events"][-1]["eventType"] == "EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED" else None

        history = until(recovered_crash_history, "same-run recovery after process crash", 30)
        assert history["events"][0]["workflowExecutionStartedEventAttributes"]["originalExecutionRunId"] == crash_evidence["originalRunId"]
        bookings = [e for e in history["events"] if e.get("activityTaskScheduledEventAttributes", {}).get("activityType", {}).get("name") == "BookToLedger"]
        assert len(bookings) == 1
        assert bookings[0]["activityTaskScheduledEventAttributes"]["startToCloseTimeout"] == "60s"
        attempts = [e["activityTaskStartedEventAttributes"]["attempt"] for e in history["events"]
                    if e.get("activityTaskStartedEventAttributes", {}).get("scheduledEventId") == bookings[0]["eventId"]]
        assert attempts == [2], attempts
        crash_evidence["completedAttempt"] = 2
        (OUT / "workflow-recovered-after-crash.json").write_text(json.dumps(history, indent=2))
    fault_evidence = response_loss_proxy.evidence(journals[0]["id"]) if response_loss_proxy else None
    recovery_evidence = None
    if args.recover_after_loss:
        workflow_id = f"settlement-{settlement_id}"

        def temporal_command(*command: str) -> dict[str, Any]:
            return json.loads(run(
                "docker", "exec", temporal, "temporal", "workflow", *command,
                "--workflow-id", workflow_id, "--namespace", "openbank-settlement",
                "--address", "127.0.0.1:7233", "--output", "json", "--command-timeout", "10s",
            ))

        observed_run: str | None = None

        def completed_history() -> dict[str, Any] | None:
            history = temporal_command("show", "--run-id", observed_run) if observed_run else temporal_command("show")
            events = history["events"]
            return history if events[-1]["eventType"] == "EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED" else None

        history = until(completed_history, "original workflow completion", 30)
        (OUT / "workflow-before-reset.json").write_text(json.dumps(history, indent=2))
        bookings = [
            event["activityTaskScheduledEventAttributes"] for event in history["events"]
            if event.get("activityTaskScheduledEventAttributes", {}).get("activityType", {}).get("name") == "BookToLedger"
        ]
        assert len(bookings) == 1, "Expected exactly one journal activity in the original history"
        reset_event = str(bookings[0]["workflowTaskCompletedEventId"])
        original_run = history["events"][0]["workflowExecutionStartedEventAttributes"]["originalExecutionRunId"]
        reset = temporal_command(
            "reset", "--run-id", original_run, "--event-id", reset_event,
            "--reason", "Isolated E2E: recover confirmed journal after five lost replies",
        )
        assert reset["runId"] != original_run, "Reset did not create a new execution"
        observed_run = reset["runId"]
        (OUT / "workflow-reset.json").write_text(json.dumps(reset, indent=2))
        until(
            lambda: sql("settlement", f"SELECT status FROM settlements WHERE id='{settlement_id}'") == "BOOKED",
            "reset workflow booked", 60,
        )
        recovered_history = until(completed_history, "recovered workflow completion", 30)
        (OUT / "workflow-after-reset.json").write_text(json.dumps(recovered_history, indent=2))
        recovered_journals = request(
            f"http://127.0.0.1:{service_ports['ledger']}/api/v1/journals/transaction/{settlement_id}", token=token,
        )
        assert len(recovered_journals) == 1 and recovered_journals[0]["id"] == journals[0]["id"]
        recovered_balances = balances_correct()
        assert recovered_balances, "Workflow recovery changed customer balances"
        assert [b["version"] for b in recovered_balances] == [b["version"] for b in final_balances]
        assert sql(
            "settlement",
            "SELECT count(*) FROM settlement_outbox "
            f"WHERE aggregate_id='{settlement_id}' "
            "AND payload::jsonb->>'previousStatus'='LEDGER_STATE_UNKNOWN' "
            "AND payload::jsonb->>'status'='BOOKED'",
        ) == "1", "Missing or duplicate durable recovery audit fact"
        assert response_loss_proxy is not None
        with response_loss_proxy.lock:
            assert response_loss_proxy.dropped_responses == 5
            assert response_loss_proxy.successful_resource_ids == [journals[0]["id"]] * 6
        recovery_evidence = {
            "status": "BOOKED", "resetEventId": reset_event, "successfulUpstreamPosts": 6,
            "originalRunId": original_run, "recoveredRunId": observed_run, "durableRecoveryFacts": 1,
        }
        print("Recovery", json.dumps(recovery_evidence), flush=True)
    audit_evidence = None
    if args.with_audit:
        expected_events = json.loads(sql(
            "settlement",
            f"SELECT json_agg(payload::jsonb) FROM settlement_outbox WHERE aggregate_id='{settlement_id}'",
        ))
        final_status = "BOOKED" if recovery_evidence else expected_status
        assert expected_events and any(event["status"] == final_status for event in expected_events)

        def audit_matches() -> list[dict[str, Any]] | None:
            stored = json.loads(sql(
                "audit",
                "SELECT coalesce(json_agg(payload::jsonb), '[]'::json) FROM audit_entries "
                f"WHERE aggregate_id='{settlement_id}' AND event_type='SETTLEMENT_STATE_CHANGED'",
            ))
            order = lambda event: event["eventId"]
            return stored if sorted(stored, key=order) == sorted(expected_events, key=order) else None

        ingested_events = until(audit_matches, "exact settlement audit ingestion", 60)
        matched_approval_events = []
        if approval_evidence:
            ids = ",".join("'" + str(uuid.UUID(item["approvalId"])) + "'" for item in approval_evidence)
            expected_approvals = json.loads(sql(
                "settlement", f"SELECT json_agg(payload::jsonb) FROM settlement_outbox WHERE aggregate_id IN ({ids})",
            ))

            def approvals_match() -> list[dict[str, Any]] | None:
                stored = json.loads(sql(
                    "audit", "SELECT coalesce(json_agg(payload::jsonb), '[]'::json) FROM audit_entries "
                    f"WHERE aggregate_id IN ({ids}) AND event_type='SETTLEMENT_OPERATOR_APPROVAL_CHANGED'",
                ))
                order = lambda event: event["eventId"]
                return stored if sorted(stored, key=order) == sorted(expected_approvals, key=order) else None

            matched_approval_events = until(approvals_match, "exact maker/checker audit ingestion", 60)
        integrity = request(f"http://127.0.0.1:{service_ports['audit']}/api/v1/audit/integrity", token=token)
        assert integrity["chainStatus"] == "INTACT" and integrity["unchainedCount"] == 0, integrity
        assert integrity["checkedCount"] >= len(ingested_events), integrity
        audit_evidence = {"matchedSettlementEvents": len(ingested_events),
                          "matchedApprovalEvents": len(matched_approval_events), "integrity": integrity}
        print("Audit", json.dumps(audit_evidence), flush=True)
    rejected_cover_evidence = None
    if args.reject_cover:
        before = balances_correct()
        assert before
        rejected_body = dict(settlement_body, idempotencyKey="no-cover-" + uuid.uuid4().hex, amount=61)
        rejected_id = originate_with_approval(rejected_body)["id"]
        until(
            lambda: sql("settlement", f"SELECT status FROM settlements WHERE id='{rejected_id}'") == "BALANCE_STATE_UNKNOWN",
            "insufficient-cover outcome", 90,
        )

        def closed_cover_history() -> dict[str, Any] | None:
            history = json.loads(run(
                "docker", "exec", temporal, "temporal", "workflow", "show",
                "--workflow-id", f"settlement-{rejected_id}", "--namespace", "openbank-settlement",
                "--address", "127.0.0.1:7233", "--output", "json", "--command-timeout", "10s",
            ))
            return history if history["events"][-1]["eventType"] == "EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED" else None

        history = until(closed_cover_history, "insufficient-cover workflow completion", 30)
        (OUT / "workflow-rejected-cover.json").write_text(json.dumps(history, indent=2))
        scheduled = [
            event["activityTaskScheduledEventAttributes"]["activityType"]["name"]
            for event in history["events"] if "activityTaskScheduledEventAttributes" in event
        ]
        assert "ReserveSettlementCover" in scheduled and "BookToLedger" not in scheduled, scheduled
        rejected_journals = request(f"{ledger_endpoint}/transaction/{rejected_id}", token=token)
        assert rejected_journals == [], rejected_journals
        assert sql("balance", f"SELECT count(*) FROM balance_holds WHERE reference_id='{rejected_id}'") == "0"
        assert originate_with_approval(rejected_body)["id"] == rejected_id
        after = balances_correct()
        assert after and [b["version"] for b in after] == [b["version"] for b in before]
        assert sql(
            "settlement", "SELECT count(*) FROM settlement_outbox "
            f"WHERE aggregate_id='{rejected_id}' AND payload::jsonb->>'status'='BALANCE_STATE_UNKNOWN'",
        ) == "1"
        rejected_cover_evidence = {
            "settlementId": rejected_id, "status": "BALANCE_STATE_UNKNOWN",
            "journals": rejected_journals, "holdCount": 0, "scheduledActivities": scheduled,
            "unchangedBalanceVersions": [b["version"] for b in after],
        }
        print("Rejected cover", json.dumps(rejected_cover_evidence), flush=True)
    print("Fault injection", json.dumps(fault_evidence), flush=True)
    print("Journals response", json.dumps(journals), flush=True)
    print("Balances", json.dumps(final_balances), flush=True)
    (OUT / "result.json").write_text(
        json.dumps(
            {
                "status": "PASS",
                "settlementId": settlement_id,
                "balances": final_balances,
                "journals": journals,
                "source": source_metadata,
                "responseLoss": fault_evidence,
                "rejectedCover": rejected_cover_evidence,
                "workerCrash": crash_evidence,
                "settlementStatus": "BOOKED" if recovery_evidence else expected_status,
                "recovery": recovery_evidence,
                "audit": audit_evidence,
                "operatorApprovals": approval_evidence,
            },
            indent=2,
        )
    )
    print("PASS real service flow", flush=True)


def cleanup() -> None:
    if response_loss_proxy:
        response_loss_proxy.close()
    for process in reversed(processes):
        if process.poll() is None:
            process.terminate()
    for process in processes:
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
    for log in process_logs:
        log.close()
    for container_name in reversed(containers):
        try:
            logs = run("docker", "logs", container_name, timeout=10)
            (OUT / f"{container_name}.log").write_text(logs)
        except Exception as error:
            print(f"Could not save logs for {container_name}: {error}", flush=True)
        try:
            run("docker", "rm", "-f", container_name, timeout=20)
        except Exception as error:
            print(f"Could not remove owned container {container_name}: {error}", flush=True)
    print(f"Evidence retained at {OUT}", flush=True)


if __name__ == "__main__":
    try:
        main()
    finally:
        cleanup()
