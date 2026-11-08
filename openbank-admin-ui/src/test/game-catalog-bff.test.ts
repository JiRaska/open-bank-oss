// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { NextRequest } from 'next/server'
import { hasPermission, permissionForPath } from '@/lib/auth/roles'

const permission = vi.hoisted(() => vi.fn())
const actor = vi.hoisted(() => vi.fn())
vi.mock('@/lib/auth/api-permission', () => ({ requireApiPermission: permission }))
vi.mock('@/auth', () => ({ auth: actor }))
beforeEach(() => actor.mockResolvedValue({ user: { accessToken: 'synthetic-test-token', roles: ['ROLE_ADMIN'] } }))
vi.mock('@/lib/services/bff', () => ({
  serverSvcUrl: (_service: string, _namespace: string, _port: number, path: string) => `https://engagement.test${path}`,
}))
afterEach(() => {
  vi.unstubAllGlobals()
  permission.mockReset()
})

describe('game catalogue BFF', () => {
  it('has a distinct read permission and admin-only writes', () => {
    expect(permissionForPath('/loyalty/game')).toBe('game:view')
    expect(hasPermission(['ROLE_OPERATOR'], 'game:view')).toBe(true)
    expect(hasPermission(['ROLE_OPERATOR'], 'game:manage')).toBe(false)
    expect(hasPermission(['ROLE_ADMIN'], 'game:manage')).toBe(true)
    expect(hasPermission(['ROLE_MARKETING'], 'game:edit')).toBe(true)
    expect(hasPermission(['ROLE_MARKETING'], 'game:manage')).toBe(false)
  })
  it('denies a direct write before any upstream request', async () => {
    permission.mockResolvedValue({ ok: false, status: 403, error: 'forbidden' })
    const fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    const { PUT } = await import('@/app/api/game/catalog/route')
    expect(
      (await PUT(new NextRequest('https://admin.test/api/game/catalog', { method: 'PUT', body: '{}' }))).status,
    ).toBe(403)
    expect(permission).toHaveBeenCalledWith('game:edit')
    expect(fetcher).not.toHaveBeenCalled()
  })
  it('preserves revision conflicts without exposing upstream error text', async () => {
    permission.mockResolvedValue({ ok: true })
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('internal-stack', { status: 409 })),
    )
    const { PUT } = await import('@/app/api/game/catalog/route')
    const response = await PUT(
      new NextRequest('https://admin.test/api/game/catalog', { method: 'PUT', body: '{"revision":2,"levels":[]}' }),
    )
    expect(response.status).toBe(409)
    expect(await response.json()).toEqual({ error: 'catalogue_changed' })
  })
  it('distinguishes unavailable service from a genuinely empty catalogue', async () => {
    permission.mockResolvedValue({ ok: true })
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('private', { status: 500 })),
    )
    const { GET } = await import('@/app/api/game/catalog/route')
    const response = await GET()
    expect(response.status).toBe(503)
    expect(await response.json()).toEqual({ error: 'catalogue_unavailable' })
  })
  it('relays a marketing author to the draft-only endpoint with their own bearer', async () => {
    permission.mockResolvedValue({ ok: true })
    actor.mockResolvedValue({ user: { accessToken: 'synthetic-test-token', roles: ['ROLE_MARKETING'] } })
    const fetcher = vi.fn(async (_url: string, _init: RequestInit) => Response.json({ revision: 1, levels: [] }))
    vi.stubGlobal('fetch', fetcher)
    const { PUT } = await import('@/app/api/game/catalog/route')
    const response = await PUT(
      new NextRequest('https://admin.test/api/game/catalog', { method: 'PUT', body: '{"revision":0,"levels":[]}' }),
    )
    expect(response.status).toBe(200)
    expect(fetcher.mock.calls[0][0]).toBe('https://engagement.test/api/v1/game/admin/drafts')
    expect(new Headers(fetcher.mock.calls[0][1].headers).get('authorization')).toBe('Bearer synthetic-test-token')
  })
  it('does not relay any request without an operator bearer', async () => {
    permission.mockResolvedValue({ ok: true })
    actor.mockResolvedValue(null)
    const fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    const { GET } = await import('@/app/api/game/catalog/route')
    expect((await GET()).status).toBe(401)
    expect(fetcher).not.toHaveBeenCalled()
  })
})
