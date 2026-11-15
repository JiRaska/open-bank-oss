// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ auth: vi.fn() }))
vi.mock('@/auth', () => ({ auth: mocks.auth }))
const series = (name: string) => ({ metric: { namespace: 'accounts', name, issuer_name: 'openbank-ca' }, value: [0, String(Math.floor(Date.now() / 1000) + 40 * 86400)] })

beforeEach(() => {
  vi.resetModules()
  vi.clearAllMocks()
  process.env.PROMETHEUS_URL = 'http://prometheus.test'
  vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify({
    status: 'success',
    data: { result: String(url).includes('certmanager') ? [series('account-service-internal-tls')] : [] },
  }))))
})

describe('GET /api/security/keys', () => {
  it('omits the per-certificate list for a non-expert department, server-side', async () => {
    mocks.auth.mockResolvedValue({ user: { roles: ['ROLE_VIEWER'] } })
    const { GET } = await import('@/app/api/security/keys/route')
    const res = await GET()
    expect(res.status).toBe(200)
    const text = await res.text()
    expect(JSON.parse(text).expert).toBeUndefined()
    expect(text).not.toContain('account-service-internal-tls')
    expect(text).not.toContain('"accounts"')
  })

  it('includes it for an operator', async () => {
    mocks.auth.mockResolvedValue({ user: { roles: ['ROLE_OPERATOR'] } })
    const { GET } = await import('@/app/api/security/keys/route')
    const body = await (await GET()).json()
    expect(body.expert).toEqual([expect.objectContaining({ name: 'account-service-internal-tls', status: 'ok' })])
  })

  it('refuses anonymous callers and DEMO before measuring anything', async () => {
    const { GET } = await import('@/app/api/security/keys/route')
    mocks.auth.mockResolvedValue(null)
    expect((await GET()).status).toBe(401)
    mocks.auth.mockResolvedValue({ user: { roles: ['ROLE_DEMO'] } })
    expect((await GET()).status).toBe(403)
    expect(fetch).not.toHaveBeenCalled()
  })
})
