// SPDX-License-Identifier: Apache-2.0
// k6 read baseline for openbank-ledger-service.
//
// perf/k6/money-path-smoke.js already probes /api/v1/journals fleet-wide; this lane is the
// ledger's OWN baseline and adds the two aggregate reads (trial balance, accounting day)
// that dominate close-period latency and are absent from the fleet smoke.
//
// READ-ONLY BY CONSTRUCTION: every request below is a GET. No openbank-ledger-service state is created,
// mutated or deleted, so this lane is safe against any booted stack, including a shared
// sandbox. Write-path benchmarking for money-path services stays in the manual, isolated
// perf/k6/money-path-write-benchmark.js lane.
//
// Discovered as an artifact by openbank-admin-ui/scripts/collect-test-intelligence.mjs
// (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`.
//
// Run:
//   BASE_URL=http://localhost:8101 PERF_READ_TOKEN=<token> \
//     k6 run openbank-ledger-service/src/test/k6/ledger-read-baseline.js
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

const BASE_URL = __ENV.BASE_URL || "http://localhost:8101";
// PERF_READ_TOKEN is injected by the scheduled workflow from an environment secret.
// Deliberately no default; never logged, never used as a k6 tag.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };

const journalsMs = new Trend("ledger_journals_ms", true);
const trialBalanceMs = new Trend("ledger_trial_balance_ms", true);
const accountingDayMs = new Trend("ledger_accounting_day_ms", true);

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
    "ledger_journals_ms": ["p(95)<800"],
    // A whole-book aggregate: generous tripwire, but a regression here is a close-cycle risk.
    "ledger_trial_balance_ms": ["p(95)<2000"],
    "ledger_accounting_day_ms": ["p(95)<300"],

    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  const j = http.get(`${BASE_URL}/api/v1/journals?limit=20`, {
    headers: readHeaders,
    tags: { name: "ledger_journals" },
  });
  journalsMs.add(j.timings.duration);
  check(j, { "journal list 200": (res) => res.status === 200 });

  const t = http.get(`${BASE_URL}/api/v1/journals/trial-balance`, {
    headers: readHeaders,
    tags: { name: "ledger_trial_balance" },
  });
  trialBalanceMs.add(t.timings.duration);
  check(t, { "trial balance 200": (res) => res.status === 200 });

  const d = http.get(`${BASE_URL}/api/v1/ledger/accounting-days/current`, {
    headers: readHeaders,
    tags: { name: "ledger_accounting_day" },
  });
  accountingDayMs.add(d.timings.duration);
  check(d, { "current accounting day 200": (res) => res.status === 200 });
}
