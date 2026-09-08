// SPDX-License-Identifier: Apache-2.0
// k6 read baseline for openbank-account-service.
//
// Account reads are on the hot path of every payment initiation (debtor lookup) and of
// the whole customer app. There was no load evidence behind their SLO.
//
// READ-ONLY BY CONSTRUCTION: every request below is a GET. No openbank-account-service state is created,
// mutated or deleted, so this lane is safe against any booted stack, including a shared
// sandbox. Write-path benchmarking for money-path services stays in the manual, isolated
// perf/k6/money-path-write-benchmark.js lane.
//
// Discovered as an artifact by openbank-admin-ui/scripts/collect-test-intelligence.mjs
// (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`.
//
// Run:
//   BASE_URL=http://localhost:8103 PERF_READ_TOKEN=<token> \
////     PERF_PARTY_ID=<uuid> PERF_ACCOUNT_ID=<uuid> \
//     k6 run openbank-account-service/src/test/k6/account-read-baseline.js
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

const BASE_URL = __ENV.BASE_URL || "http://localhost:8103";
// PERF_READ_TOKEN is injected by the scheduled workflow from an environment secret.
// Deliberately no default; never logged, never used as a k6 tag.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };
const PARTY_ID = __ENV.PERF_PARTY_ID || "00000000-0000-0000-0000-000000000001";
const ACCOUNT_ID = __ENV.PERF_ACCOUNT_ID || "00000000-0000-0000-0000-000000000002";

const listMs = new Trend("account_list_ms", true);
const detailMs = new Trend("account_detail_ms", true);
const balanceMs = new Trend("account_balance_ms", true);
const searchMs = new Trend("account_search_ms", true);

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
    "account_list_ms": ["p(95)<800"],
    "account_detail_ms": ["p(95)<400"],
    "account_balance_ms": ["p(95)<400"],
    // Trigram IBAN search is the expensive read; a looser tripwire on purpose.
    "account_search_ms": ["p(95)<1200"],

    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  const l = http.get(`${BASE_URL}/api/v1/accounts?partyId=${PARTY_ID}&limit=20`, {
    headers: readHeaders,
    tags: { name: "account_list" },
  });
  listMs.add(l.timings.duration);
  check(l, { "account list 200": (res) => res.status === 200 });

  const d = http.get(`${BASE_URL}/api/v1/accounts/${ACCOUNT_ID}`, {
    headers: readHeaders,
    tags: { name: "account_detail" },
  });
  detailMs.add(d.timings.duration);
  check(d, { "account detail 200": (res) => res.status === 200 });

  const b = http.get(`${BASE_URL}/api/v1/accounts/${ACCOUNT_ID}/balance`, {
    headers: readHeaders,
    tags: { name: "account_balance" },
  });
  balanceMs.add(b.timings.duration);
  check(b, { "account balance 200": (res) => res.status === 200 });

  const s = http.get(`${BASE_URL}/api/v1/accounts/search?q=CZ&limit=20`, {
    headers: readHeaders,
    tags: { name: "account_search" },
  });
  searchMs.add(s.timings.duration);
  check(s, { "account search 200": (res) => res.status === 200 });
}
