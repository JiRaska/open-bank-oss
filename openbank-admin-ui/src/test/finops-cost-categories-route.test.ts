// SPDX-License-Identifier: Apache-2.0
import { afterEach, expect, it, vi } from 'vitest'
import { promises as fs } from 'node:fs'
import { GET } from '@/app/api/finops/costs/route'

const readFile = fs.readFile.bind(fs)
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllEnvs() })

it('keeps AWS infrastructure categories separate from business ownership and preserves unknown spend', async () => {
  vi.stubEnv('OPENBANK_COST_REPORT', '/tmp/domain-cost-category-fixture.json')
  vi.stubEnv('OPENBANK_COST_REPORT_LIVE', '/tmp/domain-cost-category-missing.json')
  vi.spyOn(fs, 'readFile').mockImplementation(async (file, ...args) => {
    if (String(file) === '/tmp/domain-cost-category-missing.json') throw new Error('unavailable')
    if (String(file) === '/tmp/domain-cost-category-fixture.json') return JSON.stringify({ available: true, currency: 'USD', services: [
      { name: 'Amazon Elastic Compute Cloud - Compute', amount: 60 },
      { name: 'Unrecognized infrastructure service', amount: 30 },
      { name: 'AWS Key Management Service', amount: 10 },
    ] })
    return readFile(file, ...args)
  })
  const body = await (await GET()).json()
  expect(body.available).toBe(true)
  expect(body.total).toBe(100)
  expect(body.services).toEqual([
    { name: 'Amazon Elastic Compute Cloud - Compute', amount: 60, domain: 'Platform' },
    { name: 'Unrecognized infrastructure service', amount: 30, domain: 'Unclassified' },
    { name: 'AWS Key Management Service', amount: 10, domain: 'Security' },
  ])
})
