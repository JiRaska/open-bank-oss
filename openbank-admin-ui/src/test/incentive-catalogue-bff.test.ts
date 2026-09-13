// SPDX-License-Identifier: Apache-2.0
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: async () => ({ user: { accessToken: 'token' } }) }))
vi.mock('@/lib/services/bff', () => ({
  serverSvcUrl: (_service: string, _name: string, _port: number, path: string) => `http://incentive${path}`,
}))

describe('incentive catalogue BFF', () => {
  beforeEach(() => vi.resetModules())

  it('forwards only the service catalogue envelope through authenticated BFF', async () => {
    const offer = { ref: { id: '0c42be3d-f632-4f12-bdb3-2e326a471a7f', name: 'welcome', version: 2 } }
    const fetchMock = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ items: [offer] }) }))
    vi.stubGlobal('fetch', fetchMock)

    const { GET } = await import('@/app/api/incentives/route')
    await expect((await GET()).json()).resolves.toEqual({ items: [offer], state: 'ok' })
    expect(fetchMock).toHaveBeenCalledWith(
      'http://incentive/api/v1/incentives/offers',
      expect.objectContaining({ headers: { authorization: 'Bearer token' } }),
    )
  })
})
