// SPDX-License-Identifier: Apache-2.0
// k6 smoke test for openbank-kyc-service (ADR-0243 scope extension).
// Runs a short read-only load against the KYC case list with lenient CI thresholds.
//
// READ-ONLY BY CONSTRUCTION: a single GET against the operator read path; nothing is
// created, mutated or deleted. The case list is the one KYC read that answers 200 on a
// fresh database (an empty first page with total=0) — the individual cases are
// id-scoped (/api/v1/kyc/cases/{id}) and 404 with no seeded fixtures, which the
// perf-gate pre-flight probe rightly refuses to time.
//
// The perf-gate harness boots the service in dev mode against a throwaway postgres with
// auth disabled (quarkus.oidc.enabled=false, authz.enforce=false) and the messaging
// health check off (quarkus.messaging.health.enabled=false — SmallRye reports the
// channel readiness DOWN without a broker), so the @RolesAllowed route below answers
// 200 with an empty page on a fresh database. A declared quarkus.redis host gets a
// throwaway redis container — without it the Redis readiness check stays DOWN and the
// gate's readiness wait never goes green (measured on openbank-fx-service).

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8114';

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
  group('kyc cases read-only smoke', () => {
    // The FIRST BASE_URL request in this file is what .github/scripts/k6-smoke-path.py
    // extracts for perf-gate.yml's pre-flight probe — keep the case list first.
    const res = http.get(`${BASE_URL}/api/v1/kyc/cases`, {
      tags: { endpoint: 'kyc-cases-list', service: 'kyc-service' },
    });

    const is200 = res.status === 200;
    const isJson = res.headers['Content-Type'] && res.headers['Content-Type'].includes('application/json');
    okRate.add(is200 && isJson);

    check(res, {
      'kyc-cases-list status is 200': (r) => r.status === 200,
      'kyc-cases-list returns JSON':  (r) => isJson,
      // An empty first page (items=[], total=0) is the legitimate fresh-DB response;
      // the page envelope is what matters — it is what a caller paginates against.
      'kyc-cases-list body has items array': (r) => Array.isArray(JSON.parse(r.body).items),
      'kyc-cases-list body has total':       (r) => typeof JSON.parse(r.body).total === 'number',
    });

    sleep(1);
  });
}
