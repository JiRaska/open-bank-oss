// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
vi.mock('@/lib/governance/agentCharters', () => ({ loadAgentCharters: vi.fn() }))
vi.mock('@/lib/governance/docs', () => ({ loadAgentCharterDoc: vi.fn() }))
vi.mock('@/lib/governance/agentDiagnostics', () => ({
  deriveAgentDiagnostics: vi.fn(() => []),
  deriveAgentMesh: vi.fn(() => null),
}))

import { auth } from '@/auth'
import { loadAgentCharters } from '@/lib/governance/agentCharters'
import { loadAgentCharterDoc } from '@/lib/governance/docs'

const AGENT_ID = 'compliance-officer'

describe('GET /api/iaops/agents/[agentId]', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token' } } as never)
    vi.mocked(loadAgentCharters).mockResolvedValue({ available: true, agents: [{ id: AGENT_ID }] } as never)
    vi.mocked(loadAgentCharterDoc).mockResolvedValue(null)
  })

  afterEach(() => vi.restoreAllMocks())

  it('keeps only proposals created by the agent when an older upstream ignores the agentId filter', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify([
      { id: 'own-1', title: 'Review AML case', proposedBy: AGENT_ID, state: 'PROPOSED', proposedAt: '2026-08-07T10:00:00Z' },
      { id: 'other-1', title: 'Review dispute', proposedBy: 'rca-investigator', state: 'PROPOSED', proposedAt: '2026-08-07T10:01:00Z' },
    ]), { status: 200 }))
    vi.stubGlobal('fetch', fetcher)
    const { GET } = await import('@/app/api/iaops/agents/[agentId]/route')

    const response = await GET(new NextRequest(`http://localhost/api/iaops/agents/${AGENT_ID}`), {
      params: Promise.resolve({ agentId: AGENT_ID }),
    })

    expect(new URL(String(fetcher.mock.calls[0][0])).searchParams.get('agentId')).toBe(AGENT_ID)
    const body = await response.json()
    expect(body.proposals).toMatchObject({
      available: true,
      pendingCount: 1,
    })
    expect(body.proposals.items).toEqual([{
      id: 'own-1', title: 'Review AML case', state: 'PROPOSED',
      proposedAt: '2026-08-07T10:00:00Z', decidedAt: null,
    }])
  })
})
