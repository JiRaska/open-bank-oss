// SPDX-License-Identifier: Apache-2.0
// k6 smoke test for openbank-treasury-service (dealer blotter read lane).
// Runs a short read-only load against the deals list with lenient CI
// thresholds.
//
// READ-ONLY BY CONSTRUCTION: a single GET against the dealer blotter; nothing
// is created, mutated or deleted. A fresh database yields an empty list, which
// answers 200 — the list endpoint is a plain read, and the perf-gate harness
// boots with auth disabled (authz.enforce=false is the ADR-0034 D5 advisory
// mode), so it is permissive. Deal create/submit/cancel/approve/override-limit/
// reject/confirm/settle are money-path writes, the id-scoped deal read is 404
// with no seeded fixtures, and the synthetic quote read needs valid
// product/currency/tenor query parameters — all out of scope for this
// read-only lane.
//
// The perf-gate harness boots the service in dev mode against a throwaway
// postgres with auth disabled (quarkus.oidc.enabled=false, authz.enforce=false)
// and the messaging health check off (quarkus.messaging.health.enabled=false),
// so the read-only route below answers 200 on a fresh database. Kafka carries
// an explicit bootstrap-servers address in config, so no dev-services image
// is pulled at boot; the simulated market data feed is an in-process generator
// (openbank.treasury.simulated-market.enabled defaults true).

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8160';

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
  group('treasury deals read-only smoke', () => {
    // The FIRST BASE_URL request in this file is what .github/scripts/k6-smoke-path.py
    // extracts for perf-gate.yml's pre-flight probe — keep the deals read first.
    const res = http.get(`${BASE_URL}/api/v1/treasury/deals`, {
      tags: { endpoint: 'deals-list', service: 'treasury-service' },
    });

    const is200 = res.status === 200;
    const isJson = res.headers['Content-Type'] && res.headers['Content-Type'].includes('application/json');
    okRate.add(is200 && isJson);

    check(res, {
      'deals-list status is 200': (r) => r.status === 200,
      'deals-list returns JSON':  (r) => isJson,
      // The dealer blotter is a list; a fresh database yields an empty one.
      'deals-list body is array': (r) => Array.isArray(JSON.parse(r.body)),
    });

    sleep(1);
  });
}
