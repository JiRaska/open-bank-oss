// SPDX-License-Identifier: Apache-2.0
// k6 smoke test for openbank-tpp-registry-service (PSD2 TPP register read
// lane). Runs a short read-only load against the TPP list with lenient CI
// thresholds.
//
// READ-ONLY BY CONSTRUCTION: a single GET against the TPP register list; all
// query parameters are optional, and a fresh database yields an empty page,
// which answers 200 — the list endpoint is a plain @RolesAllowed read, and the
// perf-gate harness boots with auth disabled, so it is permissive. The
// check/sync/blacklist endpoints mutate registry state (the EBA sync is an
// outbound fetch + upsert), and the id-scoped TPP read 404s with no seeded
// fixtures — all out of scope for this read-only lane.
//
// The perf-gate harness boots the service in dev mode against a throwaway
// postgres (+ Redis, which application.yaml declares via quarkus.redis.hosts)
// with auth disabled (quarkus.oidc.enabled=false, authz.enforce=false) and
// the messaging health check off (quarkus.messaging.health.enabled=false),
// so the read-only route below answers 200 on a fresh database. Kafka carries
// an explicit bootstrap-servers address in config, so no dev-services image is
// pulled at boot.

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8108';

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
  group('tpp-registry tpps read-only smoke', () => {
    // The FIRST BASE_URL request in this file is what .github/scripts/k6-smoke-path.py
    // extracts for perf-gate.yml's pre-flight probe — keep the tpps read first.
    const res = http.get(`${BASE_URL}/api/v1/tpp-registry`, {
      tags: { endpoint: 'tpps-list', service: 'tpp-registry-service' },
    });

    const is200 = res.status === 200;
    const isJson = res.headers['Content-Type'] && res.headers['Content-Type'].includes('application/json');
    okRate.add(is200 && isJson);

    check(res, {
      'tpps-list status is 200': (r) => r.status === 200,
      'tpps-list returns JSON':  (r) => isJson,
      // The register list wraps its rows plus a count ({ tpps, count }).
      'tpps-list body is object': (r) => typeof JSON.parse(r.body) === 'object' && !Array.isArray(JSON.parse(r.body)),
    });

    sleep(1);
  });
}
