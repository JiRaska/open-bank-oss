// SPDX-License-Identifier: Apache-2.0

// BFF route test for /api/agent/merge-records — mirrors agent-proposals-identity.test.ts:
// the operator bearer is relayed, agentId is forwarded, and upstream status is preserved.

import { afterEach, describe, expect, it, vi } from 'vitest'
import { NextRequest } from 'next/server'
import { auth } from '@/auth'

vi.mock('@/auth', () => ({ auth: vi.fn(async () => ({ user: { accessToken: 'operator-token' } })) }))

const RECORD = {
  id: '3f6f8d9a-2b7c-4e1a-9c5d-8a1f2b3c4d5e',
  agentId: 'flaky-test-hunter',
  prNumber: 123,
  prUrl: 'https://github.com/openbank/open-bank-oss/pull/123',
  headBranch: 'agent/flaky-test-hunter-stabilise',
  mergedBy: 'jiri.raska',
  mergedAt: '2026-10-01T10:00:00Z',
  title: 'Stabilise flaky balance reconciliation test',
  proposalId: null,
  recordedAt: '2026-10-01T10:00:05Z',
  source: 'github-actions',
}

function upstreamResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('agent merge-records BFF route', () => {
  it('relays the operator bearer to the upstream list endpoint', async () => {
    const fetchMock = vi.fn(async () => upstreamResponse([RECORD]))
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/agent/merge-records/route')

    const response = await GET(new NextRequest('http://localhost/api/agent/merge-records'))

    expect(response.status).toBe(200)
    const body = await response.json()
    expect(body).toEqual([RECORD])
    const [, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(String(fetchMock.mock.calls[0][0])).toBe('http://localhost:8109/api/v1/agent-merge-records')
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer operator-token')
    expect(init.cache).toBe('no-store')
  })

  it('forwards the agentId query param, encoded', async () => {
    const fetchMock = vi.fn(async () => upstreamResponse([RECORD]))
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/agent/merge-records/route')

    await GET(new NextRequest('http://localhost/api/agent/merge-records?agentId=flaky%2Ftest%20hunter'))

    expect(String(fetchMock.mock.calls[0][0])).toBe(
      'http://localhost:8109/api/v1/agent-merge-records?agentId=flaky%2Ftest%20hunter',
    )
  })

  it('answers 401 without a session token and does not call upstream', async () => {
    vi.mocked(auth).mockResolvedValueOnce(null as never)
    const fetchMock = vi.fn(async () => upstreamResponse([]))
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/agent/merge-records/route')

    const response = await GET(new NextRequest('http://localhost/api/agent/merge-records'))

    expect(response.status).toBe(401)
    expect(await response.json()).toEqual({ error: 'unauthenticated' })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('preserves the upstream status when the operator role is insufficient', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => upstreamResponse({ message: 'forbidden' }, 403)))
    const { GET } = await import('@/app/api/agent/merge-records/route')

    const response = await GET(new NextRequest('http://localhost/api/agent/merge-records'))

    expect(response.status).toBe(403)
    expect(await response.json()).toEqual({ error: 'upstream_error' })
  })

  it('answers 502 agent_unreachable when upstream cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('ECONNREFUSED') }))
    const { GET } = await import('@/app/api/agent/merge-records/route')

    const response = await GET(new NextRequest('http://localhost/api/agent/merge-records'))

    expect(response.status).toBe(502)
    expect(await response.json()).toEqual({ error: 'agent_unreachable' })
  })
})
