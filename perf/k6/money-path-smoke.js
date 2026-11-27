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
// Set LEDGER_URL, TXN_URL, PERF_READ_TOKEN and the seeded PERF_ACCOUNT_ID.
import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

// A 401 proves only that the identity boundary rejected the request. It does NOT prove
// that either money-path handler, database projection, or read query ran, so it must never
// become a latency baseline. A runner without a dedicated read-only identity will therefore
// emit a failed evidence envelope instead of a green-looking measurement of the rejection.
http.setResponseCallback(http.expectedStatuses(200));

const LEDGER_URL = __ENV.LEDGER_URL || "http://localhost:8101";
const TXN_URL = __ENV.TXN_URL || "http://localhost:8102";
// PERF_READ_TOKEN is injected only by the scheduled workflow from an environment secret.
// It is deliberately never given a default and is never logged or added to k6 tags.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };
const accountId = __ENV.PERF_ACCOUNT_ID || "";
const ledgerPath = `${LEDGER_URL}/api/v1/journals?limit=20`;
const txnPath = `${TXN_URL}/api/v1/transactions?limit=20&accountId=${encodeURIComponent(accountId)}`;
const infoPath = `${LEDGER_URL}/api/v1/info`;
const preflight = new Rate("money_path_preflight");

const ledgerLatency = new Trend("ledger_journals_ms", true);
const txnLatency = new Trend("txn_list_ms", true);
const infoLatency = new Trend("info_ms", true);

export const options = {
  systemTags: ["status", "method", "name", "check", "scenario", "expected_response"],
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
    "money_path_preflight": [{ threshold: "rate==1.0", abortOnFail: true }],
    "ledger_journals_ms": ["p(95)<800"],
    "txn_list_ms": ["p(95)<800"],
    "info_ms": ["p(95)<200"],
    "http_req_failed": ["rate<0.01"],
    // Every sampled route must return its expected status.
    "checks": ["rate==1.0"],
  },
};

function read(path, name, headers = readHeaders) {
  return http.get(path, { headers, redirects: 0, tags: { name } });
}

function hasSeededRows(response) {
  if (response.status !== 200) return false;
  try {
    const rows = response.json("data");
    return Array.isArray(rows) && rows.length > 0;
  } catch {
    return false;
  }
}

export function setup() {
  const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
  if (!uuid.test(accountId) || !__ENV.PERF_READ_TOKEN) {
    preflight.add(false);
    console.error("Money-path preflight requires a synthetic account UUID and read token.");
    return false;
  }

  const ready = [
    ["ledger_journals", hasSeededRows(read(ledgerPath, "ledger_journals"))],
    ["txn_list", hasSeededRows(read(txnPath, "txn_list"))],
    ["info", read(infoPath, "info", {}).status === 200],
  ];
  for (const [route, ok] of ready) {
    preflight.add(ok);
    if (!ok) console.error(`Money-path preflight failed for ${route}.`);
  }
  return ready.every(([, ok]) => ok);
}

export default function (ready) {
  if (!ready) {
    sleep(1);
    return;
  }

  const j = read(ledgerPath, "ledger_journals");
  ledgerLatency.add(j.timings.duration);
  check(j, { "ledger journals 200": (r) => r.status === 200 });

  const t = read(txnPath, "txn_list");
  txnLatency.add(t.timings.duration);
  check(t, { "txn list 200": (r) => r.status === 200 });

  const i = read(infoPath, "info", {});
  infoLatency.add(i.timings.duration);
  check(i, { "info 200": (r) => r.status === 200 });
  sleep(1);
}
