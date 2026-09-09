// SPDX-License-Identifier: Apache-2.0

export interface AuditTrailEntry {
  id: string
  aggregateId: string
  aggregateType: string
  eventType: string
  actorId: string | null
  actorType: string | null
  payload: string
  occurredAt: string
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function requiredString(record: Record<string, unknown>, field: string): string {
  const value = record[field]
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`Invalid audit ${field}`)
  return value
}

function nullableString(record: Record<string, unknown>, field: string): string | null {
  const value = record[field]
  if (value === null) return null
  return requiredString(record, field)
}

export function parseAuditTrail(raw: unknown, limit: number): AuditTrailEntry[] {
  if (!Array.isArray(raw) || raw.length > limit) throw new Error('Invalid audit trail')
  return raw.map(value => {
    if (!isRecord(value)) throw new Error('Invalid audit entry')
    const occurredAt = requiredString(value, 'occurredAt')
    if (Number.isNaN(Date.parse(occurredAt))) throw new Error('Invalid audit occurredAt')
    return {
      id: requiredString(value, 'id'),
      aggregateId: requiredString(value, 'aggregateId'),
      aggregateType: requiredString(value, 'aggregateType'),
      eventType: requiredString(value, 'eventType'),
      actorId: nullableString(value, 'actorId'),
      actorType: nullableString(value, 'actorType'),
      payload: requiredString(value, 'payload'),
      occurredAt,
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
