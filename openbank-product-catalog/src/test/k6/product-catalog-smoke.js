// SPDX-License-Identifier: Apache-2.0
// k6 smoke test for openbank-product-catalog (ADR-0243 pilot).
// Runs a short read-only load against /api/v1/products with lenient CI thresholds.

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8104';

// Advisory thresholds for the first pilot slice. These mirror the existing
// public synthetic test (p95 < 2s) and canary error gate (rate < 1%).
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
  group('product-catalog read-only smoke', () => {
    const res = http.get(`${BASE_URL}/api/v1/products`, {
      tags: { endpoint: 'products-list', service: 'product-catalog' },
    });

    const is200 = res.status === 200;
    const isJson = res.headers['Content-Type'] && res.headers['Content-Type'].includes('application/json');
    okRate.add(is200 && isJson);

    check(res, {
      'products-list status is 200': (r) => r.status === 200,
      'products-list returns JSON':  (r) => isJson,
      'products-list body is array': (r) => Array.isArray(JSON.parse(r.body)),
    });

    sleep(1);
  });
}
