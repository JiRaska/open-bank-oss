// SPDX-License-Identifier: Apache-2.0
// k6 smoke test for openbank-loyalty-service (ADR-0243 scope extension).
// Runs a short read-only load against the provisioning summary with lenient CI
// thresholds.
//
// READ-ONLY BY CONSTRUCTION: a single GET against the operator read path; nothing is
// created, mutated or deleted. The provisioning summary is the one loyalty read that
// answers 200 on a fresh database (no path or query parameters) — the grant/summary
// reads are party-scoped and 404 with no seeded fixtures, which the perf-gate
// pre-flight probe rightly refuses to time.
//
// The perf-gate harness boots the service in dev mode against a throwaway postgres with
// auth disabled (quarkus.oidc.enabled=false, authz.enforce=false — the route carries
// @RolesAllowed, which the unauthenticated harness must not enforce), so the route
// below answers 200 with zeroed outstanding leaves on a fresh database.

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8157';

// Advisory thresholds, mirroring the product-catalog pilot slice: the public synthetic
// test budget (p95 < 2s) and the canary error gate (rate < 1%).
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
  group('loyalty provisioning read-only smoke', () => {
    // The FIRST BASE_URL request in this file is what .github/scripts/k6-smoke-path.py
    // extracts for perf-gate.yml's pre-flight probe — keep the provisioning summary first.
    const res = http.get(`${BASE_URL}/api/v1/loyalty/provisioning`, {
      tags: { endpoint: 'provisioning-summary', service: 'loyalty-service' },
    });

    const is200 = res.status === 200;
    const isJson = res.headers['Content-Type'] && res.headers['Content-Type'].includes('application/json');
    okRate.add(is200 && isJson);

    check(res, {
      'provisioning-summary status is 200': (r) => r.status === 200,
      'provisioning-summary returns JSON':  (r) => isJson,
      // The payload is a keyed object (at/outstandingLeaves/cap/version).
      'provisioning-summary body is object': (r) => typeof JSON.parse(r.body) === 'object' && !Array.isArray(JSON.parse(r.body)),
    });

    sleep(1);
  });
}
