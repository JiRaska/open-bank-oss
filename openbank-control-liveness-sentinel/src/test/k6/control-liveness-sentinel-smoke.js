// SPDX-License-Identifier: Apache-2.0
// k6 smoke test for openbank-control-liveness-sentinel (ADR-0119 control
// liveness findings). Runs a short read-only load against the active-findings
// list with lenient CI thresholds.
//
// READ-ONLY BY CONSTRUCTION: a single GET against the findings list; nothing is
// created, mutated or deleted. A fresh database yields an empty list, which
// answers 200 — the list endpoint is a plain @RolesAllowed read, and the
// perf-gate harness boots with auth disabled, so it is permissive. The
// check/trigger POST starts a Temporal workflow (a write that needs the
// workflow server), and the id-scoped finding read is 404 with no seeded
// fixtures — both out of scope for this read-only lane.
//
// The perf-gate harness boots the service in dev mode against a throwaway
// postgres with auth disabled (quarkus.oidc.enabled=false, authz.enforce=false)
// and all devservices off (quarkus.devservices.enabled=false — this module
// carries quarkus-observability-devservices-lgtm and would otherwise pull the
// grafana/otel-lgtm image at boot), so the read-only route below answers 200 on
// a fresh database. The Temporal worker is disabled by default
// (openbank.temporal.enabled=false) and registers only when explicitly
// enabled, so no dev server is needed at boot; the scheduled check carries a
// never-firing cron in dev/test (0 0 5 31 2 ?), so no analysis runs during
// the 30 s smoke.

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8144';

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
  group('control-liveness-sentinel findings read-only smoke', () => {
    // The FIRST BASE_URL request in this file is what .github/scripts/k6-smoke-path.py
    // extracts for perf-gate.yml's pre-flight probe — keep the findings read first.
    const res = http.get(`${BASE_URL}/api/v1/liveness-sentinel/findings`, {
      tags: { endpoint: 'findings-list', service: 'control-liveness-sentinel' },
    });

    const is200 = res.status === 200;
    const isJson = res.headers['Content-Type'] && res.headers['Content-Type'].includes('application/json');
    okRate.add(is200 && isJson);

    check(res, {
      'findings-list status is 200': (r) => r.status === 200,
      'findings-list returns JSON':  (r) => isJson,
      // The liveness findings store is a list; a fresh database yields an empty one.
      'findings-list body is array': (r) => Array.isArray(JSON.parse(r.body)),
    });

    sleep(1);
  });
}
