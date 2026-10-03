// SPDX-License-Identifier: Apache-2.0
// Run only against a synthetic, role- and assignment-enabled isolated environment.
// Required fixture example: [{"loanId":"...","currency":"EUR","partial":true},
//                            {"loanId":"...","currency":"GBP","partial":false}]
import http from 'k6/http'
import { check, fail } from 'k6'

const required = name => {
  const value = __ENV[name]
  if (!value) throw new Error(`${name} is required`)
  return value
}

const contextBase = required('CONTEXT_BASE_URL').replace(/\/$/, '')
const lendingBase = required('LENDING_BASE_URL').replace(/\/$/, '')
const token = required('ACCESS_TOKEN')
const guarantorId = required('EXPECTED_SHARED_GUARANTOR_ID')
const baseRps = Number(required('BASE_RPS'))
const fixture = JSON.parse(required('LENDING_CASES_JSON'))

if (!Number.isInteger(baseRps) || baseRps < 1 || baseRps > 100) {
  throw new Error('BASE_RPS must be an integer from 1 to 100')
}
if (!Array.isArray(fixture) || fixture.length < 2 ||
    !fixture.every(item => item.loanId && item.currency && typeof item.partial === 'boolean') ||
    new Set(fixture.map(item => item.currency)).size < 2 ||
    !fixture.some(item => item.partial)) {
  throw new Error('LENDING_CASES_JSON needs at least two loan IDs, two currencies and a partial guarantee')
}

export const options = {
  scenarios: {
    baseline: {
      executor: 'constant-arrival-rate',
      rate: baseRps,
      timeUnit: '1s',
      duration: '3m',
      preAllocatedVUs: Math.max(20, baseRps),
      maxVUs: Math.max(100, baseRps * 3),
      tags: { phase: 'baseline' },
    },
    tenfold: {
      executor: 'constant-arrival-rate',
      startTime: '3m',
      rate: baseRps * 10,
      timeUnit: '1s',
      duration: '3m',
      preAllocatedVUs: Math.max(50, baseRps * 10),
      maxVUs: Math.max(200, baseRps * 30),
      tags: { phase: 'tenfold' },
    },
  },
  thresholds: {
    'http_req_failed{phase:baseline}': ['rate<0.01'],
    'http_req_failed{phase:tenfold}': ['rate<0.01'],
    'checks{phase:baseline}': ['rate==1'],
    'checks{phase:tenfold}': ['rate==1'],
    'http_req_duration{phase:baseline}': ['p(95)<300', 'p(99)<1000'],
    'http_req_duration{phase:tenfold}': ['p(95)<300', 'p(99)<1000'],
    'dropped_iterations{phase:baseline}': ['count==0'],
    'dropped_iterations{phase:tenfold}': ['count==0'],
  },
}

export default function () {
  const selected = fixture[(__ITER + __VU) % fixture.length]
  const headers = {
    Authorization: `Bearer ${token}`,
    'X-Investigation-Case-Id': selected.loanId,
    'X-Investigation-Purpose': 'LENDING_EXPOSURE_REVIEW',
  }
  const access = http.get(`${contextBase}/api/v1/context/lending-loans/${selected.loanId}/access`, {
    headers,
    timeout: '2s',
    tags: { operation: 'context-access' },
  })
  if (!check(access, { 'live case access granted': response => response.status === 204 })) {
    fail(`Context access failed with HTTP ${access.status}`)
  }

  const history = http.get(`${lendingBase}/api/v1/lending/graph/loans/${selected.loanId}/approved-guarantees`, {
    headers,
    timeout: '2s',
    tags: { operation: 'source-history' },
  })
  const ok = check(history, {
    'bounded source history returned': response => response.status === 200,
    'shared guarantor evidence present': response => {
      if (response.status !== 200) return false
      const body = response.json()
      return body.truncated === false &&
        body.guarantees.length > 0 && body.guarantees.length <= 100 &&
        body.guarantees.some(item => item.guarantorPartyId === guarantorId && item.currency === selected.currency)
    },
    'partial guarantee evidenced where expected': response => {
      if (!selected.partial) return true
      if (response.status !== 200) return false
      return response.json().guarantees.some(item => Number(item.coverageFraction) > 0 &&
        Number(item.coverageFraction) < 1)
    },
  })
  if (!ok) fail(`Lending source history failed fixture checks with HTTP ${history.status}`)
}
