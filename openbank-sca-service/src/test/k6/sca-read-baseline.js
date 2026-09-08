// SPDX-License-Identifier: Apache-2.0
// k6 read baseline for openbank-sca-service.
//
// SCA is a synchronous blocker in front of every PSD2-scoped payment: a slow challenge or
// device read is a slow payment. Only the READ side is exercised — minting a challenge
// is a write and stays out of this lane.
//
// READ-ONLY BY CONSTRUCTION: every request below is a GET. No openbank-sca-service state is created,
// mutated or deleted, so this lane is safe against any booted stack, including a shared
// sandbox. Write-path benchmarking for money-path services stays in the manual, isolated
// perf/k6/money-path-write-benchmark.js lane.
//
// Discovered as an artifact by openbank-admin-ui/scripts/collect-test-intelligence.mjs
// (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`.
//
// Run:
//   BASE_URL=http://localhost:8130 PERF_READ_TOKEN=<token> \
////     PERF_PARTY_ID=<uuid> PERF_SCA_CHALLENGE_ID=<uuid> \
//     k6 run openbank-sca-service/src/test/k6/sca-read-baseline.js
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

const BASE_URL = __ENV.BASE_URL || "http://localhost:8130";
// PERF_READ_TOKEN is injected by the scheduled workflow from an environment secret.
// Deliberately no default; never logged, never used as a k6 tag.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };
const PARTY_ID = __ENV.PERF_PARTY_ID || "00000000-0000-0000-0000-000000000001";
const CHALLENGE_ID = __ENV.PERF_SCA_CHALLENGE_ID || "00000000-0000-0000-0000-000000000003";

const devicesMs = new Trend("sca_devices_ms", true);
const challengeMs = new Trend("sca_challenge_ms", true);

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
    // SCA blocks a human at a screen; both reads are sub-second by requirement.
    "sca_devices_ms": ["p(95)<300"],
    "sca_challenge_ms": ["p(95)<300"],

    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  const d = http.get(`${BASE_URL}/api/v1/sca/parties/${PARTY_ID}/devices`, {
    headers: readHeaders,
    tags: { name: "sca_devices" },
  });
  devicesMs.add(d.timings.duration);
  check(d, { "sca device list 200": (res) => res.status === 200 });

  const c = http.get(`${BASE_URL}/api/v1/sca/challenges/${CHALLENGE_ID}`, {
    headers: readHeaders,
    tags: { name: "sca_challenge" },
  });
  challengeMs.add(c.timings.duration);
  check(c, { "sca challenge detail 200": (res) => res.status === 200 });
}
