// SPDX-License-Identifier: Apache-2.0
import { NextRequest } from 'next/server'
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
vi.mock('@/lib/context/server', () => ({ contextServiceUrl: vi.fn(path => `http://context.test${path}`) }))
import { auth } from '@/auth'
import { GET } from '@/app/api/context/fraud-cases/[id]/network/route'

const id = '11111111-1111-4111-8111-111111111111'
const root = { caseId: id, scoreId: '22222222-2222-4222-8222-222222222222', accountId: '33333333-3333-4333-8333-333333333333', counterpartyId: null, status: 'OPEN', revision: 1, openedAt: '2026-09-17T12:00:00Z', closedAt: null }
const route = () => GET(new NextRequest(`http://localhost/api/context/fraud-cases/${id}/network`), { params: Promise.resolve({ id }) })

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('Fraud network BFF', () => {
  it('passes a human admin bearer and exact case scope, then returns no-store evidence', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'investigator-token', roles: ['ROLE_ADMIN'] } } as never)
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ root, related: [], inspectedCandidates: 0, candidateTruncated: false }), { status: 200 }))
    vi.stubGlobal('fetch', fetcher)

    const response = await route()
    expect(response.status).toBe(200)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    expect(fetcher).toHaveBeenCalledWith(`http://context.test/api/v1/context/fraud-cases/${id}/network`, expect.objectContaining({
      cache: 'no-store', headers: expect.objectContaining({
        Authorization: 'Bearer investigator-token', 'X-Investigation-Case-Id': id,
        'X-Investigation-Purpose': 'FRAUD_INVESTIGATION',
      }),
    }))
  })

  it('does not call Context for a non-admin or return mismatched case data', async () => {
    const fetcher = vi.fn()
    vi.stubGlobal('fetch', fetcher)
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'operator-token', roles: ['ROLE_OPERATOR'] } } as never)
    expect((await route()).status).toBe(403)
    expect(fetcher).not.toHaveBeenCalled()

    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'admin-token', roles: ['ROLE_ADMIN'] } } as never)
    fetcher.mockResolvedValue(new Response(JSON.stringify({ root: { ...root, caseId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' }, related: [], inspectedCandidates: 0, candidateTruncated: false })))
    expect((await route()).status).toBe(502)
  })

  it('rejects oversized streamed evidence before parsing it', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'admin-token', roles: ['ROLE_ADMIN'] } } as never)
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new Uint8Array(256 * 1024 + 1))
        controller.close()
      },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(body, { status: 200 })))

    const response = await route()
    expect(response.status).toBe(502)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
  })
})
