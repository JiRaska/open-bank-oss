// SPDX-License-Identifier: Apache-2.0
// k6 read baseline for openbank-notification-service.
//
// Notification fan-out is the tail of every customer-facing flow the bank runs — a
// payment, an approval, a template publish all land as a notification row — so the
// list and template reads sit on the operator path that handles incident volume.
//
// READ-ONLY BY CONSTRUCTION: every request below is a GET. No
// openbank-notification-service state is created, mutated or deleted, so this lane is
// safe against any booted stack, including a shared sandbox. Write-path benchmarking
// for money-path services stays in the manual, isolated perf/k6/money-path-write-benchmark.js
// lane.
//
// Discovered as an artifact by openbank-admin-ui/scripts/collect-test-intelligence.mjs
// (<module>/src/test/k6/*.js) and executed by the perf workflow — never by Gradle `test`.
//
// Run:
//   BASE_URL=http://localhost:8112 PERF_READ_TOKEN=<token> \
//     k6 run openbank-notification-service/src/test/k6/notification-read-baseline.js
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

const BASE_URL = __ENV.BASE_URL || "http://localhost:8112";
// PERF_READ_TOKEN is injected by the scheduled workflow from an environment secret.
// Deliberately no default; never logged, never used as a k6 tag.
const readHeaders = { Authorization: `Bearer ${__ENV.PERF_READ_TOKEN || ""}` };

const listMs = new Trend("notification_list_ms", true);
const templatesMs = new Trend("notification_templates_ms", true);

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
    // The paginated list is the incident-volume read — the operator screens page by page.
    "notification_list_ms": ["p(95)<800"],
    // The template list backs the staff editorial surface; a little looser.
    "notification_templates_ms": ["p(95)<1200"],

    "http_req_failed": ["rate<0.01"],
    // A transport percentile is not a handler baseline unless every asserted route answered.
    "checks": ["rate==1.0"],
  },
};

export default function () {
  const list = http.get(`${BASE_URL}/api/v1/notifications?page=0&size=20`, {
    headers: readHeaders,
    tags: { name: "notification_list" },
  });
  listMs.add(list.timings.duration);
  check(list, {
    "notification_list status is 200": (r) => r.status === 200,
    "notification_list body has items": (r) => Array.isArray(JSON.parse(r.body).items),
  });

  const templates = http.get(`${BASE_URL}/api/v1/notification-templates?template=TRANSACTION_COMPLETED`, {
    headers: readHeaders,
    tags: { name: "notification_templates" },
  });
  templatesMs.add(templates.timings.duration);
  check(templates, {
    "notification_templates status is 200": (r) => r.status === 200,
  });
}
