// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { projectDelegationAuditTimeline } from '@/lib/delegations/auditTimeline'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
import { auth } from '@/auth'

const GRANT = '018f4a3c-1b2d-7e00-9a11-000000000002'
const OTHER_GRANT = '018f4a3c-1b2d-7e00-9a11-000000000003'
const EVIDENCE_ONE = '018f4a3c-1b2d-7e00-9a11-000000000101'
const EVIDENCE_TWO = '018f4a3c-1b2d-7e00-9a11-000000000102'
const ADMIN_SESSION = { user: { accessToken: 'admin-token', roles: ['ROLE_ADMIN'] } }

function auditEntry(overrides: Record<string, unknown> = {}) {
  return {
    id: EVIDENCE_ONE,
    eventType: 'DelegationOffered',
    aggregateType: 'DELEGATIONGRANT',
    aggregateId: GRANT,
    actorId: null,
    actorType: null,
    payload: JSON.stringify({ note: 'not returned' }),
    sourceService: 'delegation-service',
    sourceServiceSource: 'EVENT',
    correlationId: 'correlation-1',
    occurredAt: '2026-08-31T10:00:00.123456Z',
    occurredAtSource: 'EVENT',
    recordedAt: '2026-08-31T10:00:01Z',
    ...overrides,
  }
}

function fetchCalls() {
  return vi.mocked(global.fetch).mock.calls as unknown as [string, RequestInit][]
}

async function call(id = GRANT) {
  const { GET } = await import('@/app/api/delegations/[id]/audit/route')
  return GET({} as never, { params: Promise.resolve({ id }) })
}

beforeEach(() => {
  vi.resetModules()
  vi.mocked(auth).mockResolvedValue(ADMIN_SESSION as never)
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('GET /api/delegations/[id]/audit', () => {
  it('requires both a session and the delegation plus audit UI permissions before calling upstream', async () => {
    const upstream = vi.fn()
    vi.stubGlobal('fetch', upstream)

    vi.mocked(auth).mockResolvedValueOnce(null as never)
    expect((await call()).status).toBe(401)

    vi.mocked(auth).mockResolvedValueOnce({ user: { accessToken: 'operator', roles: ['ROLE_OPERATOR'] } } as never)
    const forbidden = await call()
    expect(forbidden.status).toBe(403)
    expect(await forbidden.json()).toEqual({ error: 'forbidden' })
    expect(upstream).not.toHaveBeenCalled()
  })

  it('rejects a malformed grant id without touching audit-service', async () => {
    const upstream = vi.fn()
    vi.stubGlobal('fetch', upstream)
    const response = await call('../../etc/passwd')
    expect(response.status).toBe(400)
    expect(upstream).not.toHaveBeenCalled()
  })

  it('relays the human bearer and returns a sorted, payload-free evidence projection', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify([
      auditEntry(),
      auditEntry({
        id: EVIDENCE_TWO,
        eventType: 'DelegationSuspended',
        actorId: 'operator@example.invalid',
        actorType: 'USER',
        payload: JSON.stringify({ reason: 'Fraud review', secretInternalField: 'must-not-leave-bff' }),
        occurredAt: '2026-08-31T11:00:00Z',
      }),
    ]), { status: 200 })))

    const response = await call()
    const body = await response.json()

    expect(response.status).toBe(200)
    expect(response.headers.get('cache-control')).toContain('no-store')
    expect(body.grantId).toBe(GRANT)
    expect(body.latestStatusAfter).toBe('SUSPENDED')
    expect(body.entries.map((entry: { eventType: string }) => entry.eventType)).toEqual([
      'DelegationSuspended',
      'DelegationOffered',
    ])
    expect(body.entries[0]).toMatchObject({
      evidenceId: EVIDENCE_TWO,
      actorId: 'operator@example.invalid',
      reason: 'Fraud review',
      reasonState: 'recorded',
      statusAfter: 'SUSPENDED',
      sourceService: 'delegation-service',
      sourceAttribution: 'event',
      timeSource: 'event',
    })
    expect(JSON.stringify(body)).not.toContain('secretInternalField')
    expect(JSON.stringify(body)).not.toContain('must-not-leave-bff')
    expect(body.entries[0]).not.toHaveProperty('payload')

    const [url, init] = fetchCalls()[0]
    expect(String(url)).toContain(`/api/v1/audit/entries/${GRANT}?limit=100`)
    expect(new Headers(init.headers).get('authorization')).toBe('Bearer admin-token')
  })

  it('fails closed when audit-service returns a row for another aggregate or a non-array body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(
      new Response(JSON.stringify([auditEntry({ aggregateId: OTHER_GRANT })]), { status: 200 }),
    ).mockResolvedValueOnce(
      new Response(JSON.stringify({ entries: [auditEntry()] }), { status: 200 }),
    ))

    expect((await call()).status).toBe(502)
    expect((await call()).status).toBe(502)
  })

  it('accepts the append-only legacy aggregate spelling but rejects another aggregate type', () => {
    expect(projectDelegationAuditTimeline([
      auditEntry({ aggregateType: 'DelegationGrant' }),
    ], GRANT)).not.toBeNull()
    expect(projectDelegationAuditTimeline([
      auditEntry({ aggregateType: 'ACCOUNT' }),
    ], GRANT)).toBeNull()
  })

  it('normalizes upstream denial and failure without relaying their bodies', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(
      new Response(JSON.stringify({ policy: 'internal reason' }), { status: 403 }),
    ).mockResolvedValueOnce(
      new Response(JSON.stringify({ stack: 'internal stack' }), { status: 500 }),
    ))

    const denied = await call()
    expect(denied.status).toBe(403)
    expect(await denied.json()).toEqual({ error: 'forbidden' })

    const failed = await call()
    expect(failed.status).toBe(502)
    expect(await failed.json()).toEqual({ error: 'upstream_error' })
  })
})

describe('delegation audit evidence projection', () => {
  it('keeps future event types visible without inventing their resulting status', () => {
    const projection = projectDelegationAuditTimeline([
      auditEntry({ eventType: 'DelegationReviewed' }),
    ], GRANT)

    expect(projection?.entries[0]).toMatchObject({ eventType: 'DelegationReviewed', statusAfter: null })
    expect(projection?.latestStatusAfter).toBeNull()
  })

  it('labels missing and unreadable evidence instead of silently fabricating it', () => {
    const projection = projectDelegationAuditTimeline([
      auditEntry({ payload: '{not-json', actorId: null, actorType: 'SYSTEM', occurredAtSource: 'INGEST' }),
    ], GRANT)

    expect(projection?.entries[0]).toMatchObject({
      actorProvenance: 'classified',
      reason: null,
      reasonState: 'unreadable',
      timeSource: 'ingest',
    })
  })

  it('reports that a full page may omit older evidence', () => {
    const projection = projectDelegationAuditTimeline([
      auditEntry(),
      auditEntry({ id: EVIDENCE_TWO }),
    ], GRANT, 2)

    expect(projection?.mayBeTruncated).toBe(true)
  })
})
