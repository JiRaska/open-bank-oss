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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function requiredString(record: Record<string, unknown>, field: string): string {
  const value = record[field]
  if (typeof value !== 'string' || value.trim() === '') throw new Error(`Invalid SDD ${field}`)
  return value
}

function stringValue(record: Record<string, unknown>, field: string): string {
  const value = record[field]
  if (typeof value !== 'string') throw new Error(`Invalid SDD ${field}`)
  return value
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
  const at = requiredString(value, 'at')
  if (Number.isNaN(Date.parse(at))) throw new Error('Invalid SDD amendment at')
  return {
    field: requiredString(value, 'field'),
    oldValue: stringValue(value, 'oldValue'),
    newValue: stringValue(value, 'newValue'),
    at,
  }
}

function parseMandate(value: unknown): SddMandate {
  if (!isRecord(value)) throw new Error('Invalid SDD mandate')
  const scheme = requiredString(value, 'scheme')
  const sequenceType = requiredString(value, 'sequenceType')
  const status = requiredString(value, 'status')
  const createdAt = requiredString(value, 'createdAt')
  if (!SCHEMES.has(scheme)) throw new Error('Invalid SDD scheme')
  if (!SEQUENCES.has(sequenceType)) throw new Error('Invalid SDD sequenceType')
  if (!STATUSES.has(status)) throw new Error('Invalid SDD status')
  if (typeof value.b2bConfirmed !== 'boolean') throw new Error('Invalid SDD b2bConfirmed')
  if (Number.isNaN(Date.parse(createdAt))) throw new Error('Invalid SDD createdAt')
  if (!Array.isArray(value.amendments)) throw new Error('Invalid SDD amendments')

  return {
    id: requiredString(value, 'id'),
    accountId: requiredString(value, 'accountId'),
    debtorIban: requiredString(value, 'debtorIban'),
    creditorIdentifier: requiredString(value, 'creditorIdentifier'),
    umr: requiredString(value, 'umr'),
    scheme: scheme as SddScheme,
    sequenceType: sequenceType as SddSequenceType,
    creditorName: requiredString(value, 'creditorName'),
    debtorName: requiredString(value, 'debtorName'),
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
  return raw.map(parseMandate)
}
