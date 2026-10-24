// SPDX-License-Identifier: Apache-2.0
// k6 smoke test for openbank-interest-service (ADR-0243 scope extension).
// Runs a short read-only load against the withholding-remittance list with lenient CI
// thresholds.
//
// READ-ONLY BY CONSTRUCTION: a single GET against the operator read path; nothing is
// created, mutated or deleted. The remittance-batch list is the one interest read that
// answers 200 on a fresh database — the accrual rows are id-scoped
// (/api/v1/interest/accruals/{accountId}) and the remittance detail is keyed by
// year/month and 404s with no seeded batches, which the perf-gate pre-flight probe
// rightly refuses to time.
//
// The perf-gate harness boots the service in dev mode against a throwaway postgres with
// auth disabled (quarkus.oidc.enabled=false, authz.enforce=false) and the messaging
// health check off (quarkus.messaging.health.enabled=false — SmallRye reports the
// channel readiness DOWN without a broker), so the @RolesAllowed/@Authorize route below
// answers 200 with an empty list on a fresh database. A declared quarkus.redis host
// gets a throwaway redis container — without it the Redis readiness check stays DOWN
// and the gate's readiness wait never goes green (measured on openbank-fx-service).

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8125';

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
  group('interest remittances read-only smoke', () => {
    // The FIRST BASE_URL request in this file is what .github/scripts/k6-smoke-path.py
    // extracts for perf-gate.yml's pre-flight probe — keep the remittance list first.
    const res = http.get(`${BASE_URL}/api/v1/interest/withholding/remittances`, {
      tags: { endpoint: 'interest-remittances-list', service: 'interest-service' },
    });

    const is200 = res.status === 200;
    const isJson = res.headers['Content-Type'] && res.headers['Content-Type'].includes('application/json');
    okRate.add(is200 && isJson);

    check(res, {
      'interest-remittances-list status is 200': (r) => r.status === 200,
      'interest-remittances-list returns JSON':  (r) => isJson,
      // An empty batch list is a legitimate fresh-DB response; the shape is what matters.
      'interest-remittances-list body is array': (r) => Array.isArray(JSON.parse(r.body)),
    });

    sleep(1);
  });
}
