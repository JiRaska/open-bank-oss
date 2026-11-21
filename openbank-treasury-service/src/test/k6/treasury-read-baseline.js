// SPDX-License-Identifier: Apache-2.0
// k6 read baseline for openbank-treasury-service.
//
// Treasury back-office reads back the dealer blotter (the deal list a trading screen renders,
// the filtered view, the posting-rule matrix, the events calendar, counterparty limits and
// headroom, daily positions, the accounting-day completion record); there was no load
// evidence behind their SLO. The booking/lifecycle endpoints are POSTs and stay out of this
// lane by construction — every request below is a GET.
//
// READ-ONLY BY CONSTRUCTION: every request below is a GET. No
// openbank-treasury-service state is created, mutated or deleted, so this lane is safe
// against any booted stack, including a shared sandbox. Write-path benchmarking for
// money-path services stays in the manual, isolated perf/k6/money-path-write-benchmark.js
// lane.
//
// Reference data (counterparties CNB/SIMBK-A/B/C, credit measures) is seeded by Flyway, and
// src/test/k6/fixtures.sql adds 60 deals across the three synthetic counterparties so the
// blotter, the calendar, positions and limit utilisation page real rows.
//
// Discovered as an artifact by openbank-admin-ui/scripts/collect-test-intelligence.mjs
// (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`.
//
// Run:
//   BASE_URL=http://localhost:8160 PERF_READ_TOKEN=<token> \
//     k6 run openbank-treasury-service/src/test/k6/treasury-read-baseline.js
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

const BASE_URL = __ENV.BASE_URL || "http://localhost:8160";
// PERF_READ_TOKEN is injected by the scheduled workflow from an environment secret.
// Deliberately no default; never logged, never used as a k6 tag.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };

const listMs = new Trend("treasury_deals_list_ms", true);
const filteredMs = new Trend("treasury_deals_filtered_ms", true);
const postingRulesMs = new Trend("treasury_posting_rules_ms", true);
const calendarMs = new Trend("treasury_calendar_ms", true);
const counterpartiesMs = new Trend("treasury_counterparties_ms", true);
const limitsMs = new Trend("treasury_limits_utilisation_ms", true);
const positionsMs = new Trend("treasury_positions_ms", true);
const dayCompletionMs = new Trend("treasury_day_completion_ms", true);

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
    "treasury_deals_list_ms": ["p(95)<2600"],
    "treasury_deals_filtered_ms": ["p(95)<2600"],
    "treasury_posting_rules_ms": ["p(95)<2000"],
    "treasury_calendar_ms": ["p(95)<2000"],
    "treasury_counterparties_ms": ["p(95)<2000"],
    "treasury_limits_utilisation_ms": ["p(95)<2000"],
    "treasury_positions_ms": ["p(95)<2000"],
    "treasury_day_completion_ms": ["p(95)<2000"],

    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  const l = http.get(`${BASE_URL}/api/v1/treasury/deals`, {
    headers: readHeaders,
    tags: { name: "treasury_deals_list" },
  });
  listMs.add(l.timings.duration);
  check(l, { "deals list 200": (res) => res.status === 200 });

  const f = http.get(`${BASE_URL}/api/v1/treasury/deals?state=BOOKED&currency=CZK`, {
    headers: readHeaders,
    tags: { name: "treasury_deals_filtered" },
  });
  filteredMs.add(f.timings.duration);
  check(f, { "deals filtered 200": (res) => res.status === 200 });

  const p = http.get(`${BASE_URL}/api/v1/treasury/posting-rules`, {
    headers: readHeaders,
    tags: { name: "treasury_posting_rules" },
  });
  postingRulesMs.add(p.timings.duration);
  check(p, { "posting rules 200": (res) => res.status === 200 });

  const c = http.get(`${BASE_URL}/api/v1/treasury/calendar?from=2026-01-01&to=2026-12-31`, {
    headers: readHeaders,
    tags: { name: "treasury_calendar" },
  });
  calendarMs.add(c.timings.duration);
  check(c, { "calendar 200": (res) => res.status === 200 });

  const cp = http.get(`${BASE_URL}/api/v1/treasury/counterparties`, {
    headers: readHeaders,
    tags: { name: "treasury_counterparties" },
  });
  counterpartiesMs.add(cp.timings.duration);
  check(cp, { "counterparties 200": (res) => res.status === 200 });

  const lu = http.get(`${BASE_URL}/api/v1/treasury/limits/utilisation`, {
    headers: readHeaders,
    tags: { name: "treasury_limits_utilisation" },
  });
  limitsMs.add(lu.timings.duration);
  check(lu, { "limits utilisation 200": (res) => res.status === 200 });

  const pos = http.get(`${BASE_URL}/api/v1/treasury/positions`, {
    headers: readHeaders,
    tags: { name: "treasury_positions" },
  });
  positionsMs.add(pos.timings.duration);
  check(pos, { "positions 200": (res) => res.status === 200 });

  const today = new Date().toISOString().slice(0, 10);
  const dc = http.get(`${BASE_URL}/api/v1/treasury/day-completion/${today}`, {
    headers: readHeaders,
    tags: { name: "treasury_day_completion" },
  });
  dayCompletionMs.add(dc.timings.duration);
  check(dc, { "day completion 200": (res) => res.status === 200 });
}
