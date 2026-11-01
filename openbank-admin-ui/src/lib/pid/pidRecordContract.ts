// SPDX-License-Identifier: Apache-2.0

export interface PidRecordEvidence {
  id: string
  personId: string
  identifierType: string
  identifierValue: string
  status: string
  verified: boolean
  createdAt: string
}

export const PID_LOOKUP_TYPES = ['BANKID_SUB', 'ROB_AIFO', 'ICO', 'PASSPORT_NUMBER', 'ID_CARD_NUMBER'] as const
export type PidLookupType = typeof PID_LOOKUP_TYPES[number]

const LOOKUP_TYPES = new Set<string>(PID_LOOKUP_TYPES)
const PARTY_STATUSES = new Set(['ACTIVE', 'SUSPENDED', 'DECEASED', 'TERMINATED'])
// Java's UUID parser/persistence accepts canonical 128-bit hex values without enforcing
// RFC version/variant bits, so the browser contract must accept the same wire shape.
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function text(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`Invalid ${field}`)
  return value
}

function date(value: unknown, field: string): string {
  const candidate = text(value, field)
  if (Number.isNaN(Date.parse(candidate))) throw new Error(`Invalid ${field}`)
  return candidate
}

/**
 * The PID page performs one explicit lookup by an exact, supported external-id type/value.
 * The browser endpoint has a deliberately narrow response containing only a stable party ID,
 * status, creation time, and the requested identifier.
 */
export function parsePidExternalIdLookup(
  raw: unknown,
  type: PidLookupType,
  expectedValue: string,
): PidRecordEvidence {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) throw new Error('Invalid PID lookup response')
  const result = raw as Record<string, unknown>
  const personId = text(result.partyId, 'party id')
  if (!UUID.test(personId)) throw new Error('Invalid party id')
  const status = text(result.status, 'party status')
  if (!PARTY_STATUSES.has(status)) throw new Error('Invalid party status')
  const createdAt = date(result.createdAt, 'party created timestamp')
  const identifier = result.externalId
  if (typeof identifier !== 'object' || identifier === null || Array.isArray(identifier)) {
    throw new Error('Invalid PID lookup identifier')
  }
  const match = identifier as Record<string, unknown>
  if (match.type !== type || match.value !== expectedValue) {
    throw new Error('PID lookup did not return the requested identifier')
  }
  const verifiedAt = match.verifiedAt
  if (verifiedAt !== null && verifiedAt !== undefined &&
    (typeof verifiedAt !== 'string' || Number.isNaN(Date.parse(verifiedAt)))) {
    throw new Error('Invalid PID identifier verification timestamp')
  }
  return {
    id: `${personId}:${type}`,
    personId,
    identifierType: type,
    identifierValue: expectedValue,
    status,
    verified: typeof verifiedAt === 'string',
    createdAt,
  }
}

export function isPidLookupType(value: string): value is PidLookupType {
  return LOOKUP_TYPES.has(value)
}
