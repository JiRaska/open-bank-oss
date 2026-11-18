// SPDX-License-Identifier: Apache-2.0
// k6 read baseline for openbank-interest-service.
//
// Interest accrual reads back the savings back-office (per-account accrual ledgers, the
// accrual summary a product screen renders, the capitalization history); there was no
// load evidence behind their SLO. The accrue/capitalize endpoints are POSTs and stay out
// of this lane by construction — every request below is a GET.
//
// READ-ONLY BY CONSTRUCTION: every request below is a GET. No
// openbank-interest-service state is created, mutated or deleted, so this lane is safe
// against any booted stack, including a shared sandbox. Write-path benchmarking for
// money-path services stays in the manual, isolated perf/k6/money-path-write-benchmark.js
// lane.
//
// Discovered as an artifact by openbank-admin-ui/scripts/collect-test-intelligence.mjs
// (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`.
//
// Run:
//   BASE_URL=http://localhost:8125 PERF_READ_TOKEN=<token> \
//     PERF_ACCOUNT_ID=<uuid> \
//     k6 run openbank-interest-service/src/test/k6/interest-read-baseline.js
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

const BASE_URL = __ENV.BASE_URL || "http://localhost:8125";
// PERF_READ_TOKEN is injected by the scheduled workflow from an environment secret.
// Deliberately no default; never logged, never used as a k6 tag.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };
// PERF_ACCOUNT_ID is the fleet-wide fixture account; src/test/k6/fixtures.sql seeds 200
// accrual rows and one capitalization for it.
const ACCOUNT_ID = __ENV.PERF_ACCOUNT_ID || "00000000-0000-0000-0000-000000000002";

const listMs = new Trend("interest_accruals_list_ms", true);
const accountMs = new Trend("interest_accruals_account_ms", true);
const summaryMs = new Trend("interest_accruals_summary_ms", true);
const capitalizationsMs = new Trend("interest_capitalizations_ms", true);
const remittancesMs = new Trend("interest_remittances_ms", true);

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
    // Advisory tripwires (ADR-0243), deliberately generous until the scheduled lane
    // establishes the dedicated-runner baseline (see sanctions-read-baseline for the
    // same calibration note).
    "interest_accruals_list_ms": ["p(95)<2600"],
    "interest_accruals_account_ms": ["p(95)<2000"],
    "interest_accruals_summary_ms": ["p(95)<2000"],
    "interest_capitalizations_ms": ["p(95)<2000"],
    "interest_remittances_ms": ["p(95)<2000"],

    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  const l = http.get(`${BASE_URL}/api/v1/interest/accruals`, {
    headers: readHeaders,
    tags: { name: "interest_accruals_list" },
  });
  listMs.add(l.timings.duration);
  check(l, { "accruals list 200": (res) => res.status === 200 });

  const a = http.get(`${BASE_URL}/api/v1/interest/accruals/${ACCOUNT_ID}`, {
    headers: readHeaders,
    tags: { name: "interest_accruals_account" },
  });
  accountMs.add(a.timings.duration);
  check(a, { "accruals by account 200": (res) => res.status === 200 });

  const s = http.get(`${BASE_URL}/api/v1/interest/accruals/${ACCOUNT_ID}/summary`, {
    headers: readHeaders,
    tags: { name: "interest_accruals_summary" },
  });
  summaryMs.add(s.timings.duration);
  check(s, { "accruals summary 200": (res) => res.status === 200 });

  const c = http.get(`${BASE_URL}/api/v1/interest/capitalizations/${ACCOUNT_ID}`, {
    headers: readHeaders,
    tags: { name: "interest_capitalizations" },
  });
  capitalizationsMs.add(c.timings.duration);
  check(c, { "capitalizations 200": (res) => res.status === 200 });

  const r = http.get(`${BASE_URL}/api/v1/interest/withholding/remittances`, {
    headers: readHeaders,
    tags: { name: "interest_remittances" },
  });
  remittancesMs.add(r.timings.duration);
  check(r, { "withholding remittances 200": (res) => res.status === 200 });
}
