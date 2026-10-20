// SPDX-License-Identifier: AGPL-3.0-only
// k6 smoke test for openbank-case-coordinator-agent (case queue read lane).
// Runs a short read-only load against the case list with lenient CI
// thresholds.
//
// READ-ONLY BY CONSTRUCTION: a single GET against the case list; nothing is
// created, mutated or deleted. A fresh database yields an empty list, which
// answers 200 — the list endpoint is a plain @RolesAllowed read, and the
// perf-gate harness boots with auth disabled, so it is permissive. Case
// creation and the kill-switch mutate agent state, and the id-scoped case
// read is 404 with no seeded fixtures — all out of scope for this read-only
// lane.
//
// The perf-gate harness boots the service in dev mode against a throwaway
// postgres with auth disabled (quarkus.oidc.enabled=false, authz.enforce=false)
// and the messaging health check off (quarkus.messaging.health.enabled=false —
// SmallRye reports the Kafka channel readiness DOWN without a broker), so the
// read-only route below answers 200 on a fresh database. Kafka dev-services
// are switched off for this subject (no bootstrap-servers in config); the
// Temporal worker is disabled by default (openbank.temporal.enabled=false)
// and registers only when explicitly enabled, so no dev server is needed.

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8146';

// Advisory thresholds, mirroring the fleet-wide smoke slices: the public
// synthetic test budget (p95 < 2s) and the canary error gate (rate < 1%).
export const options = {
  vus: 5,
  duration: '30s',
  thresholds: {
    http_req_duration: ['p(95)<2000'],
    http_req_failed:   ['rate<0.01'],
    checks:            ['rate==1.0'],
  },
};

const okRate = new Rate('endpoint_ok_rate');

export default function () {
  group('case-coordinator cases read-only smoke', () => {
    // The FIRST BASE_URL request in this file is what .github/scripts/k6-smoke-path.py
    // extracts for perf-gate.yml's pre-flight probe — keep the cases read first.
    const res = http.get(`${BASE_URL}/api/v1/case-coordinator/cases`, {
      tags: { endpoint: 'cases-list', service: 'case-coordinator-agent' },
    });

    const is200 = res.status === 200;
    const isJson = res.headers['Content-Type'] && res.headers['Content-Type'].includes('application/json');
    okRate.add(is200 && isJson);

    check(res, {
      'cases-list status is 200': (r) => r.status === 200,
      'cases-list returns JSON':  (r) => isJson,
      // The case queue wraps its rows in a keyed object (CaseListResponse).
      'cases-list body is object': (r) => typeof JSON.parse(r.body) === 'object' && !Array.isArray(JSON.parse(r.body)),
    });

    sleep(1);
  });
}
