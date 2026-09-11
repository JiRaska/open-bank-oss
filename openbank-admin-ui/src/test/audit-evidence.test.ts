import { describe, expect, it } from 'vitest'
import { formatAuditPayload, parseAuditEvidenceList } from '@/lib/audit/auditEvidence'

const aggregateId = '05a02ef1-381c-40e7-b73f-d6855eead42e'
const entry = {
  id: 'e5ec0a88-75e7-4d1c-82ef-cbcd655112d3',
  aggregateId,
  aggregateType: 'ACCOUNT',
  eventType: 'UPDATED',
  actorId: 'operator-42',
  actorType: 'USER',
  payload: '{"status":"ACTIVE"}',
  sourceService: 'account-service',
  occurredAt: '2026-08-31T08:00:00Z',
  recordedAt: '2026-08-31T08:00:01Z',
  occurredAtSource: 'EVENT',
  sourceServiceSource: 'TOPIC',
  actChain: [],
}

describe('audit evidence contract', () => {
  it('preserves provenance and pretty-prints the JSON string carried on the wire', () => {
    const parsed = parseAuditEvidenceList([entry], aggregateId)

    expect(parsed[0]).toMatchObject({
      aggregateId,
      occurredAtSource: 'EVENT',
      sourceServiceSource: 'TOPIC',
      payload: '{"status":"ACTIVE"}',
    })
    expect(formatAuditPayload(parsed[0].payload)).toBe('{\n  "status": "ACTIVE"\n}')
  })

  it('renders a non-JSON legacy payload as inert text', () => {
    expect(formatAuditPayload('<script>alert(1)</script>')).toBe('<script>alert(1)</script>')
  })

  it('rejects evidence attributed to another aggregate or an invalid timestamp', () => {
    expect(() => parseAuditEvidenceList([{ ...entry, aggregateId: 'other' }], aggregateId)).toThrow(/another aggregate/)
    expect(() => parseAuditEvidenceList([{ ...entry, occurredAt: 'not-a-date' }], aggregateId)).toThrow(/occurredAt/)
  })
})
