// SPDX-License-Identifier: Apache-2.0
// k6 smoke test for openbank-transaction-service (ADR-0243 scope extension).
// Runs a short read-only load against the pending-approvals queue with lenient CI thresholds.
//
// A single GET lists pending approvals without posting payments or making approval
// decisions. Redis may prune expired entries from its pending index.
// The approvals queue is the one transaction read that
// answers 200 on a fresh database — the transaction rows themselves are id-scoped and
// 404 with no seeded fixtures, which the perf-gate pre-flight probe rightly refuses to
// time. The queue is also the operationally load-bearing read: without it a parked
// `transaction.reverse` four-eyes decision is invisible to the checker (issue #5679).
//
// The perf-gate harness boots the service in dev mode against a throwaway postgres with
// auth disabled (quarkus.oidc.enabled=false, authz.enforce=false) and the messaging
// health check off (quarkus.messaging.health.enabled=false — SmallRye reports the
// channel readiness DOWN without a broker), so the @RolesAllowed/@Authorize route below
// answers 200 with an empty queue on a fresh database. A declared quarkus.redis host
// gets a throwaway redis container — without it the Redis readiness check stays DOWN
// and the gate's readiness wait never goes green (measured on openbank-fx-service).

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8102';

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
  group('transaction approvals read-only smoke', () => {
    // The FIRST BASE_URL request in this file is what .github/scripts/k6-smoke-path.py
    // extracts for perf-gate.yml's pre-flight probe — keep the approvals list first.
    const res = http.get(`${BASE_URL}/api/v1/transactions/approvals`, {
      tags: { endpoint: 'transaction-approvals-list', service: 'transaction-service' },
    });

    const is200 = res.status === 200;
    const isJson = res.headers['Content-Type'] && res.headers['Content-Type'].includes('application/json');
    okRate.add(is200 && isJson);

    check(res, {
      'transaction-approvals-list status is 200': (r) => r.status === 200,
      'transaction-approvals-list returns JSON':  (r) => isJson,
      // An empty queue is a legitimate fresh-DB response; the shape is what matters.
      'transaction-approvals-list body is array': (r) => Array.isArray(JSON.parse(r.body)),
    });

    sleep(1);
  });
}
