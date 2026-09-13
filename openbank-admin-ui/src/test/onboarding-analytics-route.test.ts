// SPDX-License-Identifier: Apache-2.0

import { afterEach, describe, expect, it, vi } from 'vitest'
import { NextRequest } from 'next/server'

const { authMock } = vi.hoisted(() => ({ authMock: vi.fn() }))
vi.mock('@/auth', () => ({ auth: authMock }))

import { GET } from '@/app/api/onboarding/funnel-analytics/route'

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('onboarding analytics BFF evidence semantics', () => {
  it('returns a safe non-success response when ClickHouse produces no evidence', async () => {
    authMock.mockResolvedValue({ user: { accessToken: 'operator-token' } })
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('private upstream diagnostic')))
    vi.spyOn(console, 'error').mockImplementation(() => undefined)

    const response = await GET(new NextRequest('http://localhost/api/onboarding/funnel-analytics?from=2026-08-01&to=2026-09-01'))

    expect(response.status).toBe(502)
    expect(await response.json()).toEqual({
      available: false,
      from: '2026-08-01',
      to: '2026-09-01',
      steps: [],
      signOutcomes: [],
      failReasons: [],
      kycMethods: [],
    })
  })
})
