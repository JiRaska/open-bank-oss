// SPDX-License-Identifier: Apache-2.0
// k6 read baseline for openbank-settlement-service.
//
// Settlement back-office reads back the settlement ledger (one settlement's persisted status,
// the pending operator-approval queue, a single approval record); there was no load evidence
// behind their SLO. The settle/approve/reject endpoints are POSTs and stay out of this lane by
// construction — every request below is a GET.
//
// READ-ONLY BY CONSTRUCTION: every request below is a GET. No openbank-settlement-service
// state is created, mutated or deleted, so this lane is safe against any booted stack,
// including a shared sandbox. Write-path benchmarking for money-path services stays in the
// manual, isolated perf/k6/money-path-write-benchmark.js lane.
//
// Discovered as an artifact by openbank-admin-ui/scripts/collect-test-intelligence.mjs
// (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`.
//
// Run:
//   BASE_URL=http://localhost:8138 PERF_READ_TOKEN=<token> \
//     PERF_SETTLEMENT_ID=<uuid> PERF_APPROVAL_ID=<uuid> \
//     k6 run openbank-settlement-service/src/test/k6/settlement-read-baseline.js
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

const BASE_URL = __ENV.BASE_URL || "http://localhost:8138";
// PERF_READ_TOKEN is injected by the scheduled workflow from an environment secret.
// Deliberately no default; never logged, never used as a k6 tag.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };
// The fixed fixture ids src/test/k6/fixtures.sql seeds: the settlement detail probe and the
// pending-approval detail probe.
const SETTLEMENT_ID = __ENV.PERF_SETTLEMENT_ID || "00000000-0000-0000-0000-0000000000e1";
const APPROVAL_ID = __ENV.PERF_APPROVAL_ID || "00000000-0000-0000-0000-0000000000e2";

const detailMs = new Trend("settlement_detail_ms", true);
const pendingMs = new Trend("settlement_approvals_pending_ms", true);
const approvalMs = new Trend("settlement_approval_detail_ms", true);

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
    "settlement_detail_ms": ["p(95)<2000"],
    "settlement_approvals_pending_ms": ["p(95)<2000"],
    "settlement_approval_detail_ms": ["p(95)<2000"],

    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  const d = http.get(`${BASE_URL}/api/v1/settlements/${SETTLEMENT_ID}`, {
    headers: readHeaders,
    tags: { name: "settlement_detail" },
  });
  detailMs.add(d.timings.duration);
  check(d, { "settlement detail 200": (res) => res.status === 200 });

  const p = http.get(`${BASE_URL}/api/v1/settlements/approvals`, {
    headers: readHeaders,
    tags: { name: "settlement_approvals_pending" },
  });
  pendingMs.add(p.timings.duration);
  check(p, { "pending approvals 200": (res) => res.status === 200 });

  const a = http.get(`${BASE_URL}/api/v1/settlements/approvals/${APPROVAL_ID}`, {
    headers: readHeaders,
    tags: { name: "settlement_approval_detail" },
  });
  approvalMs.add(a.timings.duration);
  check(a, { "approval detail 200": (res) => res.status === 200 });
}
