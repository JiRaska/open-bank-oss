// SPDX-License-Identifier: Apache-2.0
// k6 read baseline for openbank-sanctions-service.
//
// Sanctions reads back the compliance review queue (pending checks, confirmed hits, the
// screened-subject lists) that an analyst works through on every alert; there was no load
// evidence behind their SLO. The screen endpoint is a POST and stays out of this lane by
// construction — every request below is a GET.
//
// READ-ONLY BY CONSTRUCTION: every request below is a GET. No
// openbank-sanctions-service state is created, mutated or deleted, so this lane is safe
// against any booted stack, including a shared sandbox. Write-path benchmarking for
// money-path services stays in the manual, isolated perf/k6/money-path-write-benchmark.js
// lane.
//
// Discovered as an artifact by openbank-admin-ui/scripts/collect-test-intelligence.mjs
// (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`.
//
// Run:
//   BASE_URL=http://localhost:8123 PERF_READ_TOKEN=<token> \
//     PERF_SANCTIONS_CHECK_ID=<uuid> \
//     k6 run openbank-sanctions-service/src/test/k6/sanctions-read-baseline.js
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

const BASE_URL = __ENV.BASE_URL || "http://localhost:8123";
// PERF_READ_TOKEN is injected by the scheduled workflow from an environment secret.
// Deliberately no default; never logged, never used as a k6 tag.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };
// PERF_SANCTIONS_CHECK_ID names the fixed detail-probe fixture (seeded by
// src/test/k6/fixtures.sql at the script default id).
const CHECK_ID = __ENV.PERF_SANCTIONS_CHECK_ID || "00000000-0000-0000-0000-0000000000e2";

const checksMs = new Trend("sanctions_checks_ms", true);
const pendingMs = new Trend("sanctions_pending_ms", true);
const hitsMs = new Trend("sanctions_hits_ms", true);
const detailMs = new Trend("sanctions_check_detail_ms", true);
const listsMs = new Trend("sanctions_lists_ms", true);

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
    // Advisory tripwires (ADR-0243), deliberately generous: the local throwaway-stack runs
    // could not produce a stable baseline (two back-to-back runs of the same lane differed
    // ~2x in p95 under container load on a shared laptop). Values sit above the WORST
    // observed p95 (1.24–1.54 s) so the first scheduled CI runs publish green evidence;
    // tighten to the dedicated-runner percentiles once that baseline exists.
    "sanctions_checks_ms": ["p(95)<3000"],
    "sanctions_pending_ms": ["p(95)<2600"],
    "sanctions_hits_ms": ["p(95)<2600"],
    "sanctions_check_detail_ms": ["p(95)<2500"],
    "sanctions_lists_ms": ["p(95)<2500"],

    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  const c = http.get(`${BASE_URL}/api/v1/sanctions`, {
    headers: readHeaders,
    tags: { name: "sanctions_checks" },
  });
  checksMs.add(c.timings.duration);
  check(c, { "sanctions checks 200": (res) => res.status === 200 });

  const p = http.get(`${BASE_URL}/api/v1/sanctions/pending`, {
    headers: readHeaders,
    tags: { name: "sanctions_pending" },
  });
  pendingMs.add(p.timings.duration);
  check(p, { "sanctions pending 200": (res) => res.status === 200 });

  const h = http.get(`${BASE_URL}/api/v1/sanctions/hits`, {
    headers: readHeaders,
    tags: { name: "sanctions_hits" },
  });
  hitsMs.add(h.timings.duration);
  check(h, { "sanctions hits 200": (res) => res.status === 200 });

  const d = http.get(`${BASE_URL}/api/v1/sanctions/${CHECK_ID}`, {
    headers: readHeaders,
    tags: { name: "sanctions_check_detail" },
  });
  detailMs.add(d.timings.duration);
  check(d, { "sanctions check detail 200": (res) => res.status === 200 });

  const l = http.get(`${BASE_URL}/api/v1/sanctions/lists`, {
    headers: readHeaders,
    tags: { name: "sanctions_lists" },
  });
  listsMs.add(l.timings.duration);
  check(l, { "sanctions lists 200": (res) => res.status === 200 });
}
