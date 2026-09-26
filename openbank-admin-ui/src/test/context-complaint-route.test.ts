// SPDX-License-Identifier: Apache-2.0

import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
import { auth } from '@/auth'

const context = { params: Promise.resolve({ reference: 'CMP-42' }) }
const request = () => new NextRequest('http://localhost/api/context/complaints/CMP-42?caseId=case-7&purpose=PAYMENT_COMPLAINT')
const graph = {
  root: 'complaint:CMP-42', truncated: false,
  nodes: [{ key: 'complaint:CMP-42', namespace: 'COMPLAINT', type: 'COMPLAINT', sourceSystem: 'dispute-service', sourceRef: 'c-1', label: 'Complaint CMP-42', classification: 'RESTRICTED', validFrom: '2026-09-13T10:00:00Z', validTo: null, recordedAt: '2026-09-13T10:00:01Z', sourceVersion: 1 }],
  edges: [],
}

beforeEach(() => vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'token', roles: ['ROLE_COMPLIANCE'] } } as never))
afterEach(() => vi.restoreAllMocks())

describe('complaint context BFF', () => {
  it('requires a signed-in compliance user before touching the backend', async () => {
    vi.mocked(auth).mockResolvedValue(null)
    const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/context/complaints/[reference]/route')
    expect((await GET(request(), context)).status).toBe(401)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('validates investigation scope before touching the backend', async () => {
    const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/context/complaints/[reference]/route')
    const bad = new NextRequest('http://localhost/api/context/complaints/CMP-42?caseId=../x&purpose=PAYMENT_COMPLAINT')
    expect((await GET(bad, context)).status).toBe(400)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('relays the bearer and validated case context to the context service', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(graph), { status: 200 })))
    const { GET } = await import('@/app/api/context/complaints/[reference]/route')
    const response = await GET(request(), context)
    expect(response.status).toBe(200)
    const [, init] = vi.mocked(global.fetch).mock.calls[0] as [string, RequestInit]
    const headers = new Headers(init.headers)
    expect(headers.get('Authorization')).toBe('Bearer token')
    expect(headers.get('X-Investigation-Case-Id')).toBe('case-7')
    expect(headers.get('X-Investigation-Purpose')).toBe('PAYMENT_COMPLAINT')
  })

  it('fails closed when the upstream payload is malformed', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}', { status: 200 })))
    const { GET } = await import('@/app/api/context/complaints/[reference]/route')
    expect((await GET(request(), context)).status).toBe(502)
  })
})
