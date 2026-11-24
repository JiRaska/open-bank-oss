// SPDX-License-Identifier: Apache-2.0
// k6 read baseline for openbank-swift-service.
//
// SWIFT messages carry the international leg of the payment platform; the
// message list, status-filtered views and approval queue are what operators
// watch while a payment is in flight, and this surface had no latency baseline.
//
// READ-ONLY BY CONSTRUCTION: every request below is a GET. No openbank-swift-service state is created,
// mutated or deleted, so this lane is safe against any booted stack, including a shared
// sandbox. Write-path benchmarking for money-path services stays in the manual, isolated
// perf/k6/money-path-write-benchmark.js lane.
//
// Discovered as an artifact by openbank-admin-ui/scripts/collect-test-intelligence.mjs
// (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`.
//
// Run:
//   BASE_URL=http://localhost:8122 PERF_READ_TOKEN=<token> \
//     PERF_SWIFT_STATUS=<status> \
//     k6 run openbank-swift-service/src/test/k6/swift-read-baseline.js
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

const BASE_URL = __ENV.BASE_URL || "http://localhost:8122";
// PERF_READ_TOKEN is injected by the scheduled workflow from an environment secret.
// Deliberately no default; never logged, never used as a k6 tag.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };
// A status value SwiftStatus actually declares (PENDING, VALIDATED, SENT, ACKNOWLEDGED,
// REJECTED, FAILED, COMPLETED) — an unknown value fails enum conversion with a 404 and the
// run fails loudly instead of timing a handler. An empty result set still answers 200, so
// the tripwire measures the handler, not fixture density.
const STATUS = __ENV.PERF_SWIFT_STATUS || "PENDING";

const messagesMs = new Trend("swift_messages_ms", true);
const statusMs = new Trend("swift_status_ms", true);
const approvalsMs = new Trend("swift_approvals_ms", true);

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
    // The status-filtered view is the in-flight monitoring read — the tightest tripwire here.
    "swift_status_ms": ["p(95)<400"],
    "swift_approvals_ms": ["p(95)<400"],
    "swift_messages_ms": ["p(95)<500"],

    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  const m = http.get(`${BASE_URL}/api/v1/swift/messages`, {
    headers: readHeaders,
    tags: { name: "swift_messages" },
  });
  messagesMs.add(m.timings.duration);
  check(m, { "swift messages 200": (res) => res.status === 200 });

  const s = http.get(`${BASE_URL}/api/v1/swift/status/${STATUS}`, {
    headers: readHeaders,
    tags: { name: "swift_status" },
  });
  statusMs.add(s.timings.duration);
  check(s, { "swift by status 200": (res) => res.status === 200 });

  const a = http.get(`${BASE_URL}/api/v1/swift/approvals`, {
    headers: readHeaders,
    tags: { name: "swift_approvals" },
  });
  approvalsMs.add(a.timings.duration);
  check(a, { "swift approvals 200": (res) => res.status === 200 });
}
