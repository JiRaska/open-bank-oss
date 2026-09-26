#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run a localhost-only, three-service settlement-to-ledger-to-balance proof."""

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
response_loss_proxy: LedgerResponseLossProxy | None = None


class LedgerResponseLossProxy:
    """Forward real journal posts, losing a bounded number of successful replies after commit."""

    def __init__(self, ledger_port: int, drop_count: int):
        self.drop_count = drop_count
        self.lock = threading.Lock()
        self.successful_journal_ids: list[str] = []
        self.dropped_responses = 0
        proxy = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self) -> None:
                if self.path != "/api/v1/journals":
                    self.send_error(404)
                    return
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                upstream = http.client.HTTPConnection("127.0.0.1", ledger_port, timeout=REQUEST_TIMEOUT_SECONDS)
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
                        journal = json.loads(payload)
                        if journal.get("status") != "POSTED":
                            raise ValueError("Fault injection requires a confirmed POSTED journal")
                        with proxy.lock:
                            proxy.successful_journal_ids.append(journal["id"])
                            if proxy.dropped_responses < proxy.drop_count:
                                proxy.dropped_responses += 1
                                drop = True
                    if drop:
                        # No synthetic ledger success: the real upstream has returned POSTED.
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

    def evidence(self, journal_id: str) -> dict[str, Any]:
        with self.lock:
            assert self.dropped_responses == self.drop_count, "Expected committed ledger replies were not lost"
            expected_posts = 2 if self.drop_count == 1 else 5
            assert len(self.successful_journal_ids) == expected_posts, "Unexpected settlement retry count"
            assert set(self.successful_journal_ids) == {journal_id}, "Retry created a different journal"
            return {
                "droppedResponses": self.dropped_responses,
                "successfulUpstreamPosts": len(self.successful_journal_ids),
                "journalId": journal_id,
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
    token: str | None = None,
    *,
    form: bool = False,
) -> Any:
    headers: dict[str, str] = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
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
            urllib.request.Request(url, data=payload, headers=headers),
            timeout=REQUEST_TIMEOUT_SECONDS,
        ) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        detail = error.read().decode(errors="replace")[:1200]
        raise RuntimeError(f"HTTP {error.code} {url}: {detail}") from None


def expect_http_status(
    url: str,
    expected_status: int,
    body: dict[str, Any] | None = None,
    token: str | None = None,
) -> None:
    try:
        request(url, body, token)
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


def create_realm(secret: str, password: str) -> Path:
    realm = {
        "realm": "settlement-proof",
        "enabled": True,
        "roles": {"realm": [{"name": role} for role in ["ROLE_OPERATOR", "ROLE_API"]]},
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
                "realmRoles": ["ROLE_OPERATOR"],
                "credentials": [{"type": "password", "value": password, "temporary": False}],
            }
        ],
    }
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
    args = parser.parse_args()
    global postgres

    java = java_executable()
    service_jars = {
        name: ROOT / f"openbank-{name}-service/build/quarkus-app/quarkus-run.jar"
        for name in ["ledger", "balance", "settlement"]
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
    realm_file = create_realm(secret, password)
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
    for database in ["ledger", "balance", "settlement"]:
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
    for topic in [
        "openbank.ledger.journal.posted",
        "openbank.account.events",
        "openbank.balance.events",
        "openbank.settlement.events",
        "openbank.dlq.balance.ledger-events-in",
        "openbank.dlq.balance.balance-init-in",
    ]:
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
    token = request(
        f"{issuer}/protocol/openid-connect/token",
        {
            "grant_type": "password",
            "client_id": "proof-browser",
            "username": "proof-operator",
            "password": password,
        },
        form=True,
    )["access_token"]
    print("Local infrastructure ready; synthetic OIDC operator token issued", flush=True)

    service_ports = {name: port() for name in ["ledger", "balance", "settlement"]}
    ledger_client_port = service_ports["ledger"]
    if args.drop_ledger_response:
        response_loss_proxy = LedgerResponseLossProxy(ledger_client_port, args.drop_ledger_response)
        ledger_client_port = response_loss_proxy.port
    opa_ports = {
        name: start_opa(name, component)
        for name, component in [("ledger", "ledger"), ("balance", "balances"), ("settlement", "payments")]
    }
    for name in ["ledger", "balance", "settlement"]:
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
                "OPA_URL": f"http://127.0.0.1:{opa_ports[name]}",
                "OPA_TIMEOUT_MS": "5000",
                "OPENBANK_TEMPORAL_SERVER_URL": f"127.0.0.1:{temporal_port}",
                "OPENBANK_TEMPORAL_NAMESPACE": "openbank-settlement",
                "BALANCE_SERVICE_URL": f"http://127.0.0.1:{service_ports['balance']}",
                "LEDGER_SERVICE_URL": f"http://127.0.0.1:{ledger_client_port}",
                "SETTLEMENT_LEDGER_PROJECTION_ENABLED": "true",
                "QUARKUS_OTEL_SDK_DISABLED": "true",
            }
        )
        properties = ["-Dmp.messaging.incoming.ledger-events-in.auto.offset.reset=earliest"] if name == "balance" else []
        start_process(
            [java, "-Xmx384m", *properties, "-jar", str(service_jars[name])],
            name,
            env,
        )
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
    created = request(settlement_endpoint, settlement_body, token)
    settlement_id = created["id"]
    print(f"Originated settlement {settlement_id}", flush=True)
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

    duplicate = request(settlement_endpoint, settlement_body, token)
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

    fault_evidence = response_loss_proxy.evidence(journals[0]["id"]) if response_loss_proxy else None
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
                "settlementStatus": expected_status,
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
