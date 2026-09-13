// SPDX-License-Identifier: Apache-2.0
// k6 read baseline for openbank-fx-service.
//
// FX rate lookup sits INSIDE the payment path for any cross-currency instruction, so its
// p95 is additive to every payment it touches. The history read is the expensive one.
//
// READ-ONLY BY CONSTRUCTION: every request below is a GET. No openbank-fx-service state is created,
// mutated or deleted, so this lane is safe against any booted stack, including a shared
// sandbox. Write-path benchmarking for money-path services stays in the manual, isolated
// perf/k6/money-path-write-benchmark.js lane.
//
// Discovered as an artifact by openbank-admin-ui/scripts/collect-test-intelligence.mjs
// (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`.
//
// Run:
//   BASE_URL=http://localhost:8120 PERF_READ_TOKEN=<token> \
//     k6 run openbank-fx-service/src/test/k6/fx-read-baseline.js
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

const BASE_URL = __ENV.BASE_URL || "http://localhost:8120";
// PERF_READ_TOKEN is injected by the scheduled workflow from an environment secret.
// Deliberately no default; never logged, never used as a k6 tag.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };
const BASE = __ENV.PERF_FX_BASE || "EUR";
const QUOTE = __ENV.PERF_FX_QUOTE || "CZK";

const ratesMs = new Trend("fx_rates_ms", true);
const pairMs = new Trend("fx_pair_ms", true);
const historyMs = new Trend("fx_history_ms", true);

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
    // A single pair lookup is in the payment critical path — keep it tight.
    "fx_pair_ms": ["p(95)<200"],
    "fx_rates_ms": ["p(95)<400"],
    "fx_history_ms": ["p(95)<1000"],

    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  const r = http.get(`${BASE_URL}/api/v1/fx/rates`, {
    headers: readHeaders,
    tags: { name: "fx_rates" },
  });
  ratesMs.add(r.timings.duration);
  check(r, { "fx rates 200": (res) => res.status === 200 });

  const p = http.get(`${BASE_URL}/api/v1/fx/rates/${BASE}/${QUOTE}`, {
    headers: readHeaders,
    tags: { name: "fx_pair" },
  });
  pairMs.add(p.timings.duration);
  check(p, { "fx pair 200": (res) => res.status === 200 });

  const h = http.get(`${BASE_URL}/api/v1/fx/rates/${BASE}/${QUOTE}/history?limit=100`, {
    headers: readHeaders,
    tags: { name: "fx_history" },
  });
  historyMs.add(h.timings.duration);
  check(h, { "fx history 200": (res) => res.status === 200 });
}
