// SPDX-License-Identifier: Apache-2.0
// k6 smoke test for openbank-ledger-service (ADR-0243 scope extension).
// Runs a short read-only load against the current-accounting-day read with lenient CI
// thresholds.
//
// READ-ONLY BY CONSTRUCTION: a single GET against the operator read path; nothing is
// created, mutated or deleted. The current-day read is the one ledger endpoint that
// answers 200 on a fresh database — it is derived from the business-date authority
// (ADR-0207 D1), not from seeded rows: measured on an empty database the authority
// auto-opens the day (`status:"OPEN", opened:true`), and had it not, `opened=false`
// with a null status would be equally legitimate. The other candidate, the day list,
// 400s without from/to filters (a fixture in disguise), and the day detail is
// id-scoped and 404s empty — the perf-gate pre-flight probe rightly refuses to time
// either.
//
// The perf-gate harness boots the service in dev mode against a throwaway postgres with
// auth disabled (quarkus.oidc.enabled=false, authz.enforce=false) and the messaging
// health check off (quarkus.messaging.health.enabled=false — SmallRye reports the
// outgoing-channel readiness DOWN without a broker), so the @RolesAllowed/@Authorize
// route below answers 200 on a fresh database. A declared quarkus.redis host gets a
// throwaway redis container — without it the Redis readiness check stays DOWN and the
// gate's readiness wait never goes green (measured on openbank-fx-service).

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8101';

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
  group('ledger current-day read-only smoke', () => {
    // The FIRST BASE_URL request in this file is what .github/scripts/k6-smoke-path.py
    // extracts for perf-gate.yml's pre-flight probe — keep the current-day read first.
    const res = http.get(`${BASE_URL}/api/v1/ledger/accounting-days/current`, {
      tags: { endpoint: 'ledger-current-day', service: 'ledger-service' },
    });

    const is200 = res.status === 200;
    const isJson = res.headers['Content-Type'] && res.headers['Content-Type'].includes('application/json');
    okRate.add(is200 && isJson);

    check(res, {
      'ledger-current-day status is 200': (r) => r.status === 200,
      'ledger-current-day returns JSON':  (r) => isJson,
      // Measured on a fresh database: the business-date authority auto-opens the day, so
      // the body reads {"businessDate":"…","status":"OPEN","opened":true}; had the open
      // not happened, opened=false with a null status is equally legitimate. The
      // businessDate field is what proves the authority path ran — the shape is not.
      'ledger-current-day has businessDate': (r) => 'businessDate' in JSON.parse(r.body),
    });

    sleep(1);
  });
}
