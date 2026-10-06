// SPDX-License-Identifier: Apache-2.0

import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
import { auth } from '@/auth'

beforeEach(() => vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'admin-token', roles: ['ROLE_ADMIN'] } } as never))
afterEach(() => vi.restoreAllMocks())

describe('context graph control BFFs', () => {
  it('blocks assignment mutation before backend access for a non-admin', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'token', roles: ['ROLE_COMPLIANCE'] } } as never)
    const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/context/assignments/route')
    const request = new NextRequest('http://localhost/api/context/assignments', {
      method: 'POST', body: JSON.stringify({ principalId: 'a', caseId: 'c', purpose: 'PAYMENT_COMPLAINT', validTo: new Date().toISOString() }),
    })
    expect((await POST(request)).status).toBe(403)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('relays a valid maker-checker proposal with the admin bearer', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: 'p-1' }), { status: 201 })))
    const { POST } = await import('@/app/api/context/assignments/route')
    const request = new NextRequest('http://localhost/api/context/assignments', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ principalId: 'analyst-1', caseId: 'case-1', purpose: 'PAYMENT_COMPLAINT', rootRef: 'complaint:CMP-42', validTo: '2026-09-14T10:00:00Z' }),
    })
    expect((await POST(request)).status).toBe(201)
    const [, init] = vi.mocked(global.fetch).mock.calls[0] as [string, RequestInit]
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer admin-token')
  })

  it('rejects an oversized assignment proposal before forwarding it', async () => {
    const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/context/assignments/route')
    const request = new NextRequest('http://localhost/api/context/assignments', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ principalId: 'a'.repeat(17 * 1024), caseId: 'case-1', purpose: 'PAYMENT_COMPLAINT', rootRef: 'complaint:CMP-42', validTo: '2026-09-14T10:00:00Z' }),
    })
    expect((await POST(request)).status).toBe(400)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('rejects unscoped or mismatched incident and complaint assignments before forwarding', async () => {
    const fetchMock = vi.fn(); vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/context/assignments/route')
    for (const [purpose, rootRef] of [
      ['INCIDENT_IMPACT', undefined], ['INCIDENT_IMPACT', 'complaint:CMP-42'],
      ['PAYMENT_COMPLAINT', undefined], ['PAYMENT_COMPLAINT', 'incident:11111111-1111-1111-1111-111111111111'],
    ]) {
      const request = new NextRequest('http://localhost/api/context/assignments', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ principalId: 'operator-1', caseId: 'case-1', purpose, rootRef, validTo: '2026-09-14T10:00:00Z' }),
      })
      expect((await POST(request)).status).toBe(400)
    }
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('relays only aggregate incident impact and investigation headers', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token', roles: ['ROLE_OPERATOR'] } } as never)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ affectedByType: { SERVICE: 2 }, total: 2, drilldownAvailable: false }), { status: 200 })))
    const { GET } = await import('@/app/api/context/incidents/[reference]/impact/route')
    const request = new NextRequest('http://localhost/api/context/incidents/i-1/impact?caseId=case-7&purpose=INCIDENT_IMPACT')
    const response = await GET(request, { params: Promise.resolve({ reference: 'i-1' }) })
    expect(response.status).toBe(200)
    const [, init] = vi.mocked(global.fetch).mock.calls[0] as [string, RequestInit]
    const headers = new Headers(init.headers)
    expect(headers.get('X-Investigation-Case-Id')).toBe('case-7')
    expect(headers.get('X-Investigation-Purpose')).toBe('INCIDENT_IMPACT')
  })

  it('rejects malformed incident aggregate responses', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token', roles: ['ROLE_OPERATOR'] } } as never)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ affectedByType: { SERVICE: -1 }, total: 2 }), { status: 200 })))
    const { GET } = await import('@/app/api/context/incidents/[reference]/impact/route')
    const request = new NextRequest('http://localhost/api/context/incidents/i-1/impact?caseId=case-7&purpose=INCIDENT_IMPACT')
    expect((await GET(request, { params: Promise.resolve({ reference: 'i-1' }) })).status).toBe(502)
  })

  it('preserves partial coverage but drops upstream identifiers and unexpected evidence', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      affectedByType: { SERVICE: 2 }, total: 2, drilldownAvailable: false, projectionStatus: 'PARTIAL',
      nodes: [{ partyId: 'synthetic-hidden-party' }], internalNotes: 'must-not-disclose',
    }))))
    const { GET } = await import('@/app/api/context/incidents/[reference]/impact/route')
    const request = new NextRequest('http://localhost/api/context/incidents/i-1/impact?caseId=case-7&purpose=INCIDENT_IMPACT')
    const response = await GET(request, { params: Promise.resolve({ reference: 'i-1' }) })
    expect(await response.json()).toEqual({
      affectedByType: { SERVICE: 2 }, total: 2, drilldownAvailable: false, projectionStatus: 'PARTIAL',
    })
  })
})
