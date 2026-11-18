// SPDX-License-Identifier: Apache-2.0
// k6 read baseline for openbank-card-processing-service.
//
// Card-processing reads back the authorisation/dispute/token desks (an authorisation detail, the
// per-card authorisation history, a dispute case, the per-card dispute list, the network-token
// mirror for a card); there was no load evidence behind their SLO. The authorise/clear/reverse
// and dispute/token lifecycle endpoints are POSTs and stay out of this lane by construction —
// every request below is a GET.
//
// READ-ONLY BY CONSTRUCTION: every request below is a GET. No
// openbank-card-processing-service state is created, mutated or deleted, so this lane is safe
// against any booted stack, including a shared sandbox. Write-path benchmarking for money-path
// services stays in the manual, isolated perf/k6/money-path-write-benchmark.js lane.
//
// Discovered as an artifact by openbank-admin-ui/scripts/collect-test-intelligence.mjs
// (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`.
//
// Run:
//   BASE_URL=http://localhost:8157 PERF_READ_TOKEN=<token> \
//     PERF_CARD_ID=<uuid> PERF_AUTH_ID=<uuid> PERF_DISPUTE_ID=<uuid> \
//     k6 run openbank-card-processing-service/src/test/k6/card-processing-read-baseline.js
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

const BASE_URL = __ENV.BASE_URL || "http://localhost:8157";
// PERF_READ_TOKEN is injected by the scheduled workflow from an environment secret.
// Deliberately no default; never logged, never used as a k6 tag.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };
// The fixed fixture ids src/test/k6/fixtures.sql seeds: the probe card carries 200
// authorisations, 1 dispute case and 3 network tokens.
const CARD_ID = __ENV.PERF_CARD_ID || "00000000-0000-0000-0000-0000000000d1";
const AUTH_ID = __ENV.PERF_AUTH_ID || "00000000-0000-0000-0000-0000000000d2";
const DISPUTE_ID = __ENV.PERF_DISPUTE_ID || "00000000-0000-0000-0000-0000000000d3";

const authByIdMs = new Trend("cardproc_auth_detail_ms", true);
const authByCardMs = new Trend("cardproc_auth_by_card_ms", true);
const disputeByIdMs = new Trend("cardproc_dispute_detail_ms", true);
const disputeByCardMs = new Trend("cardproc_dispute_by_card_ms", true);
const tokensByCardMs = new Trend("cardproc_tokens_by_card_ms", true);

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
    "cardproc_auth_detail_ms": ["p(95)<2000"],
    "cardproc_auth_by_card_ms": ["p(95)<2600"],
    "cardproc_dispute_detail_ms": ["p(95)<2000"],
    "cardproc_dispute_by_card_ms": ["p(95)<2000"],
    "cardproc_tokens_by_card_ms": ["p(95)<2000"],

    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  const a = http.get(`${BASE_URL}/api/v1/card-authorizations/${AUTH_ID}`, {
    headers: readHeaders,
    tags: { name: "cardproc_auth_detail" },
  });
  authByIdMs.add(a.timings.duration);
  check(a, { "authorization detail 200": (res) => res.status === 200 });

  const ac = http.get(`${BASE_URL}/api/v1/card-authorizations/card/${CARD_ID}`, {
    headers: readHeaders,
    tags: { name: "cardproc_auth_by_card" },
  });
  authByCardMs.add(ac.timings.duration);
  check(ac, { "authorizations by card 200": (res) => res.status === 200 });

  const d = http.get(`${BASE_URL}/api/v1/card-disputes/${DISPUTE_ID}`, {
    headers: readHeaders,
    tags: { name: "cardproc_dispute_detail" },
  });
  disputeByIdMs.add(d.timings.duration);
  check(d, { "dispute detail 200": (res) => res.status === 200 });

  const dc = http.get(`${BASE_URL}/api/v1/card-disputes/card/${CARD_ID}`, {
    headers: readHeaders,
    tags: { name: "cardproc_dispute_by_card" },
  });
  disputeByCardMs.add(dc.timings.duration);
  check(dc, { "disputes by card 200": (res) => res.status === 200 });

  const t = http.get(`${BASE_URL}/api/v1/card-tokens/card/${CARD_ID}`, {
    headers: readHeaders,
    tags: { name: "cardproc_tokens_by_card" },
  });
  tokensByCardMs.add(t.timings.duration);
  check(t, { "tokens by card 200": (res) => res.status === 200 });
}
