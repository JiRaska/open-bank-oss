#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Local-only, three fast-jar E2E: real PostgreSQL, OIDC signatures, OPA and mTLS.

All identities, certificates, credentials and source facts are generated fixtures. No production
endpoint or credential is accepted. Build both fast jars before running; see README.md.
"""
from __future__ import annotations

import argparse
import base64
import datetime as dt
from decimal import Decimal
import http.server
import json
import os
from pathlib import Path
import re
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[2]
REVISION = "00000000-0000-0000-0000-000000000002"
COMPANY = "00000000-0000-0000-0000-000000000201"


def run(args, *, data=None, env=None):
    result = subprocess.run([str(x) for x in args], input=data, capture_output=True, env=env)
    if result.returncode:
        raise RuntimeError(f"fixture command {Path(str(args[0])).name} failed: {result.stderr.decode()[-1500:]}")
    return result.stdout.decode().strip()


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


class Fixture:
    def __init__(self, directory):
        self.dir = Path(directory)
        self.processes = []
        self.container = None
        self.http = None
        self.logs = []
        self.passed = []
        self.db_password = uuid.uuid4().hex
        self.client_secret = uuid.uuid4().hex
        self.signing_key = self.dir / "issuer.key"
        run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", self.signing_key])
        modulus = run(["openssl", "rsa", "-in", self.signing_key, "-noout", "-modulus"]).split("=", 1)[1]
        self.jwk = {"kty": "RSA", "kid": "local-e2e", "alg": "RS256", "use": "sig",
                    "n": b64(bytes.fromhex(modulus)), "e": "AQAB"}
        self.create_tls()
        self.context = ssl.create_default_context(cafile=str(self.dir / "ca.crt"))
        self.context.minimum_version = ssl.TLSVersion.TLSv1_3
        self.context.set_alpn_protocols(["http/1.1"])
        self.context.load_cert_chain(str(self.dir / "client.crt"), str(self.dir / "client.key"))
        self.start_issuer()

    def create_tls(self):
        ca_config = self.dir / "ca.cnf"
        ca_config.write_text("[req]\ndistinguished_name=dn\nx509_extensions=v3_ca\nprompt=no\n"
                             "[dn]\nCN=local-accounting-e2e\n[v3_ca]\nbasicConstraints=critical,CA:TRUE,pathlen:0\n"
                             "keyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\n"
                             "authorityKeyIdentifier=keyid:always,issuer\n")
        run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
             "-config", ca_config, "-keyout", self.dir / "ca.key", "-out", self.dir / "ca.crt"])
        for name, usage in [("server", "serverAuth"), ("client", "clientAuth")]:
            run(["openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", f"/CN=local-{name}",
                 "-keyout", self.dir / f"{name}.key", "-out", self.dir / f"{name}.csr"])
            extension = self.dir / f"{name}.ext"
            extension.write_text("basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
                                 "subjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n"
                                 f"extendedKeyUsage={usage}\nsubjectAltName=IP:127.0.0.1,DNS:localhost\n")
            run(["openssl", "x509", "-req", "-in", self.dir / f"{name}.csr", "-CA", self.dir / "ca.crt",
                 "-CAkey", self.dir / "ca.key", "-CAcreateserial", "-days", "1", "-extfile", extension,
                 "-out", self.dir / f"{name}.crt"])

    def token(self, name, client="openbank-admin-ui", roles=None):
        now = int(time.time())
        body = {"iss": self.issuer, "sub": str(uuid.uuid5(uuid.NAMESPACE_URL, f"local-e2e:{name}")),
                "azp": client, "preferred_username": name, "iat": now, "exp": now + 3600,
                "aud": ["openbank-services", "openbank-pension-fund"],
                "realm_access": {"roles": roles or ["ROLE_OPERATOR"]}}
        payload = ".".join(b64(json.dumps(x, separators=(",", ":")).encode()) for x in [
            {"alg": "RS256", "kid": "local-e2e", "typ": "JWT"}, body])
        signed = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(self.signing_key)],
                                input=payload.encode(), capture_output=True, check=True).stdout
        return payload + "." + b64(signed)

    def start_issuer(self):
        fixture = self
        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def answer(self, value, status=200):
                data = json.dumps(value).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                if self.path.endswith("/.well-known/openid-configuration"):
                    self.answer({"issuer": fixture.issuer, "jwks_uri": fixture.issuer + "/keys",
                                 "token_endpoint": fixture.issuer + "/token", "authorization_endpoint": fixture.issuer + "/authorize"})
                elif self.path.endswith("/keys"):
                    self.answer({"keys": [fixture.jwk]})
                elif self.path == "/api/v2/revisions/" + REVISION:
                    self.answer({"id": REVISION, "state": "PUBLISHED",
                                 "schemaRef": {"id": "org.openbank.retirement.pension-savings", "version": 2},
                                 "content": {"attributes": {"feeSchedule": {"managementFeeAnnualPercent": "0.80"}}}})
                else:
                    self.answer({"error": "unknown fixture route"}, 404)

            def do_POST(self):
                fields = urllib.parse.parse_qs(self.rfile.read(int(self.headers.get("Content-Length", "0"))).decode())
                client = fields.get("client_id", ["openbank-pension-fund"])[0]
                auth = self.headers.get("Authorization", "")
                if auth.startswith("Basic "):
                    client = base64.b64decode(auth[6:]).decode().split(":", 1)[0]
                self.answer({"access_token": fixture.token("service-account-" + client, client, ["ROLE_API"]),
                             "token_type": "Bearer", "expires_in": 3600})
        self.http = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.issuer = f"http://127.0.0.1:{self.http.server_port}/issuer"
        threading.Thread(target=self.http.serve_forever, daemon=True).start()

    def spawn(self, name, args, env=None):
        log = (self.dir / f"{name}.log").open("w")
        self.logs.append(log)
        proc = subprocess.Popen([str(x) for x in args], stdout=log, stderr=subprocess.STDOUT, cwd=ROOT, env=env)
        self.processes.append(proc)
        return proc

    def opa(self, name, extension):
        endpoint = port()
        files = [ROOT / "openbank-libs/governance/policies/rest.rego",
                 ROOT / "openbank-libs/governance/rules-opa-data.yaml"]
        if name != "fund-admin":
            files.append(ROOT / "openbank-infra/gitops/components/ledger/ledger_rest_ext.rego")
        self.spawn("opa-" + name, ["opa", "run", "--server", "--addr", f"127.0.0.1:{endpoint}", *files, ROOT / extension])
        self.until(lambda: self.raw_http(f"http://127.0.0.1:{endpoint}/health")[0] == 200, "OPA starts")
        return f"http://127.0.0.1:{endpoint}"

    def start_database(self):
        name = "openbank-accounting-e2e-" + uuid.uuid4().hex[:12]
        env = dict(os.environ, POSTGRES_PASSWORD=self.db_password)
        self.container = run(["docker", "run", "-d", "--name", name, "-e", "POSTGRES_PASSWORD",
                              "-e", "POSTGRES_USER=fixture", "-p", "127.0.0.1::5432", "postgres:18.6"], env=env)
        # The image's temporary init server accepts only Unix sockets and is then restarted.
        # Wait for TCP so database creation cannot race that deliberate shutdown.
        self.until(lambda: subprocess.run(["docker", "exec", self.container, "pg_isready", "-h", "127.0.0.1", "-U", "fixture"],
                                         capture_output=True).returncode == 0, "Postgres starts")
        self.db_port = int(run(["docker", "port", self.container, "5432/tcp"]).rsplit(":", 1)[1])
        for db in ["fund_admin", "fund_book", "company_book"]:
            self.sql("postgres", f"CREATE DATABASE {db}")

    def sql(self, db, sql):
        return run(["docker", "exec", "-i", self.container, "psql", "-U", "fixture", "-d", db,
                    "-v", "ON_ERROR_STOP=1", "-A", "-t", "-c", sql])

    def application(self, name, module, database, opa, extra):
        https = port()
        props = {
            "quarkus.http.host": "127.0.0.1", "quarkus.http.port": str(port()),
            "quarkus.http.ssl-port": str(https), "quarkus.management.port": str(port()),
            "quarkus.http.ssl.certificate.files": str(self.dir / "server.crt"),
            "quarkus.http.ssl.certificate.key-files": str(self.dir / "server.key"),
            "quarkus.http.ssl.certificate.trust-store-files": str(self.dir / "ca.crt"),
            "quarkus.http.insecure-requests": "disabled", "quarkus.http.ssl.protocols": "TLSv1.3",
            "quarkus.datasource.jdbc.url": f"jdbc:postgresql://127.0.0.1:{self.db_port}/{database}",
            "quarkus.datasource.reactive.url": f"vertx-reactive:postgresql://127.0.0.1:{self.db_port}/{database}",
            "quarkus.datasource.username": "fixture", "quarkus.datasource.password": self.db_password,
            "quarkus.oidc.auth-server-url": self.issuer, "quarkus.oidc.roles.role-claim-path": "realm_access/roles",
            "quarkus.oidc-client.auth-server-url": self.issuer,
            "quarkus.oidc-client.credentials.secret": self.client_secret,
            "opa.url": opa, "authz.enforce": "true", "quarkus.otel.sdk.disabled": "true",
            "quarkus.log.console.json.enabled": "false", "openbank.outbox.dispatch-enabled": "false",
        }
        for tls in ["catalog-authority", "pension-books"]:
            prefix = f"quarkus.tls.{tls}."
            props.update({prefix + "key-store.pem.client.cert": str(self.dir / "client.crt"),
                          prefix + "key-store.pem.client.key": str(self.dir / "client.key"),
                          prefix + "trust-store.pem.certs": str(self.dir / "ca.crt"), prefix + "protocols": "TLSv1.3"})
        props.update(extra)
        config = self.dir / f"{name}.properties"
        config.write_text("\n".join(f"{key}={value}" for key, value in props.items()) + "\n")
        jar = ROOT / module / "build/quarkus-app/quarkus-run.jar"
        if not jar.is_file():
            raise RuntimeError(f"Build {module}:quarkusBuild first")
        env = dict(os.environ, LEDGER_EVENTS_OUT_ENABLED="false")
        java = Path(os.environ["JAVA_HOME"]) / "bin/java" if os.environ.get("JAVA_HOME") else Path(shutil.which("java"))
        proc = self.spawn(name, [java, "-Xmx512m", f"-Dquarkus.config.locations={config.as_uri()}", "-jar", jar], env)
        return proc, f"https://127.0.0.1:{props['quarkus.http.ssl-port']}"

    def raw_http(self, url, method="GET", body=None, token=None):
        headers = {"Content-Type": "application/json", "Idempotency-Key": str(uuid.uuid4())}
        if method == "POST" and url.endswith("/management-fees"):
            headers["Idempotency-Key"] = str(body["navId"])
        elif method == "POST" and url.endswith("/approve"):
            headers["Idempotency-Key"] = url.split("/")[-2]
        if token:
            headers["Authorization"] = "Bearer " + token
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, context=self.context, timeout=8) as response:
                payload = response.read()
                return response.status, json.loads(payload) if payload else None
        except urllib.error.HTTPError as error:
            payload = error.read()
            try:
                value = json.loads(payload) if payload else None
            except ValueError:
                value = {"error": "non-JSON error"}
            return error.code, value

    def ready(self, base, path, token):
        status, value = self.raw_http(base + path, token=token)
        if status != 200:
            raise AssertionError(f"startup status {status}: {value}")
        return True

    def call(self, base, path, method="GET", body=None, *, token=None, status=200):
        actual, value = self.raw_http(base + path, method, body, token or self.maker)
        if actual != status:
            raise AssertionError(f"{method} {path}: expected {status}, received {actual}: {value}")
        return value

    def until(self, predicate, description, seconds=90):
        deadline = time.monotonic() + seconds
        last_failure = "predicate remained false"
        while time.monotonic() < deadline:
            try:
                if predicate():
                    return
            except (OSError, urllib.error.URLError, AssertionError) as error:
                last_failure = str(error)
            time.sleep(0.3)
        raise AssertionError(description + " timed out: " + last_failure)

    def check(self, name, condition):
        if not condition:
            raise AssertionError(name)
        self.passed.append(name)
        print("PASS " + name, flush=True)

    def stop(self, proc):
        proc.terminate()
        try:
            proc.wait(8)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()

    def close(self):
        for proc in reversed(self.processes):
            if proc.poll() is None:
                self.stop(proc)
        if self.http:
            self.http.shutdown()
        if self.container:
            subprocess.run(["docker", "rm", "-f", self.container], capture_output=True)
        for log in self.logs:
            log.close()

    def scenario(self):
        self.start_database()
        self.maker = self.token("fixture-accountant-maker")
        checker = self.token("fixture-accountant-checker")
        source = self.token("service-account-openbank-pension-fund", "openbank-pension-fund", ["ROLE_API"])
        admin_opa = self.opa("fund-admin", "openbank-infra/gitops/components/pension-fund/pension_fund_rest_ext.rego")
        admin_props = {"openbank.pension-fund.accounting.delivery-enabled": "false",
                       "quarkus.rest-client.product-catalog.url": self.issuer.rsplit("/", 1)[0]}
        admin_proc, admin = self.application("fund-admin-initial", "openbank-pension-fund-service", "fund_admin", admin_opa, admin_props)
        self.until(lambda: self.ready(admin, "/api/v2/funds", self.maker), "Fund service starts")
        fund = self.call(admin, "/api/v2/funds", "POST", {
            "name": "Fixture segregated fund", "isin": "CZ0008474053", "lei": "315700ABCDEF12345678",
            "depositaryReference": "fixture-depositary", "custodyAccountReference": "fixture-custody",
            "currency": "CZK", "riskClass": 3, "mandatoryConservative": False,
            "productRevisionId": REVISION, "launchNavPerUnit": 1}, status=201)["id"]
        self.stop(admin_proc)
        fund_opa = self.opa("fund-book", "openbank-infra/gitops/components/ledger/pension-fund-policy/ledger_pension_fund_rest_ext.rego")
        company_opa = self.opa("company-book", "openbank-infra/gitops/components/ledger-pension-co/ledger_pension_co_rest_ext.rego")
        common = {"quarkus.scheduler.enabled": "false", "openbank.ledger.day-lock.mode": "enforce",
                  "openbank.ledger.book.close.participants-mode": "enforce",
                  "openbank.ledger.book.close.participants.pension-fund.principal": "service-account-openbank-pension-fund",
                  "openbank.synthetic.trusted-principals": "service-account-openbank-pension-fund"}
        _, fund_book = self.application("fund-book", "openbank-ledger-service", "fund_book", fund_opa, common | {
            "openbank.ledger.book.kind": "PENSION_FUND", "openbank.ledger.book.id": fund,
            "openbank.ledger.book.close.required-jobs.pension-fund": "nav,management-fees",
        })
        self.until(lambda: self.ready(fund_book, "/api/v1/ledger/pension-accounting/identity", source),
                   "Fund ledger starts")
        # Reserve the company listener now; its process deliberately starts AFTER the fund leg commits.
        company_https = port()
        company_book = f"https://127.0.0.1:{company_https}"
        admin_props.update({"openbank.pension-fund.accounting.delivery-enabled": "true",
                            "openbank.pension-fund.accounting.company-book-id": COMPANY,
                            "openbank.pension-fund.accounting.company-url": company_book,
                            "openbank.pension-fund.accounting.funds." + fund: fund_book,
                            "openbank.pension-fund.accounting.poll-interval": "1s",
                            "openbank.pension-fund.accounting.close-poll-interval": "1s"})
        _, admin = self.application("fund-admin-active", "openbank-pension-fund-service", "fund_admin", admin_opa, admin_props)
        self.until(lambda: self.ready(admin, "/api/v2/funds", self.maker), "Fund service restarts")
        today = dt.datetime.now(ZoneInfo("Europe/Prague")).date().isoformat()
        day_path = "/api/v1/ledger/accounting-days/" + today
        self.call(fund_book, day_path, "POST", status=201)

        def journal(description, debit, credit, amount):
            ids = {code: self.sql("fund_book", f"SELECT id FROM gl_accounts WHERE code='{code}'") for code in [debit, credit]}
            for account in ids.values():
                uuid.UUID(account)  # An absent chart must fail at the fixture boundary.
            lines = [{"glAccountId": ids[code], "side": side, "amount": amount, "currencyCode": "CZK",
                      "baseAmount": amount, "baseCurrencyCode": "CZK"} for code, side in [(debit, "DEBIT"), (credit, "CREDIT")]]
            return self.call(fund_book, "/api/v1/journals", "POST", {
                "idempotencyKey": str(uuid.uuid4()), "transactionId": str(uuid.uuid4()), "entryDate": today,
                "valueDate": today, "description": description, "createdBy": str(uuid.uuid4()), "lines": lines}, status=201)

        # Explicit fixture custody receipt and unit recognition; NAV/fee delivery infer neither cash fact.
        journal("Fixture confirmed custody receipt", "PF1110", "PF2210", 36500)
        contract = str(uuid.uuid4())
        self.call(admin, f"/api/v2/contracts/{contract}/orders", "POST", {"fundId": fund, "type": "SUBSCRIBE", "amount": 36500}, status=202)
        nav = self.call(admin, f"/api/v2/funds/{fund}/navs", "POST", {
            "valuationDate": today, "positions": [], "cash": 36500, "otherLiabilities": 0}, status=201)["id"]
        self.call(admin, f"/api/v2/navs/{nav}/approve", "POST", token=checker)
        self.until(lambda: self.sql("fund_admin", "SELECT count(*) FROM pension_fund_fee_receipts WHERE book_kind='PENSION_FUND'") == "1",
                   "Fund accounting leg commits before unavailable company")
        self.check("partial two-book delivery remains pending", self.sql("fund_admin", "SELECT status FROM pension_fund_accounting_outbox") != "SENT")
        self.check("company has no invented receipt", self.sql("fund_admin", "SELECT count(*) FROM pension_fund_fee_receipts WHERE book_kind='PENSION_COMPANY'") == "0")

        chart = (ROOT / "openbank-infra/gitops/components/ledger-pension-co/chart-of-accounts.yaml").read_text()
        callback = chart.split("  afterMigrate__pension_co_chart_of_accounts.sql: |\n", 1)[1]
        callback = "\n".join(line[4:] if line.startswith("    ") else line for line in callback.splitlines())
        company_dir = self.dir / "company-chart"
        company_dir.mkdir()
        (company_dir / "afterMigrate__pension_co_chart_of_accounts.sql").write_text(callback)
        _, actual_company_url = self.application("company-book", "openbank-ledger-service", "company_book", company_opa, common | {
            "quarkus.http.ssl-port": str(company_https), "openbank.ledger.book.kind": "PENSION_COMPANY",
            "openbank.ledger.book.id": COMPANY, "openbank.ledger.book.close.required-jobs.pension-fund": "management-fees",
            "openbank.ledger.book.chart-script": str(company_dir / "afterMigrate__pension_co_chart_of_accounts.sql")})
        self.until(lambda: self.ready(company_book, "/api/v1/ledger/pension-accounting/identity", source),
                   "Company ledger starts")
        self.call(company_book, day_path, "POST", status=201)
        self.until(lambda: self.sql("fund_admin", "SELECT status FROM pension_fund_accounting_outbox") == "SENT", "Both receipts recover")
        journal("Fixture register subscription recognition", "PF2210", "PF3010", 36500)
        def fees_once():
            for database, debit, credit in [("fund_book", "PF5110", "PF2110"),
                                            ("company_book", "PS1310", "PS6110")]:
                count = self.sql(database, f"SELECT count(*) FROM journal_entries WHERE transaction_id='{nav}'")
                if count != "1":
                    return False
                for code, expected in [(debit, Decimal("0.80")), (credit, Decimal("-0.80"))]:
                    amount = self.sql(database, "SELECT coalesce(sum(CASE WHEN l.side='D' THEN l.base_amount "
                                      "ELSE -l.base_amount END), 0) FROM journal_lines l JOIN journal_entries e "
                                      "ON e.id=l.journal_id JOIN gl_accounts g ON g.id=l.gl_account_id "
                                      f"WHERE e.transaction_id='{nav}' AND g.code='{code}'")
                    if Decimal(amount) != expected:
                        return False
                cash_legs = self.sql(database, "SELECT count(*) FROM journal_lines l JOIN journal_entries e "
                                     "ON e.id=l.journal_id JOIN gl_accounts g ON g.id=l.gl_account_id "
                                     f"WHERE e.transaction_id='{nav}' AND g.code NOT IN ('{debit}', '{credit}')")
                if cash_legs != "0":
                    return False
            return True

        self.check("exactly one balanced noncash fee journal per book after recovery", fees_once())
        self.check("company fee is a receivable, not a cash receipt", self.sql("company_book", "SELECT count(*) FROM journal_lines l JOIN gl_accounts g ON g.id=l.gl_account_id WHERE g.code='PS1110'") == "0")
        for base in [fund_book, company_book]:
            self.until(lambda base=base: self.call(base, day_path + "/participants")["complete"], "Own source completion")
            self.call(base, day_path + "/transitions/CUTOFF", "POST")
            self.call(base, day_path + "/transitions/TIED_OUT", "POST", status=409)
        self.check("source completion alone cannot close either book", True)
        expected_fund = {code: 0 for code in ["PF1110", "PF1500", "PF2110", "PF2210", "PF2220", "PF3010", "PF5110", "PF6210"]}
        expected_fund.update(PF1110=36500, PF2110=-0.80, PF3010=-36500, PF5110=0.80)
        expected_company = {code: 0 for code in set(re.findall(r"'((?:PS)[0-9]{4})'", callback))}
        expected_company.update(PS1310=0.80, PS6110=-0.80)
        for base, book_id, sources, balances in [
            (fund_book, fund, ["cash", "units", "custody", "nav", "fees", "settlements"], expected_fund),
            (company_book, COMPANY, ["bank", "receivables", "portfolio", "expenses", "tax"], expected_company),
        ]:
            proposal = self.call(base, "/api/v1/ledger/pension-accounting/reconciliations", "POST", {
                "bookId": book_id, "businessDate": today, "sourceReferences": {key: "fixture-source-" + key for key in sources},
                "expectedBalances": balances})["id"]
            approval = f"/api/v1/ledger/pension-accounting/reconciliations/{proposal}/approve"
            self.call(base, approval, "POST", status=409)
            self.call(base, approval, "POST", token=checker)
            before = self.call(base, day_path + "/participants")["participants"][0]["reportedAt"]
            time.sleep(2)
            after = self.call(base, day_path + "/participants")["participants"][0]["reportedAt"]
            label = "fund" if base == fund_book else "company"
            self.check("unchanged source receipts preserve " + label + " work watermark", before == after)
            self.call(base, day_path + "/transitions/TIED_OUT", "POST")
        self.check("separate maker-checker proof closes each own book", True)
        journal_body = {"bookId": fund, "fundId": fund, "navId": nav, "valuationDate": today, "amount": 0.80, "currency": "CZK"}
        replay = self.call(fund_book, "/api/v1/ledger/pension-accounting/management-fees", "POST", journal_body, token=source)
        self.check("closed-day retry returns original posting", replay["outcome"] == "POSTED")
        self.check("closed-day replay adds no journal or duplicate fee amount", fees_once())
        self.call(company_book, "/api/v1/ledger/pension-accounting/management-fees", "POST", journal_body, token=source, status=409)
        self.check("wrong-book routing is refused", True)
        books = [("fund", fund_book, "fund_book"), ("company", company_book, "company_book")]
        for label, base, _ in books:
            self.check("a single fixture day cannot certify the " + label + " calendar month",
                       not self.call(base, "/api/v1/ledger/close-readiness")["eligibleForEnforcement"])

        # New pending source work must revoke a prior RAN even on an already closed day,
        # including when the book already has a delivered fee and no new journal is posted.
        snapshots = {db: self.sql(db, "SELECT row_to_json(e)::text FROM ledger_close_evidence e") for _, _, db in books}
        self.call(admin, f"/api/v2/funds/{fund}/navs", "POST", {
            "valuationDate": today, "positions": [], "cash": 36600, "otherLiabilities": 0}, status=201)
        for label, base, database in books:
            self.until(lambda base=base: self.call(base, day_path + "/participants")["participants"][0]["status"] == "REFUSED",
                       label + " source revokes completion after close")
            refusal = self.call(base, day_path + "/participants")["participants"][0]
            self.check(label + " pending correction revokes completion without claiming journals", refusal["journalsPosted"] == 0)
            self.check(label + " late refusal preserves frozen close evidence",
                       self.sql(database, "SELECT row_to_json(e)::text FROM ledger_close_evidence e") == snapshots[database])
            count = self.sql(database, "SELECT count(*) FROM ledger_close_participation")
            time.sleep(2)
            self.check(label + " repeated refusal preserves its source receipt",
                       self.sql(database, "SELECT count(*) FROM ledger_close_participation") == count)
        self.check("pending valuation creates no new journal or cash movement", fees_once())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, help="Write assertion names only; no credentials or endpoints")
    args = parser.parse_args()
    if args.report:
        args.report.write_text(json.dumps({"status": "running", "passed": [], "productionEvidence": False}) + "\n")
    with tempfile.TemporaryDirectory(prefix="openbank-accounting-e2e-") as directory:
        fixture = Fixture(directory)
        try:
            fixture.scenario()
            result = {"status": "passed", "passed": fixture.passed, "productionEvidence": False}
            if args.report:
                args.report.write_text(json.dumps(result, indent=2) + "\n")
            print(f"PASS {len(fixture.passed)} assertions; generated local fixtures only", flush=True)
        except Exception:
            if args.report:
                args.report.write_text(json.dumps({"status": "failed", "passed": fixture.passed, "productionEvidence": False}) + "\n")
            for log in fixture.dir.glob("*.log"):
                print(f"--- {log.name}: last diagnostic lines ---", flush=True)
                print("\n".join(log.read_text(errors="replace").splitlines()[-18:]), flush=True)
            raise
        finally:
            fixture.close()


if __name__ == "__main__":
    main()
