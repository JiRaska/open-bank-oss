// SPDX-License-Identifier: Apache-2.0
// k6 smoke test for openbank-card-processing-service (authorisation read lane).
// Runs a short read-only load against the per-card authorisation list with
// lenient CI thresholds.
//
// READ-ONLY BY CONSTRUCTION: a single GET against the authorisation list; the
// all-zero (nil) UUID asks for a card that cannot exist, and a fresh database
// yields an empty page, which answers 200 — the endpoint is a plain read, and
// the perf-gate harness boots with auth disabled (authz.enforce=false is the
// ADR-0034 D5 advisory mode), so it is permissive. The authorize endpoint is a
// money-path write (decides, records and holds), clearing/reversal mutate
// state, the id-scoped authorisation read is 404 with no seeded fixtures, and
// the sandbox acquirer purchase is a payments write — all out of scope for
// this read-only lane.
//
// The perf-gate harness boots the service in dev mode against a throwaway
// postgres with auth disabled (quarkus.oidc.enabled=false, authz.enforce=false)
// and the messaging health check off (quarkus.messaging.health.enabled=false),
// so the read-only route below answers 200 on a fresh database. Kafka carries
// an explicit bootstrap-servers address in config, so no dev-services image is
// pulled at boot; the sandbox acquirer is an in-process stub.

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8157';

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
  group('card-processing authorisations read-only smoke', () => {
    // The FIRST BASE_URL request in this file is what .github/scripts/k6-smoke-path.py
    // extracts for perf-gate.yml's pre-flight probe — keep the authorisations read first.
    // The nil UUID asks for a card that cannot exist; the answer is an empty page, 200.
    const res = http.get(`${BASE_URL}/api/v1/card-authorizations/card/00000000-0000-0000-0000-000000000000`, {
      tags: { endpoint: 'authorizations-by-card', service: 'card-processing-service' },
    });

    const is200 = res.status === 200;
    const isJson = res.headers['Content-Type'] && res.headers['Content-Type'].includes('application/json');
    okRate.add(is200 && isJson);

    check(res, {
      'authorizations-by-card status is 200': (r) => r.status === 200,
      'authorizations-by-card returns JSON':  (r) => isJson,
      // The list response wraps its rows plus a size counter (AuthorizationListResponse).
      'authorizations-by-card body is object': (r) => typeof JSON.parse(r.body) === 'object' && !Array.isArray(JSON.parse(r.body)),
    });

    sleep(1);
  });
}
