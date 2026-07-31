// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Integration tests for the OBO MCP relay (/api/agent/obo-mcp, ADR-0224 D4 + D2) —
// mocked Keycloak token endpoint + mcp-service session lifecycle + session.

import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({
  auth: vi.fn(),
}))

import { auth } from '@/auth'

const SESSION = { user: { accessToken: 'operator-subject-token', roles: ['ROLE_OPERATOR'] } }
const RPC = { jsonrpc: '2.0', id: 1, method: 'tools/list', params: {} }
const EXPIRED_FUTURE_S = 600
const TEST_JTI = 'jti-under-test-123'

function fakeOboToken(): string {
  const payload = Buffer.from(
    JSON.stringify({ exp: Math.floor(Date.now() / 1000) + EXPIRED_FUTURE_S, jti: TEST_JTI }),
  ).toString('base64url')
  return `hdr.${payload}.sig`
}

function req(): NextRequest {
  return new NextRequest('http://localhost/api/agent/obo-mcp', {
    method: 'POST',
    body: JSON.stringify(RPC),
  })
}

// create session → exchange → bind → relay
function fetchMockSequence(): ReturnType<typeof vi.fn> {
  const mock = vi.fn()
  mock.mockResolvedValueOnce(
    new Response(JSON.stringify({ id: 'sess-1' }), { status: 201 }),
  )
  mock.mockResolvedValueOnce(
    new Response(JSON.stringify({ access_token: fakeOboToken(), token_type: 'Bearer' }), { status: 200 }),
  )
  mock.mockResolvedValueOnce(
    new Response(JSON.stringify({ id: 'sess-1', jti: TEST_JTI }), { status: 200 }),
  )
  mock.mockResolvedValueOnce(
    new Response(JSON.stringify({ jsonrpc: '2.0', id: 1, result: { tools: [] } }), { status: 200 }),
  )
  return mock
}

async function route(): Promise<typeof import('@/app/api/agent/obo-mcp/route')> {
  return import('@/app/api/agent/obo-mcp/route')
}

describe('obo-mcp relay (ADR-0224 D4 + D2)', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.stubEnv('OBO_MCP_ENABLED', 'true')
    vi.stubEnv('KEYCLOAK_CLIENT_SECRET', 'test-secret')
    vi.mocked(auth).mockResolvedValue(SESSION as never)
  })
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.restoreAllMocks()
  })

  it('404s when the flag is off — the chain is inert by default', async () => {
    vi.stubEnv('OBO_MCP_ENABLED', 'false')
    const res = await (await route()).POST(req())
    expect(res.status).toBe(404)
  })

  it('401s without an operator session — never an unauthenticated relay', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const res = await (await route()).POST(req())
    expect(res.status).toBe(401)
  })

  it('drives the full D2 flow: session create → exchange → jti bind → relay with the OBO bearer', async () => {
    const mock = fetchMockSequence()
    vi.stubGlobal('fetch', mock)

    const res = await (await route()).POST(req())
    expect(res.status).toBe(200)

    const [createUrl, createInit] = mock.mock.calls[0] as [string, RequestInit]
    expect(createUrl).toContain('/api/v1/mcp/sessions')
    expect(JSON.parse(createInit.body as string)).toMatchObject({
      clientId: 'admin-ui',
      roleCeiling: ['ROLE_OPERATOR'],
    })
    expect((createInit.headers as Record<string, string>).authorization).toBe('Bearer operator-subject-token')

    const [exchangeUrl, exchangeInit] = mock.mock.calls[1] as [string, RequestInit]
    expect(exchangeUrl).toContain('/realms/openbank/protocol/openid-connect/token')
    expect((exchangeInit.body as URLSearchParams).get('audience')).toBe('openbank-mcp-service')

    const [bindUrl, bindInit] = mock.mock.calls[2] as [string, RequestInit]
    expect(bindUrl).toContain('/api/v1/mcp/sessions/sess-1/bind')
    expect(JSON.parse(bindInit.body as string)).toEqual({ jti: TEST_JTI })

    const [mcpUrl, mcpInit] = mock.mock.calls[3] as [string, RequestInit]
    expect(mcpUrl).toContain('/mcp')
    expect((mcpInit.headers as Record<string, string>).Authorization).toMatch(/^Bearer hdr\./)
  })

  it('caches the exchanged token — a second call skips session, exchange and bind', async () => {
    const mock = fetchMockSequence()
    vi.stubGlobal('fetch', mock)

    const { POST } = await route()
    await POST(req())
    mock.mockResolvedValueOnce(
      new Response(JSON.stringify({ jsonrpc: '2.0', id: 1, result: { tools: [] } }), { status: 200 }),
    )
    await POST(req())

    const lifecycleCalls = mock.mock.calls.filter(([url]) =>
      String(url).includes('openid-connect/token') || String(url).includes('/api/v1/mcp/sessions'),
    )
    expect(lifecycleCalls).toHaveLength(3)
  })

  it('502s when the session create fails — no partial flow reaches the relay', async () => {
    const mock = vi.fn().mockResolvedValueOnce(
      new Response(JSON.stringify({ error: 'forbidden' }), { status: 403 }),
    )
    vi.stubGlobal('fetch', mock)

    const res = await (await route()).POST(req())
    expect(res.status).toBe(502)
    expect(mock).toHaveBeenCalledTimes(1)
  })

  it('502s when the exchange fails — the operator never sees a relayed error as success', async () => {
    const mock = vi.fn()
    mock.mockResolvedValueOnce(new Response(JSON.stringify({ id: 'sess-1' }), { status: 201 }))
    mock.mockResolvedValueOnce(
      new Response(JSON.stringify({ error: 'access_denied' }), { status: 400 }),
    )
    vi.stubGlobal('fetch', mock)

    const res = await (await route()).POST(req())
    expect(res.status).toBe(502)
  })
})
