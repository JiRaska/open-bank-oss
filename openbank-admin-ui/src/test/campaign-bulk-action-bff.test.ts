// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: async () => ({ user: { accessToken: 'test-token' } }) }))

describe('campaign bulk action proxy', () => {
  beforeEach(() => vi.resetModules())

  it('starts a bounded run instead of calling synchronous enrol', async () => {
    const upstream = vi.fn(async (url: string, options: RequestInit) => ({
      ok: true,
      status: 201,
      text: async () => JSON.stringify({ id: 'run-id' }),
      url,
      options,
    }))
    vi.stubGlobal('fetch', upstream)
    const { POST } = await import('@/app/api/campaigns/[id]/actions/route')
    const request = new Request('http://localhost/api/campaigns/campaign-id/actions', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ action: 'enrol' }),
    })

    const result = await POST(request, { params: Promise.resolve({ id: 'campaign-id' }) })

    expect((await result.json()).state).toBe('ok')
    expect(String(upstream.mock.calls[0][0])).toContain('/api/v1/campaigns/campaign-id/bulk-runs')
    expect(String(upstream.mock.calls[0][0])).not.toContain('/enrol')
    expect(upstream.mock.calls[0][1].method).toBe('POST')
  })
})
