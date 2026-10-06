// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/lib/discovery', () => ({
  inCluster: vi.fn(),
  resolveInClusterBaseUrl: vi.fn(),
}))

import { inCluster, resolveInClusterBaseUrl } from '@/lib/discovery'
import { loadDocsIndex } from '@/lib/services/docs'

afterEach(() => {
  vi.resetAllMocks()
  vi.unstubAllGlobals()
})

describe('newly discovered service documentation', () => {
  it('reads a new service through a discovered URL', async () => {
    vi.mocked(inCluster).mockReturnValue(true)
    vi.mocked(resolveInClusterBaseUrl).mockImplementation(async name =>
      name === 'context-service' ? 'http://context-service.context.svc:8150' : null)
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      service: 'openbank-context-service', version: '0.3.0', available: true,
      gitCommit: 'abc123', items: [{ slug: '00-build', title: 'Build facts' }],
    }), { status: 200 }))
    vi.stubGlobal('fetch', fetch)

    const index = await loadDocsIndex('context')
    expect(resolveInClusterBaseUrl).toHaveBeenCalledWith('context-service')
    expect(fetch).toHaveBeenCalledWith(
      'http://context-service.context.svc:8150/q/openbank/docs?lang=en',
      expect.objectContaining({ cache: 'no-store' }),
    )
    expect(index).toMatchObject({ source: 'live', version: '0.3.0', gitCommit: 'abc123' })
  })

  it('does not compose an outbound URL for an unknown local service', async () => {
    vi.mocked(inCluster).mockReturnValue(false)
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    expect(await loadDocsIndex('unregistered-service')).toBeNull()
    expect(fetch).not.toHaveBeenCalled()
  })
})
