// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest'
import { GET } from '@/app/api/finops/resources/route'
afterEach(() => vi.unstubAllGlobals())
function mockMetrics(used: string | null, failed = false) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    if (url.endsWith('/-/ready')) return new Response('ok')
    const query = new URL(url).searchParams.get('query') ?? ''
    if (query.includes('jvm_memory_used') && failed) return new Response('', { status: 503 })
    const value = query.includes('jvm_memory_max') ? '100' : query.includes('jvm_memory_used') ? used : null
    return new Response(JSON.stringify({ status: 'success', data: { result: value === null ? []
      : [{ metric: { container: 'example-service' }, value: [1, value] }] } }))
  }))
}
describe('FinOps resource observations', () => {
  it.each([['failed query', null, true], ['missing series', null, false], ['non-finite sample', '+Inf', false]] as const)(
    'does not invent zero utilisation from %s', async (_name, used, failed) => {
      mockMetrics(used, failed)
      const body = await (await GET()).json()
      expect(body.services[0].heap).toEqual({ usedBytes: null, maxBytes: 100, pct: null })
      expect(body.services[0].efficiency).toBe('unknown')
      expect(body.fleetHeapPct).toBeNull()
      expect(body.underutilisedCount).toBe(0)
      expect(body.degraded).toBe(failed)
    },
  )
  it('preserves a measured zero as real evidence', async () => {
    mockMetrics('0')
    const body = await (await GET()).json()
    expect(body.services[0].heap.pct).toBe(0)
    expect(body.fleetHeapPct).toBe(0)
    expect(body.underutilisedCount).toBe(1)
  })
})
