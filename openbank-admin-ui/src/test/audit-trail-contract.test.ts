// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { formatAuditPayload, parseAuditTrail } from '@/lib/audit/auditTrailContract'

const entry = {
  id: 'audit-42',
  aggregateId: 'account-42',
  aggregateType: 'ACCOUNT',
  eventType: 'UPDATED',
  actorId: null,
  actorType: null,
  payload: '{"status":"ACTIVE"}',
  occurredAt: '2026-09-09T08:00:00Z',
}

describe('audit trail response contract', () => {
  it('preserves valid regulated evidence and formats its encoded payload', () => {
    expect(parseAuditTrail([entry], 500)).toEqual([entry])
    expect(formatAuditPayload(entry.payload)).toContain('\n  "status": "ACTIVE"\n')
  })

  it.each([
    [[{ ...entry, payload: { status: 'ACTIVE' } }], 'payload'],
    [[{ ...entry, occurredAt: 'not-a-date' }], 'occurredAt'],
    [[{ ...entry, actorId: 42 }], 'actorId'],
    [{ entries: [entry] }, 'trail'],
  ])('rejects malformed evidence instead of rendering it', (candidate, message) => {
    expect(() => parseAuditTrail(candidate, 500)).toThrow(message)
  })

  it('rejects a response beyond the requested evidence window', () => {
    expect(() => parseAuditTrail([entry, entry], 1)).toThrow('trail')
  })

  it('keeps a legacy non-JSON payload readable without changing its evidence', () => {
    expect(formatAuditPayload('legacy payload')).toBe('legacy payload')
  })
})
