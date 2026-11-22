// SPDX-License-Identifier: Apache-2.0
// k6 read baseline for openbank-clearing-service.
//
// Clearing batches aggregate the day's payment traffic; operators watch the
// batch list, the approval queue and batch detail during every settlement
// window, and this surface had no latency baseline.
//
// READ-ONLY BY CONSTRUCTION: every request below is a GET. No openbank-clearing-service state is created,
// mutated or deleted, so this lane is safe against any booted stack, including a shared
// sandbox. Write-path benchmarking for money-path services stays in the manual, isolated
// perf/k6/money-path-write-benchmark.js lane.
//
// Discovered as an artifact by openbank-admin-ui/scripts/collect-test-intelligence.mjs
// (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`.
//
// Run:
//   BASE_URL=http://localhost:8124 PERF_READ_TOKEN=<token> \
//     PERF_BATCH_ID=<uuid> \
//     k6 run openbank-clearing-service/src/test/k6/clearing-read-baseline.js
//
// The id-scoped probe needs a seeded fixture: override PERF_BATCH_ID against a
// seeded sandbox, otherwise the detail probe 404s and the run fails loudly instead
// of publishing a transport percentile.
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

const BASE_URL = __ENV.BASE_URL || "http://localhost:8124";
// PERF_READ_TOKEN is injected by the scheduled workflow from an environment secret.
// Deliberately no default; never logged, never used as a k6 tag.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };
const BATCH_ID = __ENV.PERF_BATCH_ID || "00000000-0000-0000-0000-000000000002";

const batchesMs = new Trend("clearing_batches_ms", true);
const approvalsMs = new Trend("clearing_approvals_ms", true);
const detailMs = new Trend("clearing_batch_detail_ms", true);

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
    // The batch-detail read backs settlement-window monitoring — the tightest tripwire here.
    "clearing_batch_detail_ms": ["p(95)<300"],
    "clearing_approvals_ms": ["p(95)<400"],
    "clearing_batches_ms": ["p(95)<500"],

    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  const b = http.get(`${BASE_URL}/api/v1/clearing/batches`, {
    headers: readHeaders,
    tags: { name: "clearing_batches" },
  });
  batchesMs.add(b.timings.duration);
  check(b, { "clearing batches 200": (res) => res.status === 200 });

  const a = http.get(`${BASE_URL}/api/v1/clearing/approvals`, {
    headers: readHeaders,
    tags: { name: "clearing_approvals" },
  });
  approvalsMs.add(a.timings.duration);
  check(a, { "pending approvals 200": (res) => res.status === 200 });

  const d = http.get(`${BASE_URL}/api/v1/clearing/batches/${BATCH_ID}`, {
    headers: readHeaders,
    tags: { name: "clearing_batch_detail" },
  });
  detailMs.add(d.timings.duration);
  check(d, { "batch detail 200": (res) => res.status === 200 });
}
