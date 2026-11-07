// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest'
import { NextRequest } from 'next/server'
import { hasPermission, permissionForPath } from '@/lib/auth/roles'

const permission = vi.hoisted(() => vi.fn())
vi.mock('@/lib/auth/api-permission', () => ({ requireApiPermission: permission }))
vi.mock('@/auth', () => ({ auth: async () => ({ user: { accessToken: 'synthetic-test-token' } }) }))
vi.mock('@/lib/services/bff', () => ({ serverSvcUrl: () => 'https://engagement.test/api/v1/game/admin/catalog' }))
afterEach(() => { vi.unstubAllGlobals(); permission.mockReset() })

describe('game catalogue BFF', () => {
  it('has a distinct read permission and admin-only writes', () => {
    expect(permissionForPath('/loyalty/game')).toBe('game:view')
    expect(hasPermission(['ROLE_OPERATOR'], 'game:view')).toBe(true)
    expect(hasPermission(['ROLE_OPERATOR'], 'game:manage')).toBe(false)
    expect(hasPermission(['ROLE_ADMIN'], 'game:manage')).toBe(true)
  })
  it('denies a direct write before any upstream request', async () => {
    permission.mockResolvedValue({ ok: false, status: 403, error: 'forbidden' })
    const fetcher = vi.fn(); vi.stubGlobal('fetch', fetcher)
    const { PUT } = await import('@/app/api/game/catalog/route')
    expect((await PUT(new NextRequest('https://admin.test/api/game/catalog', { method: 'PUT', body: '{}' }))).status).toBe(403)
    expect(permission).toHaveBeenCalledWith('game:manage')
    expect(fetcher).not.toHaveBeenCalled()
  })
  it('preserves revision conflicts without exposing upstream error text', async () => {
    permission.mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', vi.fn(async () => new Response('internal-stack', { status: 409 })))
    const { PUT } = await import('@/app/api/game/catalog/route')
    const response = await PUT(new NextRequest('https://admin.test/api/game/catalog', { method: 'PUT', body: '{"revision":2,"levels":[]}' }))
    expect(response.status).toBe(409)
    expect(await response.json()).toEqual({ error: 'catalogue_changed' })
  })
  it('distinguishes unavailable service from a genuinely empty catalogue', async () => {
    permission.mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', vi.fn(async () => new Response('private', { status: 500 })))
    const { GET } = await import('@/app/api/game/catalog/route')
    const response = await GET()
    expect(response.status).toBe(503)
    expect(await response.json()).toEqual({ error: 'catalogue_unavailable' })
  })
})
