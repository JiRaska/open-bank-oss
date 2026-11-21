// SPDX-License-Identifier: Apache-2.0
// k6 read baseline for openbank-card-issuance-service.
//
// Card back-office reads back the card-issuance ledger (the card list a back-office screen
// renders, per-account card listing, the spend-category taxonomy, per-card category rules);
// there was no load evidence behind their SLO. The issue/lifecycle endpoints are POSTs and
// stay out of this lane by construction — every request below is a GET.
//
// READ-ONLY BY CONSTRUCTION: every request below is a GET. No
// openbank-card-issuance-service state is created, mutated or deleted, so this lane is safe
// against any booted stack, including a shared sandbox. Write-path benchmarking for
// money-path services stays in the manual, isolated perf/k6/money-path-write-benchmark.js
// lane.
//
// The id- and party-scoped GETs that carry a named-caller guard (GET /{id}, GET /party/{id},
// GET /{id}/secure-details) and the entitlements read (which calls product-catalog) are
// deliberately NOT probed here: the guard rejects the anonymous lane identity and the
// entitlements read needs a second service, so both would measure a rejection, not a handler.
//
// Discovered as an artifact by openbank-admin-ui/scripts/collect-test-intelligence.mjs
// (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`.
//
// Run:
//   BASE_URL=http://localhost:8118 PERF_READ_TOKEN=<token> \
//     PERF_ACCOUNT_ID=<uuid> PERF_CARD_ID=<uuid> \
//     k6 run openbank-card-issuance-service/src/test/k6/card-issuance-read-baseline.js
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

const BASE_URL = __ENV.BASE_URL || "http://localhost:8118";
// PERF_READ_TOKEN is injected by the scheduled workflow from an environment secret.
// Deliberately no default; never logged, never used as a k6 tag.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };
// PERF_ACCOUNT_ID is the fleet-wide fixture account; src/test/k6/fixtures.sql seeds 200
// cards for it. PERF_CARD_ID is the fixed fixture card the category-rules probe targets.
const ACCOUNT_ID = __ENV.PERF_ACCOUNT_ID || "00000000-0000-0000-0000-000000000002";
const CARD_ID = __ENV.PERF_CARD_ID || "00000000-0000-0000-0000-0000000000d1";

const listMs = new Trend("card_list_ms", true);
const accountMs = new Trend("card_account_ms", true);
const taxonomyMs = new Trend("card_taxonomy_ms", true);
const categoryLimitsMs = new Trend("card_category_limits_ms", true);

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
    "card_list_ms": ["p(95)<2600"],
    "card_account_ms": ["p(95)<2000"],
    "card_taxonomy_ms": ["p(95)<2000"],
    "card_category_limits_ms": ["p(95)<2000"],

    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  const l = http.get(`${BASE_URL}/api/v1/cards`, {
    headers: readHeaders,
    tags: { name: "card_list" },
  });
  listMs.add(l.timings.duration);
  check(l, { "card list 200": (res) => res.status === 200 });

  const a = http.get(`${BASE_URL}/api/v1/cards/account/${ACCOUNT_ID}`, {
    headers: readHeaders,
    tags: { name: "card_account" },
  });
  accountMs.add(a.timings.duration);
  check(a, { "cards by account 200": (res) => res.status === 200 });

  const t = http.get(`${BASE_URL}/api/v1/cards/category-taxonomy`, {
    headers: readHeaders,
    tags: { name: "card_taxonomy" },
  });
  taxonomyMs.add(t.timings.duration);
  check(t, { "category taxonomy 200": (res) => res.status === 200 });

  const c = http.get(`${BASE_URL}/api/v1/cards/${CARD_ID}/category-limits`, {
    headers: readHeaders,
    tags: { name: "card_category_limits" },
  });
  categoryLimitsMs.add(c.timings.duration);
  check(c, { "category limits 200": (res) => res.status === 200 });
}
