export type AuditEvidence = {
  id: string
  aggregateId: string
  aggregateType: string
  eventType: string
  actorId?: string
  actorType?: string
  payload: string
  sourceService: string
  occurredAt: string
  recordedAt: string
  occurredAtSource: 'EVENT' | 'INGEST'
  sourceServiceSource: 'EVENT' | 'TOPIC' | 'ABSENT'
  correlationId?: string
  channel?: string
  actChain: string[]
  sessionId?: string
}

type JsonRecord = Record<string, unknown>

function isRecord(value: unknown): value is JsonRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function optionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

function requiredString(record: JsonRecord, key: string): string {
  const value = record[key]
  if (typeof value !== 'string' || value.length === 0) throw new Error(`invalid ${key}`)
  return value
}

function instant(record: JsonRecord, key: string): string {
  const value = requiredString(record, key)
  if (!Number.isFinite(Date.parse(value))) throw new Error(`invalid ${key}`)
  return value
}

export function parseAuditEvidenceList(value: unknown, requestedAggregateId: string): AuditEvidence[] {
  if (!Array.isArray(value)) throw new Error('audit response is not a list')
  return value.map(item => {
    if (!isRecord(item)) throw new Error('invalid audit entry')
    const aggregateId = requiredString(item, 'aggregateId')
    if (aggregateId !== requestedAggregateId) throw new Error('audit entry belongs to another aggregate')
    const occurredAtSource = requiredString(item, 'occurredAtSource')
    const sourceServiceSource = requiredString(item, 'sourceServiceSource')
    if (occurredAtSource !== 'EVENT' && occurredAtSource !== 'INGEST') throw new Error('invalid time provenance')
    if (!['EVENT', 'TOPIC', 'ABSENT'].includes(sourceServiceSource)) throw new Error('invalid source provenance')
    if (!Array.isArray(item.actChain) || item.actChain.some(part => typeof part !== 'string')) {
      throw new Error('invalid delegation chain')
    }
    return {
      id: requiredString(item, 'id'),
      aggregateId,
      aggregateType: requiredString(item, 'aggregateType'),
      eventType: requiredString(item, 'eventType'),
      actorId: optionalString(item.actorId),
      actorType: optionalString(item.actorType),
      payload: requiredString(item, 'payload'),
      sourceService: requiredString(item, 'sourceService'),
      occurredAt: instant(item, 'occurredAt'),
      recordedAt: instant(item, 'recordedAt'),
      occurredAtSource,
      sourceServiceSource: sourceServiceSource as AuditEvidence['sourceServiceSource'],
      correlationId: optionalString(item.correlationId),
      channel: optionalString(item.channel),
      actChain: item.actChain as string[],
      sessionId: optionalString(item.sessionId),
    }
  })
}

export function formatAuditPayload(payload: string): string {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2)
  } catch {
    return payload
  }
}
