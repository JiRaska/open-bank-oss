// SPDX-License-Identifier: Apache-2.0
// k6 smoke test for openbank-statement-service (ADR-0069 close-run telemetry).
// Runs a short read-only load against the recent close-runs list with lenient
// CI thresholds.
//
// READ-ONLY BY CONSTRUCTION: a single GET against the close-runs list; nothing
// is created, mutated or deleted. A fresh database yields an empty list, which
// answers 200 — the list endpoint is a plain @RolesAllowed read, and the
// perf-gate harness boots with auth disabled, so it is permissive. The per-run
// failures read is id-scoped (404 with no seeded fixtures), the account
// statement list is id-scoped, and close/restate are money-path writes — all
// out of scope for this read-only lane.
//
// The perf-gate harness boots the service in dev mode against a throwaway
// postgres with auth disabled (quarkus.oidc.enabled=false, authz.enforce=false)
// and the messaging health check off (quarkus.messaging.health.enabled=false —
// SmallRye reports the Kafka channel readiness DOWN without a broker), so the
// read-only route below answers 200 on a fresh database. Kafka dev-services
// are switched off for this subject (no bootstrap-servers in config); the
// Temporal worker is disabled by default (openbank.temporal.enabled=false).

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8136';

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
  group('statement close-runs read-only smoke', () => {
    // The FIRST BASE_URL request in this file is what .github/scripts/k6-smoke-path.py
    // extracts for perf-gate.yml's pre-flight probe — keep the close-runs read first.
    const res = http.get(`${BASE_URL}/api/v1/statements/close-runs`, {
      tags: { endpoint: 'close-runs-list', service: 'statement-service' },
    });

    const is200 = res.status === 200;
    const isJson = res.headers['Content-Type'] && res.headers['Content-Type'].includes('application/json');
    okRate.add(is200 && isJson);

    check(res, {
      'close-runs-list status is 200': (r) => r.status === 200,
      'close-runs-list returns JSON':  (r) => isJson,
      // The close-run telemetry store is a list; a fresh database yields an empty one.
      'close-runs-list body is array': (r) => Array.isArray(JSON.parse(r.body)),
    });

    sleep(1);
  });
}
