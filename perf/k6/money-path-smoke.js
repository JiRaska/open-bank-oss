// SPDX-License-Identifier: Apache-2.0
// k6 money-path read baseline (SSDLC-audit follow-up 2026-07-06).
//
// A LOAD BASELINE, not a stress test: the platform advertises SLOs (Pyrra) but had no
// load evidence behind them. This exercises the read side of the two core money-path
// services (ledger journals, transaction list) plus the libs-served /api/v1/info, and
// records latency percentiles so a regression shows up as a trend.
//
// Read-only on purpose: no journal is posted, no transaction created — a perf lane must
// never mutate a shared money-path ledger. Authenticated write baselines are a separate,
// carefully-scoped follow-up.
//
// Run against a docker-compose or sandbox stack:
//   LEDGER_URL=http://localhost:8101 TXN_URL=http://localhost:8102 k6 run money-path-smoke.js
//
// Thresholds are ADVISORY (the CI lane never fails a deploy on them; issue #334) — they
// are the tripwire values to investigate, calibrated from the first green baseline run.
import http from "k6/http";
import { check } from "k6";
import { Trend } from "k6/metrics";

// A 401 proves only that the identity boundary rejected the request. It does NOT prove
// that either money-path handler, database projection, or read query ran, so it must never
// become a latency baseline. A runner without a dedicated read-only identity will therefore
// emit a failed evidence envelope instead of a green-looking measurement of the rejection.
http.setResponseCallback(http.expectedStatuses(200));

const LEDGER_URL = __ENV.LEDGER_URL || "http://localhost:8101";
const TXN_URL = __ENV.TXN_URL || "http://localhost:8102";

const ledgerLatency = new Trend("ledger_journals_ms", true);
const txnLatency = new Trend("txn_list_ms", true);
const infoLatency = new Trend("info_ms", true);

export const options = {
  scenarios: {
    // Gentle, steady read load — a baseline, not a soak or a spike.
    steady_reads: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "30s", target: 10 },
        { duration: "1m", target: 10 },
        { duration: "30s", target: 0 },
      ],
      gracefulStop: "10s",
    },
  },
  thresholds: {
    // Advisory tripwires (p95). Recalibrate from the first stable baseline; the CI
    // lane runs with --no-thresholds-fail so a breach reports without failing the run.
    "ledger_journals_ms": ["p(95)<800"],
    "txn_list_ms": ["p(95)<800"],
    "info_ms": ["p(95)<200"],
    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    // The workflow keeps thresholds advisory; Test Intelligence retains the breach as failed
    // evidence until a least-privilege runner identity is configured.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  // Ledger: list journal entries (cursor-paginated read).
  const j = http.get(`${LEDGER_URL}/api/v1/journals?limit=20`, {
    tags: { name: "ledger_journals" },
  });
  ledgerLatency.add(j.timings.duration);
  check(j, { "ledger journals 200": (r) => r.status === 200 });

  // Transaction: list (read side of the money path).
  const t = http.get(`${TXN_URL}/api/v1/transactions?limit=20`, {
    tags: { name: "txn_list" },
  });
  txnLatency.add(t.timings.duration);
  check(t, { "txn list 200": (r) => r.status === 200 });

  // libs-served service-info (unauthenticated; cheap liveness+version surface).
  const i = http.get(`${LEDGER_URL}/api/v1/info`, { tags: { name: "info" } });
  infoLatency.add(i.timings.duration);
  check(i, { "info 200": (r) => r.status === 200 });
}
