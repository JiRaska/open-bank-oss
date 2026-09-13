// SPDX-License-Identifier: Apache-2.0

export interface PidRecordEvidence {
  id: string
  personId: string
  identifierType: string
  identifierValue: string
  issuingCountry: string
  status: string
  verified: boolean
  createdAt: string
  validUntil?: string
}

function text(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`Invalid ${field}`)
  return value
}

function date(value: unknown, field: string): string {
  const candidate = text(value, field)
  if (Number.isNaN(Date.parse(candidate))) throw new Error(`Invalid ${field}`)
  return candidate
}

export function parsePidRecords(raw: unknown): PidRecordEvidence[] {
  // No list envelope is published today. If one is added, its contract must land with the route;
  // guessing at items/content here would turn any unrelated JSON object into a valid empty list.
  if (!Array.isArray(raw)) throw new Error('Invalid PID list response')
  return raw.map(value => {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new Error('Invalid PID record')
    const item = value as Record<string, unknown>
    if (typeof item.verified !== 'boolean') throw new Error('Invalid PID verification state')
    return {
      id: text(item.id, 'PID id'),
      personId: text(item.personId, 'person id'),
      identifierType: text(item.identifierType, 'identifier type'),
      identifierValue: text(item.identifierValue, 'identifier value'),
      issuingCountry: text(item.issuingCountry, 'issuing country'),
      status: text(item.status, 'PID status'),
      verified: item.verified,
      createdAt: date(item.createdAt, 'created timestamp'),
      ...(item.validUntil === undefined ? {} : { validUntil: date(item.validUntil, 'valid-until timestamp') }),
    }
  })
}
