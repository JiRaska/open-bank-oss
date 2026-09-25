// SPDX-License-Identifier: Apache-2.0
import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('@/auth', () => ({ auth: vi.fn() }))
import { auth } from '@/auth'
import { GET } from '@/app/api/context/lending-loans/[id]/approved-guarantees/route'

const id = '11111111-1111-4111-8111-111111111111'
const at = '2026-09-25T12:00:00Z'
const fact = {
  guaranteeId: '22222222-2222-4222-8222-222222222222', contractId: '33333333-3333-4333-8333-333333333333',
  revision: 2, supersedesGuaranteeId: null, guarantorPartyId: '44444444-4444-4444-8444-444444444444',
  capAmount: 120000, currency: 'CZK', coverageFraction: 0.75, seniority: 1, validFrom: at, validTo: null,
  sourceDocumentId: '55555555-5555-4555-8555-555555555555', sourceSha256: 'a'.repeat(64), decidedAt: at,
}
const history = { loanId: id, effectiveAt: at, knownAt: at, guarantees: [fact], truncated: false }
const request = (suffix = '', loanId = id) => GET(new NextRequest(`http://localhost/api/context/lending-loans/${loanId}/approved-guarantees${suffix}`), { params: Promise.resolve({ id: loanId }) })
beforeEach(() => vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'synthetic-token', roles: ['ROLE_CREDIT_RISK'] } } as never))
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks() })

describe('lending guarantees BFF', () => {
  it('rejects missing session, wrong role and request query without contacting source', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    vi.mocked(auth).mockResolvedValueOnce(null as never)
    expect((await request()).status).toBe(401)
    vi.mocked(auth).mockResolvedValueOnce({ user: { accessToken: 'synthetic-token', roles: ['ROLE_OPERATOR'] } } as never)
    expect((await request()).status).toBe(403)
    expect((await request('?loanId=other')).status).toBe(400)
    expect(fetch).not.toHaveBeenCalled()
  })
  it('forwards only the exact case and fixed purpose, returning bounded validated evidence', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ ...history, privateNote: 'excluded' })))
    vi.stubGlobal('fetch', fetch)
    const response = await request()
    expect(response.status).toBe(200)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    expect(await response.json()).toEqual(history)
    const [url, init] = fetch.mock.calls[0] as [string, RequestInit]
    expect(url).toContain(`/api/v1/context/lending-loans/${id}/approved-guarantees`)
    expect(init.cache).toBe('no-store')
    const headers = new Headers(init.headers)
    expect(headers.get('Authorization')).toBe('Bearer synthetic-token')
    expect(headers.get('X-Investigation-Case-Id')).toBe(id)
    expect(headers.get('X-Investigation-Purpose')).toBe('LENDING_EXPOSURE_REVIEW')
  })
  it('fails closed on cross-loan or malformed source evidence', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ ...history, loanId: '66666666-6666-4666-8666-666666666666' })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...history, guarantees: [{ ...fact, sourceSha256: 'bad' }] })))
    vi.stubGlobal('fetch', fetch)
    expect((await request()).status).toBe(502)
    expect((await request()).status).toBe(502)
  })
  it('preserves disabled-source status without showing fabricated evidence', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 503 })))
    expect((await request()).status).toBe(503)
  })
})
