// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/lib/discovery', () => ({
  inCluster: vi.fn(),
  resolveInClusterService: vi.fn(),
}))

import { inCluster, resolveInClusterService } from '@/lib/discovery'
import { loadDocsIndex } from '@/lib/services/docs'

afterEach(() => {
  vi.resetAllMocks()
  vi.unstubAllGlobals()
})

describe('newly discovered service documentation', () => {
  it('reads a new service through a discovered URL', async () => {
    vi.mocked(inCluster).mockReturnValue(true)
    vi.mocked(resolveInClusterService).mockImplementation(async name => name === 'context-service'
      ? { state: 'found', service: { name, namespace: 'context', port: 8150, group: 'platform', ready: true, replicas: 1, readyReplicas: 1, scaledToZero: false }, baseUrl: 'http://context-service.context.svc:8150', coverage: 'partial' }
      : { state: 'not_found', coverage: 'complete' })
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      service: 'openbank-context-service', version: '0.3.0', available: true,
      gitCommit: 'abc123', items: [{ slug: '00-build', title: 'Build facts' }],
    }), { status: 200 }))
    vi.stubGlobal('fetch', fetch)

    const index = await loadDocsIndex('context')
    expect(resolveInClusterService).toHaveBeenCalledWith('context-service')
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

  it('does not turn incomplete discovery into a missing-docs result', async () => {
    vi.mocked(inCluster).mockReturnValue(true)
    vi.mocked(resolveInClusterService).mockResolvedValue({ state: 'unknown', coverage: 'partial' })
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)

    await expect(loadDocsIndex('context')).rejects.toMatchObject({ name: 'DocsDiscoveryIncompleteError' })
    expect(fetch).not.toHaveBeenCalled()
  })
})
