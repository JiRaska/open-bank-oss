// SPDX-License-Identifier: Apache-2.0
// k6 read baseline for openbank-consent-service.
//
// Every PSD2 TPP call resolves a consent first, so consent lookup latency is additive to
// the whole open-banking surface. Validation is a POST and is deliberately excluded.
//
// READ-ONLY BY CONSTRUCTION: every request below is a GET. No openbank-consent-service state is created,
// mutated or deleted, so this lane is safe against any booted stack, including a shared
// sandbox. Write-path benchmarking for money-path services stays in the manual, isolated
// perf/k6/money-path-write-benchmark.js lane.
//
// Discovered as an artifact by openbank-admin-ui/scripts/collect-test-intelligence.mjs
// (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`.
//
// Run:
//   BASE_URL=http://localhost:8140 PERF_READ_TOKEN=<token> \
////     PERF_PARTY_ID=<uuid> PERF_GRANTEE_ID=<tpp-id> \
//     k6 run openbank-consent-service/src/test/k6/consent-read-baseline.js
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

const BASE_URL = __ENV.BASE_URL || "http://localhost:8140";
// PERF_READ_TOKEN is injected by the scheduled workflow from an environment secret.
// Deliberately no default; never logged, never used as a k6 tag.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };
const PARTY_ID = __ENV.PERF_PARTY_ID || "00000000-0000-0000-0000-000000000001";
const GRANTEE_ID = __ENV.PERF_GRANTEE_ID || "tpp-perf-baseline";

const byPartyMs = new Trend("consent_by_party_ms", true);
const byGranteeMs = new Trend("consent_by_grantee_ms", true);
const activeMs = new Trend("consent_active_ms", true);

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
    // The active-consent resolve runs on EVERY TPP request — the tightest tripwire.
    "consent_active_ms": ["p(95)<250"],
    "consent_by_party_ms": ["p(95)<500"],
    "consent_by_grantee_ms": ["p(95)<500"],

    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  const p = http.get(`${BASE_URL}/api/v1/consents/party/${PARTY_ID}`, {
    headers: readHeaders,
    tags: { name: "consent_by_party" },
  });
  byPartyMs.add(p.timings.duration);
  check(p, { "consents by party 200": (res) => res.status === 200 });

  const g = http.get(`${BASE_URL}/api/v1/consents/grantee/${GRANTEE_ID}`, {
    headers: readHeaders,
    tags: { name: "consent_by_grantee" },
  });
  byGranteeMs.add(g.timings.duration);
  check(g, { "consents by grantee 200": (res) => res.status === 200 });

  const a = http.get(`${BASE_URL}/api/v1/consents/party/${PARTY_ID}/grantee/${GRANTEE_ID}/active`, {
    headers: readHeaders,
    tags: { name: "consent_active" },
  });
  activeMs.add(a.timings.duration);
  check(a, { "active consent 200": (res) => res.status === 200 });
}
