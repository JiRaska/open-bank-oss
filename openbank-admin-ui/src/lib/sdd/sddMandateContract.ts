// SPDX-License-Identifier: Apache-2.0

export const SDD_SCHEMES = ['CORE', 'B2B'] as const
export const SDD_SEQUENCE_TYPES = ['OOFF', 'FRST', 'RCUR', 'FNAL'] as const
export const SDD_MANDATE_STATUSES = ['PENDING_CONFIRMATION', 'ACTIVE', 'SUSPENDED', 'CANCELLED', 'EXPIRED'] as const

export type SddScheme = (typeof SDD_SCHEMES)[number]
export type SddSequenceType = (typeof SDD_SEQUENCE_TYPES)[number]
export type SddMandateStatus = (typeof SDD_MANDATE_STATUSES)[number]

export interface SddMandate {
  id: string
  accountId: string
  debtorIban: string
  creditorIdentifier: string
  umr: string
  scheme: SddScheme
  sequenceType: SddSequenceType
  creditorName: string
  debtorName: string
  signatureDate: string
  status: SddMandateStatus
  b2bConfirmed: boolean
  lastCollectionDate: string | null
  lastPreNotificationDate: string | null
  createdAt: string
  amendments: Array<{ field: string; oldValue: string; newValue: string; at: string }>
}

const SCHEMES = new Set<string>(SDD_SCHEMES)
const SEQUENCES = new Set<string>(SDD_SEQUENCE_TYPES)
const STATUSES = new Set<string>(SDD_MANDATE_STATUSES)
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const IBAN = /^[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}$/
const CREDITOR_ID = /^[A-Z]{2}[0-9]{2}[A-Z0-9]{4,31}$/
const RFC3339 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function requiredString(record: Record<string, unknown>, field: string, maxLength: number): string {
  const value = record[field]
  if (typeof value !== 'string' || value.trim() === '' || value.length > maxLength) throw new Error(`Invalid SDD ${field}`)
  return value
}

function stringValue(record: Record<string, unknown>, field: string, maxLength: number): string {
  const value = record[field]
  if (typeof value !== 'string' || value.length > maxLength) throw new Error(`Invalid SDD ${field}`)
  return value
}

function isValidIban(value: string): boolean {
  if (!IBAN.test(value)) return false
  const rearranged = `${value.slice(4)}${value.slice(0, 4)}`
  let remainder = 0
  for (const character of rearranged) {
    const digits = /[A-Z]/.test(character) ? String(character.charCodeAt(0) - 55) : character
    for (const digit of digits) remainder = (remainder * 10 + Number(digit)) % 97
  }
  return remainder === 1
}

function dateOnly(record: Record<string, unknown>, field: string, nullable = false): string | null {
  const value = record[field]
  if (nullable && value === null) return null
  const parsed = typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)
    ? new Date(`${value}T00:00:00Z`)
    : null
  if (!parsed || Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== value) {
    throw new Error(`Invalid SDD ${field}`)
  }
  return value
}

function parseAmendment(value: unknown): SddMandate['amendments'][number] {
  if (!isRecord(value)) throw new Error('Invalid SDD amendment')
  const at = requiredString(value, 'at', 35)
  if (!RFC3339.test(at) || Number.isNaN(Date.parse(at))) throw new Error('Invalid SDD amendment at')
  return {
    field: requiredString(value, 'field', 64),
    oldValue: stringValue(value, 'oldValue', 140),
    newValue: stringValue(value, 'newValue', 140),
    at,
  }
}

function parseMandate(value: unknown): SddMandate {
  if (!isRecord(value)) throw new Error('Invalid SDD mandate')
  const id = requiredString(value, 'id', 36)
  const accountId = requiredString(value, 'accountId', 36)
  const debtorIban = requiredString(value, 'debtorIban', 34)
  const creditorIdentifier = requiredString(value, 'creditorIdentifier', 35)
  const scheme = requiredString(value, 'scheme', 8)
  const sequenceType = requiredString(value, 'sequenceType', 8)
  const status = requiredString(value, 'status', 24)
  const createdAt = requiredString(value, 'createdAt', 35)
  if (!SCHEMES.has(scheme)) throw new Error('Invalid SDD scheme')
  if (!SEQUENCES.has(sequenceType)) throw new Error('Invalid SDD sequenceType')
  if (!STATUSES.has(status)) throw new Error('Invalid SDD status')
  if (!UUID.test(id) || !UUID.test(accountId)) throw new Error('Invalid SDD identity')
  if (!isValidIban(debtorIban)) throw new Error('Invalid SDD debtorIban')
  if (!CREDITOR_ID.test(creditorIdentifier)) throw new Error('Invalid SDD creditorIdentifier')
  if (typeof value.b2bConfirmed !== 'boolean') throw new Error('Invalid SDD b2bConfirmed')
  if (!RFC3339.test(createdAt) || Number.isNaN(Date.parse(createdAt))) throw new Error('Invalid SDD createdAt')
  if (!Array.isArray(value.amendments)) throw new Error('Invalid SDD amendments')
  if (scheme === 'CORE' && value.b2bConfirmed) throw new Error('Invalid SDD B2B confirmation')
  if (status === 'PENDING_CONFIRMATION' && (scheme !== 'B2B' || value.b2bConfirmed)) {
    throw new Error('Invalid SDD pending confirmation')
  }

  return {
    id,
    accountId,
    debtorIban,
    creditorIdentifier,
    umr: requiredString(value, 'umr', 35),
    scheme: scheme as SddScheme,
    sequenceType: sequenceType as SddSequenceType,
    creditorName: requiredString(value, 'creditorName', 140),
    debtorName: requiredString(value, 'debtorName', 140),
    signatureDate: dateOnly(value, 'signatureDate')!,
    status: status as SddMandateStatus,
    b2bConfirmed: value.b2bConfirmed,
    lastCollectionDate: dateOnly(value, 'lastCollectionDate', true),
    lastPreNotificationDate: dateOnly(value, 'lastPreNotificationDate', true),
    createdAt,
    amendments: value.amendments.map(parseAmendment),
  }
}

export function parseSddMandates(raw: unknown): SddMandate[] {
  if (!Array.isArray(raw)) throw new Error('Invalid SDD mandate list')
  const mandates = raw.map(parseMandate)
  if (new Set(mandates.map(mandate => mandate.id)).size !== mandates.length) throw new Error('Duplicate SDD mandate id')
  if (new Set(mandates.map(mandate => `${mandate.creditorIdentifier}\u0000${mandate.umr}`)).size !== mandates.length) {
    throw new Error('Duplicate SDD mandate reference')
  }
  return mandates
}
