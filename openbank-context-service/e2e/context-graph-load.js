// SPDX-License-Identifier: Apache-2.0
import http from 'k6/http'
import { check } from 'k6'

export const options = {
  scenarios: {
    authorized_reads: {
      executor: 'constant-arrival-rate',
      rate: 100,
      timeUnit: '1s',
      duration: '5m',
      preAllocatedVUs: 50,
      maxVUs: 100,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<300', 'p(99)<1000'],
  },
}

const required = name => {
  const value = __ENV[name]
  if (!value) throw new Error(`${name} is required`)
  return value
}

export default function () {
  const response = http.get(
    `${required('CONTEXT_BASE_URL')}/api/v1/context/complaints/${encodeURIComponent(required('COMPLAINT_REFERENCE'))}`,
    {
      headers: {
        Authorization: `Bearer ${required('ACCESS_TOKEN')}`,
        'X-Investigation-Case-Id': required('CASE_ID'),
        'X-Investigation-Purpose': 'PAYMENT_COMPLAINT',
      },
      timeout: '2s',
      tags: { lens: 'complaint' },
    },
  )
  check(response, { 'authorized bounded graph returned': result => result.status === 200 })
}
