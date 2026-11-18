// SPDX-License-Identifier: Apache-2.0
// k6 read baseline for openbank-pension-service.
//
// Pension back-office reads back the participant reporting aggregate (the period summary a
// supervisor screen renders), the state-contribution return desk (returns, filed reports,
// deadlines) and the annuity provider directory; there was no load evidence behind their SLO.
// The enrol/contribute/exit endpoints are POSTs and stay out of this lane by construction —
// every request below is a GET.
//
// READ-ONLY BY CONSTRUCTION: every request below is a GET. No openbank-pension-service state
// is created, mutated or deleted, so this lane is safe against any booted stack, including a
// shared sandbox. Write-path benchmarking for money-path services stays in the manual,
// isolated perf/k6/money-path-write-benchmark.js lane.
//
// Discovered as an artifact by openbank-admin-ui/scripts/collect-test-intelligence.mjs
// (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`.
//
// Run:
//   BASE_URL=http://localhost:8171 PERF_READ_TOKEN=<token> \
//     k6 run openbank-pension-service/src/test/k6/pension-read-baseline.js
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

const BASE_URL = __ENV.BASE_URL || "http://localhost:8171";
// PERF_READ_TOKEN is injected by the scheduled workflow from an environment secret.
// Deliberately no default; never logged, never used as a k6 tag.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };

const aggregatesMs = new Trend("pension_participant_aggregates_ms", true);
const returnsMs = new Trend("pension_sc_returns_ms", true);
const reportsMs = new Trend("pension_return_reports_ms", true);
const reportResultMs = new Trend("pension_report_result_ms", true);
const deadlinesMs = new Trend("pension_sc_deadlines_ms", true);

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
    "pension_participant_aggregates_ms": ["p(95)<2600"],
    "pension_sc_returns_ms": ["p(95)<2000"],
    "pension_return_reports_ms": ["p(95)<2000"],
    "pension_report_result_ms": ["p(95)<2000"],
    "pension_sc_deadlines_ms": ["p(95)<2000"],

    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  // A full calendar year: the aggregates read computes a year-to-date view from January 1,
  // so the window starts at the year boundary on purpose.
  const ag = http.get(
    `${BASE_URL}/api/v2/pension/reporting/participant-aggregates?periodStart=2026-01-01&periodEnd=2026-12-31`,
    { headers: readHeaders, tags: { name: "pension_participant_aggregates" } },
  );
  aggregatesMs.add(ag.timings.duration);
  check(ag, { "participant aggregates 200": (res) => res.status === 200 });

  const r = http.get(`${BASE_URL}/api/v2/pension/funding/operations/state-contribution/returns`, {
    headers: readHeaders,
    tags: { name: "pension_sc_returns" },
  });
  returnsMs.add(r.timings.duration);
  check(r, { "state contribution returns 200": (res) => res.status === 200 });

  const rp = http.get(`${BASE_URL}/api/v2/pension/funding/operations/state-contribution/return-reports`, {
    headers: readHeaders,
    tags: { name: "pension_return_reports" },
  });
  reportsMs.add(rp.timings.duration);
  check(rp, { "return reports 200": (res) => res.status === 200 });

  const dl = http.get(`${BASE_URL}/api/v2/pension/funding/operations/state-contribution/deadlines`, {
    headers: readHeaders,
    tags: { name: "pension_sc_deadlines" },
  });
  deadlinesMs.add(dl.timings.duration);
  check(dl, { "deadlines 200": (res) => res.status === 200 });
}
