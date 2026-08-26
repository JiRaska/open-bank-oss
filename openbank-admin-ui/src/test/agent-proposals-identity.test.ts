// SPDX-License-Identifier: Apache-2.0

import { afterEach, describe, expect, it, vi } from 'vitest'
import { NextRequest } from 'next/server'
import { loadAgentCharters } from '@/lib/governance/agentCharters'

vi.mock('@/auth', () => ({ auth: vi.fn(async () => ({ user: { accessToken: 'operator-token' } })) }))
vi.mock('@/lib/governance/agentCharters', () => ({
  loadAgentCharters: vi.fn(async () => ({ available: true, agents: [{ id: 'fraud-investigator' }] })),
}))

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('agent proposal identity enrichment', () => {
  it('attaches authoritative charter identity for a known proposing agent', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify([{
      id: 'proposal-1', proposedBy: 'fraud-investigator', suggestedAction: 'fraud.review',
    }]), { status: 200, headers: { 'content-type': 'application/json' } })))
    const { GET } = await import('@/app/api/agent/proposals/route')
    const response = await GET(new NextRequest('http://localhost/api/agent/proposals?state=pending'))
    const body = await response.json()

    expect(body[0].agent).toEqual({
      id: 'fraud-investigator', displayName: 'Fraud Investigator', icon: 'bot', charterKnown: true,
    })
  })

  it('does not mislabel an unknown human principal as a governed agent', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify([{
      id: 'proposal-2', proposedBy: 'alice@example.test', suggestedAction: 'agent.review',
    }]), { status: 200, headers: { 'content-type': 'application/json' } })))
    const { GET } = await import('@/app/api/agent/proposals/route')
    const body = await (await GET(new NextRequest('http://localhost/api/agent/proposals'))).json()

    expect(body[0].agent).toMatchObject({ id: 'alice@example.test', icon: 'user', charterKnown: false })
  })

  it('preserves upstream provenance when the charter registry is unavailable', async () => {
    vi.mocked(loadAgentCharters).mockResolvedValueOnce({ available: false, agents: [] } as never)
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify([{
      id: 'proposal-3', proposedBy: 'fraud-investigator', suggestedAction: 'fraud.review',
    }]), { status: 200, headers: { 'content-type': 'application/json' } })))
    const { GET } = await import('@/app/api/agent/proposals/route')
    const body = await (await GET(new NextRequest('http://localhost/api/agent/proposals'))).json()

    expect(body[0]).toMatchObject({ id: 'proposal-3', proposedBy: 'fraud-investigator' })
    expect(body[0].agent).toBeUndefined()
  })
})
