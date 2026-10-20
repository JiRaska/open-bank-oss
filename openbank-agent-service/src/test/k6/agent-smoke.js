// SPDX-License-Identifier: AGPL-3.0-only
// k6 smoke test for openbank-agent-service (ADR-0031 HITL approval queue).
// Runs a short read-only load against the proposals list with lenient CI
// thresholds.
//
// READ-ONLY BY CONSTRUCTION: a single GET against the pending-proposals list;
// nothing is created, mutated or deleted. `state` defaults to `pending` and a
// fresh database yields an empty list, which answers 200 — the list endpoint
// is a plain @Blocking JDBC read behind @RolesAllowed, and the perf-gate
// harness boots with auth disabled, so it is permissive. The decision POST is
// a HITL write (and 404 without fixtures), the merge-record POST records
// evidence, and the admin halt/resume endpoints mutate runtime state — all
// out of scope for this read-only lane.
//
// The perf-gate harness boots the service in dev mode against a throwaway
// postgres with auth disabled (quarkus.oidc.enabled=false, authz.enforce=false)
// and the messaging health check off (quarkus.messaging.health.enabled=false —
// SmallRye reports the Kafka channel readiness DOWN without a broker), so the
// read-only route below answers 200 on a fresh database.

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8109';

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
  group('agent proposals list read-only smoke', () => {
    // The FIRST BASE_URL request in this file is what .github/scripts/k6-smoke-path.py
    // extracts for perf-gate.yml's pre-flight probe — keep the proposals read first.
    const res = http.get(`${BASE_URL}/api/v1/proposals?state=pending`, {
      tags: { endpoint: 'proposals-list', service: 'agent-service' },
    });

    const is200 = res.status === 200;
    const isJson = res.headers['Content-Type'] && res.headers['Content-Type'].includes('application/json');
    okRate.add(is200 && isJson);

    check(res, {
      'proposals-list status is 200': (r) => r.status === 200,
      'proposals-list returns JSON':  (r) => isJson,
      // The HITL approval queue is a list; a fresh database yields an empty one.
      'proposals-list body is array': (r) => Array.isArray(JSON.parse(r.body)),
    });

    sleep(1);
  });
}
