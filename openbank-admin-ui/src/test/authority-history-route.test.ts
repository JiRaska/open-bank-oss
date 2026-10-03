// SPDX-License-Identifier: Apache-2.0
import { NextRequest } from 'next/server'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('@/auth', () => ({ auth: vi.fn() }))
import { auth } from '@/auth'
import { GET } from '@/app/api/context/authorizations/[id]/route'
const id = '11111111-1111-4111-8111-111111111111', at = '2026-09-17T12:00:00Z'
function fixture(rootId = id) {
  return { root: `delegation:${rootId}`, effectiveAt: at, knownAt: at, truncated: false, actionAuthorization: 'UNKNOWN', observations: [{ evidence: { delegationId: rootId, revision: 1, eventType: 'DelegationActivated', grantorPartyId: '22222222-2222-4222-8222-222222222222', granteePartyId: '33333333-3333-4333-8333-333333333333', resourceType: 'ACCOUNT', resourceId: '44444444-4444-4444-8444-444444444444', capabilities: ['ACCOUNT_READ'], approvalPolicy: 'SOLO', requiredApprovals: null, validFrom: at, validTo: null, occurredAt: at }, recordedAt: at, evidenceRef: `delegation:${rootId}:1`, contentHash: 'a'.repeat(64) }] }
}
function request(extra = '') { return GET(new NextRequest(`http://localhost/api/context/authorizations/${id}?caseId=case-synthetic${extra}`), { params: Promise.resolve({ id }) }) }
beforeEach(() => { vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'synthetic-admin-token', roles: ['ROLE_ADMIN'] } } as never) })
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks() })
describe('authority history BFF', () => {
  it('requires authentication before upstream access', async () => {
    vi.mocked(auth).mockResolvedValue(null as never); const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    expect((await request()).status).toBe(401); expect(fetch).not.toHaveBeenCalled()
  })
  it('rejects an operator token without investigative role', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'synthetic-operator-token', roles: ['ROLE_OPERATOR'] } } as never)
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    expect((await request()).status).toBe(403); expect(fetch).not.toHaveBeenCalled()
  })
  it('preserves the initial offered revision zero', async () => {
    const source = fixture()
    source.observations[0].evidence.revision = 0
    source.observations[0].evidence.eventType = 'DelegationOffered'
    source.observations[0].evidenceRef = `delegation:${id}:0`
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(source))))
    const response = await request()
    expect(response.status).toBe(200)
    expect(await response.json()).toEqual(source)
  })
  it('rejects a history for a different requested root', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(fixture('55555555-5555-4555-8555-555555555555')))))
    expect((await request()).status).toBe(502)
  })
  it('rejects a snapshot differing from the requested known time', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(fixture()))))
    expect((await request('&knownAt=2026-09-18T12%3A00%3A00Z')).status).toBe(502)
  })
  it('copies allowed fields only and relays no-store, fixed purpose and caller token', async () => {
    const source = fixture()
    const raw = { ...source, hiddenParties: ['excluded'], observations: source.observations.map(row => ({ ...row, privateNote: 'excluded', evidence: { ...row.evidence, credentialId: 'excluded' } })) }
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(raw))); vi.stubGlobal('fetch', fetch)
    const response = await request(`&knownAt=${encodeURIComponent(at)}`)
    expect(response.status).toBe(200); expect(response.headers.get('Cache-Control')).toBe('no-store'); expect(await response.json()).toEqual(source)
    const [, init] = fetch.mock.calls[0] as [string, RequestInit]; expect(init.cache).toBe('no-store')
    const headers = new Headers(init.headers)
    expect(headers.get('Authorization')).toBe('Bearer synthetic-admin-token'); expect(headers.get('X-Investigation-Case-Id')).toBe('case-synthetic'); expect(headers.get('X-Investigation-Purpose')).toBe('AUTHORIZATION_REVIEW')
  })
})
