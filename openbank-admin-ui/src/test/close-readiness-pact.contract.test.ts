// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import path from 'node:path'
import { PactV3, SpecificationVersion, MatchersV3 } from '@pact-foundation/pact'
import { afterEach, expect, it, vi } from 'vitest'
import { fetchCloseReadiness } from '@/lib/closings/evidence'
import { parseCloseReadiness } from '@/components/closings/CloseReadinessPanel'

afterEach(() => vi.unstubAllGlobals())

it('the real UI reader consumes retained-evidence gaps without inventing readiness', async () => {
  const actualFetch = globalThis.fetch
  const pact = new PactV3({
    consumer: 'openbank-admin-ui', provider: 'openbank-ledger-service',
    dir: path.resolve(process.cwd(), '../pacts'), host: '127.0.0.1',
    spec: SpecificationVersion.SPECIFICATION_VERSION_V3, logLevel: 'error',
  })
  await pact.given('ledger has no retained close evidence for February 2000')
    .uponReceiving('read a bounded historical month of close completeness evidence')
    .withRequest({ method: 'GET', path: '/api/v1/ledger/close-readiness', query: { month: '2000-02' } })
    .willRespondWith({
      status: 200, headers: { 'Content-Type': 'application/json' },
      body: {
        month: '2000-02', observedDays: 0, requiredDays: 29, eligibleForEnforcement: false,
        blockers: MatchersV3.eachLike('STALE_PERIOD'),
        days: Array.from({ length: 29 }, (_, index) => ({
          businessDate: `2000-02-${String(index + 1).padStart(2, '0')}`, blockers: ['NO_CLOSE_EVIDENCE'],
        })),
      },
    })
    .executeTest(async server => {
      // Substitute only the browser's same-origin BFF prefix. Path and query come from production code.
      vi.stubGlobal('fetch', (input: string, init?: RequestInit) => {
        const prefix = '/api/svc/ledger-service'
        expect(input.startsWith(`${prefix}/`)).toBe(true)
        return actualFetch(`${server.url}${input.slice(prefix.length)}`, init)
      })
      const response = await fetchCloseReadiness(AbortSignal.timeout(5000), '2000-02')
      const evidence = parseCloseReadiness(await response.json())
      expect(evidence?.eligibleForEnforcement).toBe(false)
      expect(evidence?.days).toHaveLength(29)
      expect(evidence?.days[0].blockers).toEqual(['NO_CLOSE_EVIDENCE'])
    })
})
