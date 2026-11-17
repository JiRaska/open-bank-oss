// SPDX-License-Identifier: Apache-2.0
// k6 read baseline for openbank-standing-order-service.
//
// Standing-order reads back the recurring-payment list of the customer app and the
// back-office operations queue; there was no load evidence behind their SLO. perf/k6/
// money-path-smoke.js probes the fleet-wide journals only, so this lane is the standing
// order's OWN baseline: list by party, list all, and detail by id.
//
// READ-ONLY BY CONSTRUCTION: every request below is a GET. No
// openbank-standing-order-service state is created, mutated or deleted, so this lane is
// safe against any booted stack, including a shared sandbox. Write-path benchmarking for
// money-path services stays in the manual, isolated perf/k6/money-path-write-benchmark.js
// lane.
//
// Discovered as an artifact by openbank-admin-ui/scripts/collect-test-intelligence.mjs
// (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`.
//
// Run:
//   BASE_URL=http://localhost:8121 PERF_READ_TOKEN=<token> \
//     PERF_PARTY_ID=<uuid> PERF_STANDING_ORDER_ID=<uuid> \
//     k6 run openbank-standing-order-service/src/test/k6/standing-order-read-baseline.js
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

const BASE_URL = __ENV.BASE_URL || "http://localhost:8121";
// PERF_READ_TOKEN is injected by the scheduled workflow from an environment secret.
// Deliberately no default; never logged, never used as a k6 tag.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };
const PARTY_ID = __ENV.PERF_PARTY_ID || "00000000-0000-0000-0000-000000000001";
// PERF_STANDING_ORDER_ID names the fixed detail-probe fixture (seeded by
// src/test/k6/fixtures.sql at the script default id).
const STANDING_ORDER_ID = __ENV.PERF_STANDING_ORDER_ID || "00000000-0000-0000-0000-0000000000e2";

const byPartyMs = new Trend("standing_order_by_party_ms", true);
const listAllMs = new Trend("standing_order_list_all_ms", true);
const detailMs = new Trend("standing_order_detail_ms", true);

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
    "standing_order_by_party_ms": ["p(95)<600"],
    "standing_order_list_all_ms": ["p(95)<800"],
    "standing_order_detail_ms": ["p(95)<400"],

    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  const p = http.get(`${BASE_URL}/api/v1/standing-orders/party/${PARTY_ID}`, {
    headers: readHeaders,
    tags: { name: "standing_order_by_party" },
  });
  byPartyMs.add(p.timings.duration);
  check(p, { "standing orders by party 200": (res) => res.status === 200 });

  const l = http.get(`${BASE_URL}/api/v1/standing-orders`, {
    headers: readHeaders,
    tags: { name: "standing_order_list_all" },
  });
  listAllMs.add(l.timings.duration);
  check(l, { "standing order list all 200": (res) => res.status === 200 });

  const d = http.get(`${BASE_URL}/api/v1/standing-orders/${STANDING_ORDER_ID}`, {
    headers: readHeaders,
    tags: { name: "standing_order_detail" },
  });
  detailMs.add(d.timings.duration);
  check(d, { "standing order detail 200": (res) => res.status === 200 });
}
