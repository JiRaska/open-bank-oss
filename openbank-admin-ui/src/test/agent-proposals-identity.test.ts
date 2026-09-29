// SPDX-License-Identifier: Apache-2.0

import { afterEach, describe, expect, it, vi } from 'vitest'
import { NextRequest } from 'next/server'
import { auth } from '@/auth'
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

  it('recognizes the Keycloak service-account form of a chartered agent', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify([{
      id: 'proposal-service-account', proposedBy: 'service-account-fraud-investigator', suggestedAction: 'fraud.review',
    }]), { status: 200, headers: { 'content-type': 'application/json' } })))
    const { GET } = await import('@/app/api/agent/proposals/route')
    const body = await (await GET(new NextRequest('http://localhost/api/agent/proposals'))).json()

    expect(body[0].agent).toMatchObject({ id: 'service-account-fraud-investigator', icon: 'bot', charterKnown: true })
  })

  it('does not assert human identity for a principal absent from the charter registry', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify([{
      id: 'proposal-2', proposedBy: 'alice@example.test', suggestedAction: 'agent.review',
    }]), { status: 200, headers: { 'content-type': 'application/json' } })))
    const { GET } = await import('@/app/api/agent/proposals/route')
    const body = await (await GET(new NextRequest('http://localhost/api/agent/proposals'))).json()

    expect(body[0].agent).toBeUndefined()
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

  it('forwards the bearer without any browser-supplied or mismatched legacy reviewer', async () => {
    vi.mocked(auth).mockResolvedValueOnce({ user: { id: 'operator-subject', accessToken: 'operator-token' } } as never)
    const upstream = vi.fn(async () => new Response(JSON.stringify({ state: 'APPROVED' }), {
      status: 200, headers: { 'content-type': 'application/json' },
    }))
    vi.stubGlobal('fetch', upstream)
    const { POST } = await import('@/app/api/agent/proposals/route')
    const response = await POST(new NextRequest('http://localhost/api/agent/proposals', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ proposalId: '47fd98d8-a0bb-4a8a-9b16-ea9b18b60447', approve: true, decidedBy: 'forged-reviewer', reason: 'verified' }),
    }))

    expect(response.status).toBe(200)
    expect(JSON.parse(upstream.mock.calls[0][1].body as string)).toEqual({ approve: true, reason: 'verified' })
    expect(upstream.mock.calls[0][1].headers).toMatchObject({ Authorization: 'Bearer operator-token' })
  })

  it('rejects a decision when the session lacks an access token', async () => {
    vi.mocked(auth).mockResolvedValueOnce({ user: { id: 'operator-subject' } } as never)
    const upstream = vi.fn()
    vi.stubGlobal('fetch', upstream)
    const { POST } = await import('@/app/api/agent/proposals/route')
    const response = await POST(new NextRequest('http://localhost/api/agent/proposals', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ proposalId: '47fd98d8-a0bb-4a8a-9b16-ea9b18b60447', approve: true, decidedBy: 'forged-reviewer' }),
    }))

    expect(response.status).toBe(401)
    expect(upstream).not.toHaveBeenCalled()
  })

  it('checks authentication before interpreting an invalid browser request', async () => {
    vi.mocked(auth).mockResolvedValueOnce(null as never)
    const upstream = vi.fn()
    vi.stubGlobal('fetch', upstream)
    const { POST } = await import('@/app/api/agent/proposals/route')
    const response = await POST(new NextRequest('http://localhost/api/agent/proposals', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ proposalId: 'not-a-uuid', approve: 'true' }),
    }))

    expect(response.status).toBe(401)
    expect(upstream).not.toHaveBeenCalled()
  })

  it('rejects malformed decision data before forwarding to the agent', async () => {
    vi.mocked(auth).mockResolvedValueOnce({ user: { id: 'operator-subject', accessToken: 'operator-token' } } as never)
    const upstream = vi.fn()
    vi.stubGlobal('fetch', upstream)
    const { POST } = await import('@/app/api/agent/proposals/route')
    const response = await POST(new NextRequest('http://localhost/api/agent/proposals', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ proposalId: 'not-a-uuid', approve: true }),
    }))

    expect(response.status).toBe(400)
    expect(upstream).not.toHaveBeenCalled()
  })
})
