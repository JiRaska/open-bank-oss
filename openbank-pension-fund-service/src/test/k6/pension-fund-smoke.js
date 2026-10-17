// SPDX-License-Identifier: Apache-2.0
// k6 smoke test for openbank-pension-fund-service (unit-order read lane).
// Runs a short read-only load against the per-contract order list with
// lenient CI thresholds.
//
// READ-ONLY BY CONSTRUCTION: a single GET against the order list; the
// all-zero (nil) UUID asks for a contract that cannot exist, and a fresh
// database yields an empty list, which answers 200 — the endpoint is a plain
// read, and the perf-gate harness boots with auth disabled (authz.enforce=false
// is the ADR-0034 D5 advisory mode), so it is permissive. The order placement
// is an investment write, NAV approve/reject mutate fund state, and the
// id-scoped NAV reads are 404 with no seeded fixtures — all out of scope for
// this read-only lane.
//
// The perf-gate harness boots the service in dev mode against a throwaway
// postgres with auth disabled (quarkus.oidc.enabled=false, authz.enforce=false)
// and the messaging health check off (quarkus.messaging.health.enabled=false),
// so the read-only route below answers 200 on a fresh database. The service
// declares no smallrye-kafka channels and no quarkus.redis hosts, so no
// broker provisioning is needed at boot.

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8162';

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
  group('pension-fund orders read-only smoke', () => {
    // The FIRST BASE_URL request in this file is what .github/scripts/k6-smoke-path.py
    // extracts for perf-gate.yml's pre-flight probe — keep the orders read first.
    // The nil UUID asks for a contract that cannot exist; the answer is an empty list, 200.
    const res = http.get(`${BASE_URL}/api/v1/contracts/00000000-0000-0000-0000-000000000000/orders`, {
      tags: { endpoint: 'orders-by-contract', service: 'pension-fund-service' },
    });

    const is200 = res.status === 200;
    const isJson = res.headers['Content-Type'] && res.headers['Content-Type'].includes('application/json');
    okRate.add(is200 && isJson);

    check(res, {
      'orders-by-contract status is 200': (r) => r.status === 200,
      'orders-by-contract returns JSON':  (r) => isJson,
      // The unit-order register is a list; a fresh database yields an empty one.
      'orders-by-contract body is array': (r) => Array.isArray(JSON.parse(r.body)),
    });

    sleep(1);
  });
}
