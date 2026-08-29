// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

// Read-only performance smoke for the control-plane findings query. The perf gate starts the
// service with OIDC disabled in its disposable dev stack; no operator credential or live finding
// data leaves the runner.
import http from 'k6/http';
import { check, group, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8148';

export const options = {
  vus: 5,
  duration: '30s',
  thresholds: {
    http_req_duration: ['p(95)<2000'],
    http_req_failed: ['rate<0.01'],
    checks: ['rate==1.0'],
  },
};

export default function () {
  group('flaky-test-hunter findings read-only smoke', () => {
    const res = http.get(`${BASE_URL}/api/v1/flaky-test-hunter/findings`, {
      tags: { endpoint: 'findings-list', service: 'flaky-test-hunter' },
    });

    check(res, {
      'findings-list status is 200': (r) => r.status === 200,
      'findings-list returns JSON': (r) =>
        r.headers['Content-Type'] && r.headers['Content-Type'].includes('application/json'),
      'findings-list body is array': (r) => Array.isArray(JSON.parse(r.body)),
    });

    sleep(1);
  });
}
