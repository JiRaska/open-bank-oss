// SPDX-License-Identifier: Apache-2.0
// k6 read baseline for openbank-transaction-service.
//
// Transaction history is the single most-requested read in the customer app, and the
// search endpoint is the one with a non-trivial query plan behind it.
//
// READ-ONLY BY CONSTRUCTION: every request below is a GET. No openbank-transaction-service state is created,
// mutated or deleted, so this lane is safe against any booted stack, including a shared
// sandbox. Write-path benchmarking for money-path services stays in the manual, isolated
// perf/k6/money-path-write-benchmark.js lane.
//
// Discovered as an artifact by openbank-admin-ui/scripts/collect-test-intelligence.mjs
// (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`.
//
// Run:
//   BASE_URL=http://localhost:8102 PERF_READ_TOKEN=<token> \
////     PERF_ACCOUNT_ID=<uuid> \
//     k6 run openbank-transaction-service/src/test/k6/transaction-read-baseline.js
//
// Thresholds are ADVISORY in the CI lane (ADR-0243 / issue #334): the run records the
// breach, Test Intelligence retains it as failed evidence. They are tripwires to
// investigate, calibrated to be generous until the first stable baseline exists.
import http from "k6/http";
import { check } from "k6";
import { Trend } from "k6/metrics";

// A 401 is rejection evidence, not a handler baseline: a run without a valid read identity
// must fail loudly rather than publish the latency of an auth rejection.
http.setResponseCallback(http.expectedStatuses(200));

const BASE_URL = __ENV.BASE_URL || "http://localhost:8102";
// PERF_READ_TOKEN is injected by the scheduled workflow from an environment secret.
// Deliberately no default; never logged, never used as a k6 tag.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };
const ACCOUNT_ID = __ENV.PERF_ACCOUNT_ID || "00000000-0000-0000-0000-000000000002";

const listMs = new Trend("txn_list_ms", true);
const searchMs = new Trend("txn_search_ms", true);

export const options = {
  scenarios: {
    // A steady read baseline, not a soak or a spike.
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
    "txn_list_ms": ["p(95)<800"],
    "txn_search_ms": ["p(95)<1500"],

    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  const l = http.get(`${BASE_URL}/api/v1/transactions?accountId=${ACCOUNT_ID}&limit=20`, {
    headers: readHeaders,
    tags: { name: "txn_list" },
  });
  listMs.add(l.timings.duration);
  check(l, { "transaction list 200": (res) => res.status === 200 });

  const s = http.get(`${BASE_URL}/api/v1/transactions/search?accountId=${ACCOUNT_ID}&limit=20`, {
    headers: readHeaders,
    tags: { name: "txn_search" },
  });
  searchMs.add(s.timings.duration);
  check(s, { "transaction search 200": (res) => res.status === 200 });
}
