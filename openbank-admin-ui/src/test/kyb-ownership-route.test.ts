// SPDX-License-Identifier: Apache-2.0

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
vi.mock('@/auth', () => ({ auth: vi.fn() }))
import { auth } from '@/auth'
import { GET as list } from '@/app/api/context/kyb-cases/[id]/ownership-observations/route'
import { GET as detail } from '@/app/api/context/kyb-cases/[id]/ownership-observations/[observationId]/route'

const caseId = '11111111-1111-4111-8111-111111111111'
const observationId = '22222222-2222-4222-8222-222222222222'
const hash = 'a'.repeat(64)
const at = '2026-09-17T12:00:00Z'
const history = { root: `kyb-case:${caseId}`, knownAt: at, truncated: false,
  observations: [{ observationId, revision: 1, sourceSha256: hash, recordedAt: at }] }
const source = { id: observationId, caseId, revision: 1, sourceSha256: hash,
  finding: { source: 'REGISTER', fetchedAt: at, owners: [{ fullName: 'Synthetic Owner', band: 'PCT_25_TO_50', corporate: false,
    natureOfControl: ['ownership-of-shares-25-to-50-percent'], dateOfBirth: '1900-01-01' }] } }

function listRequest() { return list(new Request('http://localhost/ownership-observations'), { params: Promise.resolve({ id: caseId }) }) }
function detailRequest() { return detail(new Request('http://localhost/ownership-observations/selected'), { params: Promise.resolve({ id: caseId, observationId }) }) }

beforeEach(() => { vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'synthetic-kyc-token', roles: ['ROLE_KYC'] } } as never) })
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks() })

describe('KYB ownership BFF', () => {
  it('rejects unauthenticated and operator requests before upstream access', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    vi.mocked(auth).mockResolvedValue(null as never)
    expect((await listRequest()).status).toBe(401)
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator', roles: ['ROLE_OPERATOR'] } } as never)
    expect((await detailRequest()).status).toBe(403)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('returns a bounded reference history under the caller token and fixed purpose', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ ...history, privateNote: 'excluded' })))
    vi.stubGlobal('fetch', fetch)
    const response = await listRequest()
    expect(response.status).toBe(200)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    expect(await response.json()).toEqual(history)
    const headers = new Headers((fetch.mock.calls[0] as [string, RequestInit])[1].headers)
    expect(headers.get('Authorization')).toBe('Bearer synthetic-kyc-token')
    expect(headers.get('X-Investigation-Case-Id')).toBe(caseId)
    expect(headers.get('X-Investigation-Purpose')).toBe('KYB_OWNERSHIP_REVIEW')
  })

  it('does not call KYB after Context denies the case assignment', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response('{}', { status: 403 })); vi.stubGlobal('fetch', fetch)
    expect((await detailRequest()).status).toBe(403)
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('reads selected KYB evidence only after assignment and strips birth date', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(history)))
      .mockResolvedValueOnce(new Response(JSON.stringify(source)))
    vi.stubGlobal('fetch', fetch)
    const response = await detailRequest()
    expect(response.status).toBe(200)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    const body = await response.json()
    expect(body.owners).toEqual([{ fullName: 'Synthetic Owner', band: 'PCT_25_TO_50', corporate: false,
      natureOfControl: ['ownership-of-shares-25-to-50-percent'] }])
    expect(JSON.stringify(body)).not.toContain('dateOfBirth')
    expect(fetch).toHaveBeenCalledTimes(2)
    const headers = new Headers((fetch.mock.calls[1] as [string, RequestInit])[1].headers)
    expect(headers.get('X-Investigation-Purpose')).toBe('KYB_OWNERSHIP_REVIEW')
  })

  it('refuses source details that disagree with the authorized reference', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(history)))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ...source, sourceSha256: 'b'.repeat(64) })))
    vi.stubGlobal('fetch', fetch)
    expect((await detailRequest()).status).toBe(502)
  })
})
