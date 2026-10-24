// SPDX-License-Identifier: Apache-2.0
// k6 smoke test for openbank-pid-service (ADR-0243 scope extension).
// Runs a short read-only load against the OpenID4VCI issuer metadata with lenient CI
// thresholds.
//
// READ-ONLY BY CONSTRUCTION: a single GET against the public wallet-facing metadata
// path; nothing is created, mutated or deleted. The issuer metadata is the one PID read
// that answers 200 on a fresh database — it is @PermitAll (a wallet reads it before it
// has any credentials) and its JWKS degrades to an empty key set when no issuer key is
// configured. The issuance endpoints (credential-offers/token/credential) are
// wallet-session writes, and the status-list read is id-scoped and 404 with no seeded
// fixtures, which the perf-gate pre-flight probe rightly refuses to time.
//
// The perf-gate harness boots the service in dev mode against a throwaway postgres with
// auth disabled (quarkus.oidc.enabled=false, authz.enforce=false) and the messaging
// health check off (quarkus.messaging.health.enabled=false — SmallRye reports the
// channel readiness DOWN without a broker), so the public route below answers 200 on a
// fresh database.

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8105';

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
  group('pid issuer metadata read-only smoke', () => {
    // The FIRST BASE_URL request in this file is what .github/scripts/k6-smoke-path.py
    // extracts for perf-gate.yml's pre-flight probe — keep the metadata read first.
    const res = http.get(`${BASE_URL}/.well-known/openid-credential-issuer`, {
      tags: { endpoint: 'issuer-metadata', service: 'pid-service' },
    });

    const is200 = res.status === 200;
    const isJson = res.headers['Content-Type'] && res.headers['Content-Type'].includes('application/json');
    okRate.add(is200 && isJson);

    check(res, {
      'issuer-metadata status is 200': (r) => r.status === 200,
      'issuer-metadata returns JSON':  (r) => isJson,
      // OpenID4VCI metadata is a keyed object (credential_issuer/endpoints/jwks/…).
      'issuer-metadata body is object': (r) => typeof JSON.parse(r.body) === 'object' && !Array.isArray(JSON.parse(r.body)),
    });

    sleep(1);
  });
}
