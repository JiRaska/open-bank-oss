// SPDX-License-Identifier: Apache-2.0

export const CONSENT_STATUSES = ['PENDING_SCA', 'ACTIVE', 'EXPIRED', 'REVOKED', 'REJECTED', 'SUPERSEDED'] as const
export type ConsentStatus = (typeof CONSENT_STATUSES)[number]

export const CONSENT_SCOPES = [
  'ACCOUNTS_READ', 'BALANCES_READ', 'TRANSACTIONS_READ', 'STATEMENTS_READ', 'PAYMENT_ACCOUNTS_READ',
  'STANDING_ORDERS_READ', 'DIRECT_DEBITS_READ', 'PAYMENTS_INITIATE', 'PAYMENTS_STATUS_READ',
  'DOMESTIC_PAYMENT_INITIATE', 'SIPO_PAYMENT_INITIATE', 'FUNDS_CONFIRMATION', 'AGENT_QUERY',
  'AGENT_INITIATE', 'AGENT_NOTIFY', 'AGENT_ANALYZE', 'TELEMETRY_RUM', 'MARKETING_COMMS_EMAIL',
  'MARKETING_COMMS_PUSH', 'MARKETING_COMMS_INAPP', 'CREDIT_OFFERS', 'CREDIT_PROFILE_USE', 'CREDIT_AI_AGENT',
] as const
export type ConsentScope = (typeof CONSENT_SCOPES)[number]

export const GRANTEE_TYPES = ['TPP', 'BANK_AGENT', 'CUSTOMER_AGENT', 'INTERNAL_SERVICE'] as const
export type GranteeType = (typeof GRANTEE_TYPES)[number]

export interface ConsentEvidence {
  id: string
  partyId: string
  granteeId: string
  granteeType: GranteeType
  granteeName: string
  scopes: ConsentScope[]
  accountIbans: string[] | null
  status: ConsentStatus
  validFrom: string
  validTo: string
  createdAt: string
}

export interface ConsentEvidenceResult {
  value: ConsentEvidence[] | null
  excludedCount: number
}

const STATUSES = new Set<string>(CONSENT_STATUSES)
const SCOPES = new Set<string>(CONSENT_SCOPES)
const TYPES = new Set<string>(GRANTEE_TYPES)
// Match java.util.UUID's wire shape without constraining the version nibble; the service contract
// accepts UUIDs generally and may legitimately move from v4 to a newer allocation strategy.
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function requiredString(record: Record<string, unknown>, field: string): string {
  const value = record[field]
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`Invalid consent ${field}`)
  return value
}

function timestamp(record: Record<string, unknown>, field: string): string {
  const value = requiredString(record, field)
  if (Number.isNaN(Date.parse(value))) throw new Error(`Invalid consent ${field}`)
  return value
}

function parseConsent(value: unknown): ConsentEvidence {
  if (!isRecord(value)) throw new Error('Invalid consent evidence')
  const id = requiredString(value, 'id')
  const partyId = requiredString(value, 'partyId')
  const granteeType = requiredString(value, 'granteeType')
  const status = requiredString(value, 'status')
  const validFrom = timestamp(value, 'validFrom')
  const validTo = timestamp(value, 'validTo')
  const createdAt = timestamp(value, 'createdAt')
  if (!UUID.test(id) || !UUID.test(partyId)) throw new Error('Invalid consent identifier')
  if (!TYPES.has(granteeType)) throw new Error('Invalid consent granteeType')
  if (!STATUSES.has(status)) throw new Error('Invalid consent status')
  if (!Array.isArray(value.scopes) || value.scopes.length === 0 || !value.scopes.every(scope => typeof scope === 'string' && SCOPES.has(scope))) {
    throw new Error('Invalid consent scopes')
  }
  if (value.accountIbans !== null && (!Array.isArray(value.accountIbans) || !value.accountIbans.every(iban => typeof iban === 'string' && iban.trim() !== ''))) {
    throw new Error('Invalid consent accountIbans')
  }
  if (Date.parse(validTo) <= Date.parse(validFrom)) throw new Error('Invalid consent validity')

  return {
    id,
    partyId,
    granteeId: requiredString(value, 'granteeId'),
    granteeType: granteeType as GranteeType,
    granteeName: requiredString(value, 'granteeName'),
    scopes: value.scopes as ConsentScope[],
    accountIbans: value.accountIbans as string[] | null,
    status: status as ConsentStatus,
    validFrom,
    validTo,
    createdAt,
  }
}

export function parseConsentEvidenceList(raw: unknown): ConsentEvidenceResult {
  if (!Array.isArray(raw)) return { value: null, excludedCount: 0 }
  const value: ConsentEvidence[] = []
  for (const candidate of raw) {
    try {
      value.push(parseConsent(candidate))
    } catch {
      // Keep independently valid consent evidence visible; the caller discloses every exclusion.
    }
  }
  return { value, excludedCount: raw.length - value.length }
}
