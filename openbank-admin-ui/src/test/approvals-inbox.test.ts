// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Integration tests for the federated approval inbox read (/api/approvals/pending, ADR-0227 D2).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({
  auth: vi.fn(),
}))

import { auth } from '@/auth'

const SESSION = { user: { accessToken: 'operator-token', roles: ['ROLE_ADMIN'] } }

async function route(): Promise<typeof import('@/app/api/approvals/pending/route')> {
  return import('@/app/api/approvals/pending/route')
}

describe('federated approvals inbox (ADR-0227 D2)', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue(SESSION as never)
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('401s without a session', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const res = await (await route()).GET()
    expect(res.status).toBe(401)
  })

  it('merges lending and agent queues into canonical items, sorted by proposedAt', async () => {
    const mock = vi.fn().mockImplementation((url: string) => {
      if (url.includes('lending')) {
        return Promise.resolve(new Response(JSON.stringify([
          { id: 'L-2', action: 'lending.disburse', resourceId: 'loan-2', makerId: 'officer.b', createdAt: '2026-07-30T10:00:00Z' },
          { id: 'L-1', action: 'lending.writeoff', resourceId: 'loan-1', makerId: 'officer.a', createdAt: '2026-07-29T09:00:00Z' },
        ]), { status: 200 }))
      }
      return Promise.resolve(new Response(JSON.stringify([
        { id: 'P-1', suggestedAction: 'agent.research', proposedBy: 'ui-assistant', proposedAt: '2026-07-30T08:00:00Z' },
      ]), { status: 200 }))
    })
    vi.stubGlobal('fetch', mock)

    const res = await (await route()).GET()
    const body = await res.json()
    expect(body.items.map((i: { id: string }) => i.id)).toEqual(['L-1', 'P-1', 'L-2'])
    expect(body.items[0]).toMatchObject({ domain: 'lending', action: 'lending.writeoff', maker: 'officer.a' })
    expect(body.items[1]).toMatchObject({ domain: 'agent', action: 'agent.research' })
  })

  it('degrades to the working half when one queue is down', async () => {
    const mock = vi.fn().mockImplementation((url: string) => {
      if (url.includes('lending')) return Promise.reject(new Error('lending down'))
      return Promise.resolve(new Response(JSON.stringify([
        { id: 'P-1', suggestedAction: 'agent.research', proposedBy: 'ui-assistant', proposedAt: '2026-07-30T08:00:00Z' },
      ]), { status: 200 }))
    })
    vi.stubGlobal('fetch', mock)

    const res = await (await route()).GET()
    const body = await res.json()
    expect(res.status).toBe(200)
    expect(body.items).toHaveLength(1)
    expect(body.items[0].domain).toBe('agent')
  })
})
