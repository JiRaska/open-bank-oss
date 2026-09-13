// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/auth', () => ({ auth: async () => ({ user: { accessToken: 'operator-token' } }) }))
vi.mock('@/lib/discovery', () => ({ resolveInClusterBaseUrl: async () => 'http://security-scanner' }))

const incident = {
  id: '11111111-1111-1111-1111-111111111111', title: 'Core banking outage',
  description: 'Payment processing became unavailable.', category: 'AVAILABILITY',
  severity: 'P1_CRITICAL', status: 'INVESTIGATING', affectedServices: ['payment-service'],
  detectedAt: '2026-09-09T08:00:00Z', reportedAt: '2026-09-09T08:05:00Z',
  containedAt: null, resolvedAt: null, rtoMinutes: null, rpoMinutes: 0,
  reportedToRegulator: false, regulatoryReportId: null, assignedTo: 'incident-commander',
  createdAt: '2026-09-09T08:05:00Z', updatedAt: '2026-09-09T08:10:00Z',
}

describe('security incidents BFF evidence boundary', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('returns only validated and allow-listed incident fields', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify([{ ...incident, internalNote: 'private' }]), { status: 200 })))
    const { GET } = await import('@/app/api/security/incidents/route')

    const response = await GET(new Request('http://localhost/api/security/incidents'))

    expect(response.status).toBe(200)
    expect(await response.json()).toEqual({ available: true, incidents: [incident] })
  })

  it('does not label a malformed upstream row as available evidence', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify([{ ...incident, severity: 'SEVERE' }]), { status: 200 })))
    const { GET } = await import('@/app/api/security/incidents/route')

    const response = await GET(new Request('http://localhost/api/security/incidents'))

    expect(await response.json()).toEqual({ available: false, reason: 'invalid_response' })
  })

  it('walks upstream pages so portfolio totals cover the complete verified register', async () => {
    const rows = Array.from({ length: 201 }, (_, index) => ({
      ...incident,
      id: `00000000-0000-0000-0000-${String(index).padStart(12, '0')}`,
    }))
    const requests: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input))
      requests.push(url.href)
      const offset = Number(url.searchParams.get('offset'))
      return new Response(JSON.stringify(rows.slice(offset, offset + 200)), { status: 200 })
    }))
    const { GET } = await import('@/app/api/security/incidents/route')

    const response = await GET(new Request('http://localhost/api/security/incidents?severity=P1_CRITICAL&limit=1'))

    const body = await response.json()
    expect(body.available).toBe(true)
    expect(body.incidents).toHaveLength(201)
    expect(requests).toHaveLength(2)
    expect(requests[0]).toContain('severity=P1_CRITICAL')
    expect(requests[0]).toContain('limit=200&offset=0')
    expect(requests[1]).toContain('limit=200&offset=200')
  })

  it('never presents a truncated oversized register as complete evidence', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input))
      const offset = Number(url.searchParams.get('offset'))
      const page = Array.from({ length: 200 }, (_, index) => ({
        ...incident,
        id: `00000000-0000-0000-0000-${String(offset + index).padStart(12, '0')}`,
      }))
      return new Response(JSON.stringify(page), { status: 200 })
    }))
    const { GET } = await import('@/app/api/security/incidents/route')

    const response = await GET(new Request('http://localhost/api/security/incidents'))

    expect(await response.json()).toEqual({ available: false, reason: 'result_limit' })
  })
})
