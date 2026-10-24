// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it, vi } from 'vitest'

vi.mock('@/lib/discovery', () => ({
  discoverServicesSnapshot: vi.fn(),
}))

import { discoverServicesSnapshot } from '@/lib/discovery'

describe('GET /api/services/discovery coverage contract', () => {
  it.each(['partial', 'unavailable'] as const)(
    'marks %s reads as incomplete instead of a verified empty cluster',
    async (status) => {
      vi.mocked(discoverServicesSnapshot).mockResolvedValue({ status, services: [] })
      const { GET } = await import('@/app/api/services/discovery/route')
      const body = await (await GET()).json()
      expect(body).toEqual({ source: 'k8s', services: [], discoveryStatus: status })
    },
  )

  it('marks a verified empty response separately from an incomplete read', async () => {
    vi.mocked(discoverServicesSnapshot).mockResolvedValue({ status: 'complete', services: [] })
    const { GET } = await import('@/app/api/services/discovery/route')
    const body = await (await GET()).json()
    expect(body).toEqual({ source: 'k8s', services: [], discoveryStatus: 'complete' })
  })
})
