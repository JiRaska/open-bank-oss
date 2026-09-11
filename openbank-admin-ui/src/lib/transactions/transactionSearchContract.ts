// SPDX-License-Identifier: Apache-2.0

export const TRANSACTION_TYPES = ['DEBIT', 'CREDIT', 'TRANSFER', 'FEE', 'INTEREST', 'REVERSAL', 'ADJUSTMENT'] as const
export type TransactionType = (typeof TRANSACTION_TYPES)[number]
export const TRANSACTION_STATUSES = ['PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'REVERSED'] as const
export type TransactionStatus = (typeof TRANSACTION_STATUSES)[number]

export interface TransactionSearchRow {
  id: string
  referenceNumber: string
  type: TransactionType
  sourceAccountId: string | null
  targetAccountId: string | null
  amount: number
  currencyCode: string
  status: TransactionStatus
  description: string | null
  valueDate: string
  bookingDate: string
  initiatedAt: string
  completedAt: string | null
}

export interface TransactionSearchResult {
  data: TransactionSearchRow[]
  count: number
  limit: number
  offset: number
}

const TYPES = new Set<string>(TRANSACTION_TYPES)
const STATUSES = new Set<string>(TRANSACTION_STATUSES)
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const RFC3339 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function stringField(record: Record<string, unknown>, field: string, maxLength = 500): string {
  const value = record[field]
  if (typeof value !== 'string' || value.trim() === '' || value.length > maxLength) throw new Error(`Invalid transaction ${field}`)
  return value
}

function nullableString(record: Record<string, unknown>, field: string): string | null {
  const value = record[field]
  if (value === null) return null
  return stringField(record, field)
}

function numberField(record: Record<string, unknown>, field: string): number {
  const value = record[field]
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`Invalid transaction ${field}`)
  return value
}

function dateField(record: Record<string, unknown>, field: string): string {
  const value = stringField(record, field)
  const parsed = /^\d{4}-\d{2}-\d{2}$/.test(value) ? new Date(`${value}T00:00:00Z`) : null
  if (!parsed || Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== value) {
    throw new Error(`Invalid transaction ${field}`)
  }
  return value
}

function timestampField(record: Record<string, unknown>, field: string): string {
  const value = stringField(record, field)
  if (!RFC3339.test(value) || Number.isNaN(Date.parse(value))) throw new Error(`Invalid transaction ${field}`)
  return value
}

function nullableUuid(record: Record<string, unknown>, field: string): string | null {
  const value = record[field]
  if (value === null) return null
  const parsed = stringField(record, field)
  if (!UUID.test(parsed)) throw new Error(`Invalid transaction ${field}`)
  return parsed
}

function parseRow(value: unknown): TransactionSearchRow {
  if (!isRecord(value)) throw new Error('Invalid transaction row')
  const id = stringField(value, 'id')
  const type = stringField(value, 'type')
  const status = stringField(value, 'status')
  const amount = numberField(value, 'amount')
  const currencyCode = stringField(value, 'currencyCode')
  const initiatedAt = timestampField(value, 'initiatedAt')
  const completedAt = value.completedAt === null ? null : timestampField(value, 'completedAt')
  if (!UUID.test(id)) throw new Error('Invalid transaction id')
  if (!TYPES.has(type)) throw new Error('Invalid transaction type')
  if (!STATUSES.has(status)) throw new Error('Invalid transaction status')
  if (amount <= 0) throw new Error('Invalid transaction amount')
  if (!/^[A-Z]{3}$/.test(currencyCode)) throw new Error('Invalid transaction currencyCode')

  return {
    id,
    referenceNumber: stringField(value, 'referenceNumber', 200),
    type: type as TransactionType,
    sourceAccountId: nullableUuid(value, 'sourceAccountId'),
    targetAccountId: nullableUuid(value, 'targetAccountId'),
    amount,
    currencyCode,
    status: status as TransactionStatus,
    description: nullableString(value, 'description'),
    valueDate: dateField(value, 'valueDate'),
    bookingDate: dateField(value, 'bookingDate'),
    initiatedAt,
    completedAt,
  }
}

export function parseTransactionSearchResult(raw: unknown): TransactionSearchResult {
  if (!isRecord(raw) || !Array.isArray(raw.data)) throw new Error('Invalid transaction search result')
  const count = numberField(raw, 'count')
  const limit = numberField(raw, 'limit')
  const offset = numberField(raw, 'offset')
  if (![count, limit, offset].every(Number.isInteger) || count < 0 || limit <= 0 || limit > 200 || offset < 0) {
    throw new Error('Invalid transaction pagination')
  }
  if (count !== raw.data.length || count > limit) throw new Error('Invalid transaction count')
  const data = raw.data.map(parseRow)
  if (new Set(data.map(row => row.id)).size !== data.length) throw new Error('Duplicate transaction id')
  for (const row of data) {
    const completed = row.status === 'COMPLETED' || row.status === 'REVERSED'
    if (completed !== (row.completedAt !== null)) throw new Error('Invalid transaction lifecycle')
    if (row.completedAt && Date.parse(row.completedAt) < Date.parse(row.initiatedAt)) throw new Error('Invalid transaction lifecycle')
  }
  return { data, count, limit, offset }
}
