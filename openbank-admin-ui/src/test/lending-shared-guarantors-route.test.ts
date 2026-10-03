// SPDX-License-Identifier: Apache-2.0
import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('@/auth', () => ({ auth: vi.fn() }))
import { auth } from '@/auth'
import { GET } from '@/app/api/context/lending-loans/[id]/shared-guarantors/route'
import { parseSharedGuarantorRelationships } from '@/lib/context/sharedGuarantors'

const root = '11111111-1111-4111-8111-111111111111'
const related = '22222222-2222-4222-8222-222222222222'
const at = '2026-09-25T12:00:00Z'
const fact = {
  guaranteeId: '33333333-3333-4333-8333-333333333333', contractId: '44444444-4444-4444-8444-444444444444',
  revision: 2, supersedesGuaranteeId: null, guarantorPartyId: '55555555-5555-4555-8555-555555555555',
  capAmount: 120000, currency: 'CZK', coverageFraction: 0.75, seniority: 1, validFrom: at, validTo: null,
  sourceDocumentId: '66666666-6666-4666-8666-666666666666', sourceSha256: 'a'.repeat(64), decidedAt: at,
}
const relationships = { rootLoanId: root, effectiveAt: at, knownAt: at, candidateTruncated: true,
  relatedLoansTruncated: false, relatedLoans: [{ loanId: related, guarantees: [fact], truncated: true }] }
const request = (suffix = '', id = root) => GET(new NextRequest(`http://localhost/api/context/lending-loans/${id}/shared-guarantors${suffix}`), { params: Promise.resolve({ id }) })
beforeEach(() => vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'synthetic-token', roles: ['ROLE_CREDIT_RISK'] } } as never))
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks() })

describe('lending shared guarantors', () => {
  it('validates nested source evidence and all partial flags', () => {
    expect(parseSharedGuarantorRelationships(relationships)).toEqual(relationships)
    expect(() => parseSharedGuarantorRelationships({ ...relationships, candidateTruncated: undefined })).toThrow()
    expect(() => parseSharedGuarantorRelationships({ ...relationships, relatedLoans: [{ ...relationships.relatedLoans[0], guarantees: [{ ...fact, sourceSha256: 'bad' }] }] })).toThrow()
    expect(() => parseSharedGuarantorRelationships({ ...relationships, relatedLoans: [...relationships.relatedLoans, relationships.relatedLoans[0]] })).toThrow()
  })
  it('requires session, role and exact loan scope before fetching', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    vi.mocked(auth).mockResolvedValueOnce(null as never)
    expect((await request()).status).toBe(401)
    vi.mocked(auth).mockResolvedValueOnce({ user: { accessToken: 'synthetic-token', roles: ['ROLE_OPERATOR'] } } as never)
    expect((await request()).status).toBe(403)
    expect((await request('?effectiveAt=other')).status).toBe(400)
    expect(fetch).not.toHaveBeenCalled()
  })
  it('forwards the fixed purpose and returns only validated evidence', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ ...relationships, privateNote: 'excluded' })))
    vi.stubGlobal('fetch', fetch)
    const response = await request()
    expect(response.status).toBe(200)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    expect(await response.json()).toEqual(relationships)
    const [url, init] = fetch.mock.calls[0] as [string, RequestInit]
    expect(url).toContain(`/api/v1/context/lending-loans/${root}/shared-guarantors`)
    expect(init.cache).toBe('no-store')
    const headers = new Headers(init.headers)
    expect(headers.get('Authorization')).toBe('Bearer synthetic-token')
    expect(headers.get('X-Investigation-Case-Id')).toBe(root)
    expect(headers.get('X-Investigation-Purpose')).toBe('LENDING_EXPOSURE_REVIEW')
  })
  it('fails closed on cross-loan, malformed, or disabled source', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...relationships, rootLoanId: related })))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...relationships, relatedLoans: [{ ...relationships.relatedLoans[0], truncated: null }] })))
      .mockResolvedValueOnce(new Response(null, { status: 503 })))
    expect((await request()).status).toBe(502)
    expect((await request()).status).toBe(502)
    expect((await request()).status).toBe(503)
  })
})
