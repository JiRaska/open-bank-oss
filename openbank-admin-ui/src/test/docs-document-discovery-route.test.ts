// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('next/headers', () => ({ cookies: vi.fn(async () => ({ get: () => undefined })) }))
vi.mock('@/lib/services/docs', () => ({
  DocsDiscoveryIncompleteError: class DocsDiscoveryIncompleteError extends Error {},
  loadDocsDocument: vi.fn(),
}))

import { loadDocsDocument } from '@/lib/services/docs'

describe('GET /api/services/[name]/docs/[slug]', () => {
  afterEach(() => { vi.resetModules(); vi.restoreAllMocks() })

  it('returns unavailable instead of a false missing-document 404 for incomplete discovery', async () => {
    const { DocsDiscoveryIncompleteError } = await import('@/lib/services/docs')
    vi.mocked(loadDocsDocument).mockRejectedValue(new DocsDiscoveryIncompleteError())
    const { GET } = await import('@/app/api/services/[name]/docs/[slug]/route')

    const response = await GET(
      new Request('http://localhost/api/services/account/docs/overview'),
      { params: Promise.resolve({ name: 'account', slug: 'overview' }) },
    )

    expect(response.status).toBe(503)
    expect(await response.json()).toEqual({ error: 'service discovery incomplete', service: 'account', slug: 'overview' })
  })
})
