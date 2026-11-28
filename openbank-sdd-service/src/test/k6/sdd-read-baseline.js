// SPDX-License-Identifier: Apache-2.0
// k6 read baseline for openbank-sdd-service.
//
// Direct-debit back-office reads back the mandate vault (the mandate list a console renders,
// the per-account view, the newest-mandates queue, one mandate detail, the post-settlement
// refund assessment); there was no load evidence behind their SLO. The
// create/amend/cancel/collect endpoints are POSTs and stay out of this lane by construction —
// every request below is a GET.
//
// READ-ONLY BY CONSTRUCTION: every request below is a GET. No openbank-sdd-service state is
// created, mutated or deleted, so this lane is safe against any booted stack, including a
// shared sandbox. Write-path benchmarking for money-path services stays in the manual,
// isolated perf/k6/money-path-write-benchmark.js lane.
//
// Discovered as an artifact by openbank-admin-ui/scripts/collect-test-intelligence.mjs
// (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`.
//
// Run:
//   BASE_URL=http://localhost:8129 PERF_READ_TOKEN=<token> \
//     PERF_ACCOUNT_ID=<uuid> PERF_MANDATE_ID=<uuid> \
//     k6 run openbank-sdd-service/src/test/k6/sdd-read-baseline.js
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

const BASE_URL = __ENV.BASE_URL || "http://localhost:8129";
// PERF_READ_TOKEN is injected by the scheduled workflow from an environment secret.
// Deliberately no default; never logged, never used as a k6 tag.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };
// PERF_ACCOUNT_ID is the fleet-wide fixture account; src/test/k6/fixtures.sql seeds 200
// mandates for it. PERF_MANDATE_ID is the fixed ACTIVE mandate the detail and refund probes
// target.
const ACCOUNT_ID = __ENV.PERF_ACCOUNT_ID || "00000000-0000-0000-0000-000000000002";
const MANDATE_ID = __ENV.PERF_MANDATE_ID || "00000000-0000-0000-0000-0000000000d1";

const listMs = new Trend("sdd_mandates_account_ms", true);
const recentMs = new Trend("sdd_mandates_recent_ms", true);
const detailMs = new Trend("sdd_mandate_detail_ms", true);
const refundMs = new Trend("sdd_refund_assessment_ms", true);

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
    "sdd_mandates_account_ms": ["p(95)<2600"],
    "sdd_mandates_recent_ms": ["p(95)<2000"],
    "sdd_mandate_detail_ms": ["p(95)<2000"],
    "sdd_refund_assessment_ms": ["p(95)<2000"],

    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  // NOTE: GET /mandates requires the accountId query parameter (it is the account's mandate
  // list), so the bare-list and per-account probes are one and the same route.
  const a = http.get(`${BASE_URL}/api/v1/sdd/mandates?accountId=${ACCOUNT_ID}`, {
    headers: readHeaders,
    tags: { name: "sdd_mandates_account" },
  });
  listMs.add(a.timings.duration);
  check(a, { "mandates list 200": (res) => res.status === 200 });

  const r = http.get(`${BASE_URL}/api/v1/sdd/mandates/recent`, {
    headers: readHeaders,
    tags: { name: "sdd_mandates_recent" },
  });
  recentMs.add(r.timings.duration);
  check(r, { "recent mandates 200": (res) => res.status === 200 });

  const d = http.get(`${BASE_URL}/api/v1/sdd/mandates/${MANDATE_ID}`, {
    headers: readHeaders,
    tags: { name: "sdd_mandate_detail" },
  });
  detailMs.add(d.timings.duration);
  check(d, { "mandate detail 200": (res) => res.status === 200 });

  // A refund claim 30 days after debit: inside the 8-week unconditional window, so the
  // assessment computes a real verdict for the fixed mandate.
  const debitDate = new Date(Date.now() - 30 * 24 * 3600 * 1000).toISOString().slice(0, 10);
  const f = http.get(
    `${BASE_URL}/api/v1/sdd/mandates/${MANDATE_ID}/refund-assessment?debitDate=${debitDate}`,
    { headers: readHeaders, tags: { name: "sdd_refund_assessment" } },
  );
  refundMs.add(f.timings.duration);
  check(f, { "refund assessment 200": (res) => res.status === 200 });
}
