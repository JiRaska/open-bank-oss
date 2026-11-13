# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

"""Run k6 against loopback HTTP fixtures for the schedule's retry and proof controls."""

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory
from threading import Thread
import unittest
from urllib.parse import parse_qs, urlsplit
import uuid


SOURCE = "00000000-0000-4000-8000-000000000012"
TARGET = "00000000-0000-4000-8000-000000000013"
JOURNEY = Path(__file__).with_name("journey.js").resolve()
# 2026-07-15T22:02Z falls on July 16 in the Prague booking calendar.
BANK_DAY = "2026-07-16"


class FixtureHandler(BaseHTTPRequestHandler):
    def reply(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        if self.path in {"/customer-token", "/ledger-token"}:
            expected = ("openbank-synthetic-transfer" if self.path == "/customer-token"
                        else "openbank-synthetic-transfer-verifier")
            self.reply(200 if parse_qs(body.decode()).get("client_id") == [expected] else 403,
                       {"access_token": expected})
            return
        if not self.customer_call() or self.path != "/customer/v1/synthetic/transfers":
            self.reply(403, {})
            return
        self.server.submits += 1
        request = json.loads(body)
        self.server.assert_only_fixture_fields = set(request) == {"runDate", "direction"}
        if request.get("runDate") != self.server.bank_day:
            self.reply(400, {})
            return
        direction = request["direction"]
        row = self.server.rows.get(direction)
        if row is None:
            source, target = (SOURCE, TARGET) if direction == "OUTBOUND" else (TARGET, SOURCE)
            row = {
                "id": str(uuid.uuid4()), "description": "Synthetic transfer canary", "type": "TRANSFER",
                "sourceAccountId": source, "targetAccountId": target, "amount": "0.01",
                "currencyCode": "CZK", "valueDate": request["runDate"], "status": "COMPLETED",
            }
            self.server.rows[direction] = row
            self.server.journal_ids[row["id"]] = str(uuid.uuid4())
            self.server.balances[source] -= 1
            self.server.balances[target] += 1
            if self.server.ambiguous and direction == "OUTBOUND":
                self.reply(503, {})
                return
        self.reply(201, {"id": row["id"]})

    def do_GET(self):
        path = urlsplit(self.path).path
        if path.startswith("/api/v1/journals/synthetic/transactions/"):
            if self.headers.get("Authorization") != "Bearer openbank-synthetic-transfer-verifier":
                self.reply(403, {})
                return
            transaction_id = path.rsplit("/", 1)[-1]
            rows = [row for row in self.server.rows.values() if row["id"] == transaction_id]
            if not rows:
                self.reply(404, {})
                return
            row = rows[0]
            self.reply(200, {
                "journalId": self.server.journal_ids[transaction_id], "transactionId": transaction_id,
                "debitAccountId": row["sourceAccountId"], "creditAccountId": row["targetAccountId"],
                "valueDate": row["valueDate"], "amountMinor": 1, "currency": "CZK", "postingCount": 1,
                "synthetic": self.server.synthetic_proof,
            })
        elif not self.customer_call():
            self.reply(403, {})
        elif path == "/customer/v1/synthetic/transfers":
            date = "2026-07-17" if self.server.cross_midnight and "OUTBOUND" in self.server.rows else self.server.bank_day
            self.reply(200, {"runDate": date})
        elif path == "/customer/v1/transactions":
            self.reply(200, {"data": list(self.server.rows.values())})
        elif path.startswith("/customer/v1/balances/"):
            account = path.rsplit("/", 1)[-1]
            self.reply(200, {"balances": [{
                "accountId": account, "currency": "CZK",
                "bookedAmount": f"{self.server.balances[account] / 100:.2f}",
            }]})
        else:
            self.reply(404, {})

    def customer_call(self):
        return (self.headers.get("Authorization") == "Bearer openbank-synthetic-transfer"
                and self.headers.get("x-openbank-synthetic") == "true")

    def log_message(self, _format, *_args):
        pass


@unittest.skipUnless(shutil.which("k6"), "k6 is required for the loopback journey")
class ScheduledTransferTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), FixtureHandler)
        self.server.ambiguous = True
        self.server.bank_day = BANK_DAY
        self.server.cross_midnight = False
        self.server.synthetic_proof = True
        self.server.rows = {}
        self.server.journal_ids = {}
        self.server.balances = {SOURCE: 100, TARGET: 0}
        self.server.submits = 0
        self.thread = Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.close_fixture)

    def close_fixture(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def run_journey(self):
        base = f"http://127.0.0.1:{self.server.server_port}"
        with TemporaryDirectory(prefix="openbank-k6-fixture-") as workdir:
            return subprocess.run(
                [shutil.which("k6"), "run", "--quiet", "--no-usage-report", str(JOURNEY)],
                cwd=workdir,
                env=dict(CUSTOMER_BASE=base, LEDGER_BASE=base,
                         CUSTOMER_TOKEN_URL=base + "/customer-token", LEDGER_TOKEN_URL=base + "/ledger-token",
                         CUSTOMER_CLIENT_SECRET="loopback-test", LEDGER_CLIENT_SECRET="loopback-test",
                         FIXTURE_SOURCE_ACCOUNT=SOURCE, FIXTURE_TARGET_ACCOUNT=TARGET,
                         FIXTURE_SOURCE_BOOKED_CZK="1.00", FIXTURE_TARGET_BOOKED_CZK="0.00"),
                capture_output=True, text=True, timeout=20, check=False,
            )

    def test_timeout_replay_and_same_day_rerun_book_only_the_two_directions(self):
        run = self.run_journey()
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertEqual(self.server.submits, 3)
        self.assertTrue(self.server.assert_only_fixture_fields)
        self.assertEqual(len(self.server.journal_ids), 2)
        self.assertEqual({row["valueDate"] for row in self.server.rows.values()}, {BANK_DAY})
        self.assertEqual(self.server.balances, {SOURCE: 100, TARGET: 0})

        replay = self.run_journey()
        self.assertEqual(replay.returncode, 0, replay.stdout + replay.stderr)
        self.assertEqual(self.server.submits, 3)
        self.assertEqual(len(self.server.journal_ids), 2)

    def test_non_synthetic_journal_stops_before_return(self):
        self.server.synthetic_proof = False
        run = self.run_journey()
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("fixture ledger proof differs", run.stderr)
        self.assertEqual(set(self.server.rows), {"OUTBOUND"})

    def test_bank_midnight_after_forward_reports_incomplete_compensation(self):
        self.server.ambiguous = False
        self.server.cross_midnight = True
        run = self.run_journey()
        self.assertNotEqual(run.returncode, 0)
        self.assertIn("fixture bank date changed before submission", run.stderr)
        outbound = self.server.rows["OUTBOUND"]["id"]
        self.assertIn(f"fixture compensation unproven for bank date {BANK_DAY}, outbound {outbound}", run.stderr)
        self.assertEqual(set(self.server.rows), {"OUTBOUND"})
        self.assertEqual(self.server.submits, 1)
