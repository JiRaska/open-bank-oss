// SPDX-License-Identifier: Apache-2.0

export const JOURNAL_STATUSES = ['PENDING', 'POSTED', 'REVERSED'] as const
export type JournalStatus = (typeof JOURNAL_STATUSES)[number]

export interface LedgerJournalLine {
  id: string
  glAccountId: string
  side: 'DEBIT' | 'CREDIT'
  amount: number
  currencyCode: string
  baseAmount: number
  baseCurrencyCode: string
  sequence: number
  subAccountId: string | null
}

export interface LedgerJournalEntry {
  id: string
  entryNumber: number | null
  transactionId: string
  entryDate: string
  valueDate: string
  description: string | null
  status: JournalStatus
  lines: LedgerJournalLine[]
  createdAt: string
  synthetic: boolean
}

export interface LedgerJournalPage {
  data: LedgerJournalEntry[]
  pagination: {
    limit: number
    hasNextPage: boolean
    nextCursor?: string
    previousCursor?: string
    totalCount?: number
  }
}

const STATUSES = new Set<string>(JOURNAL_STATUSES)

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function stringField(record: Record<string, unknown>, field: string, allowEmpty = false): string {
  const value = record[field]
  if (typeof value !== 'string' || (!allowEmpty && value.trim() === '')) throw new Error(`Invalid ledger ${field}`)
  return value
}

function nullableString(record: Record<string, unknown>, field: string): string | null {
  const value = record[field]
  if (value === null) return null
  return stringField(record, field, true)
}

function finiteNumber(record: Record<string, unknown>, field: string): number {
  const value = record[field]
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`Invalid ledger ${field}`)
  return value
}

function dateOnly(record: Record<string, unknown>, field: string): string {
  const value = stringField(record, field)
  const parsed = /^\d{4}-\d{2}-\d{2}$/.test(value) ? new Date(`${value}T00:00:00Z`) : null
  if (!parsed || Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== value) {
    throw new Error(`Invalid ledger ${field}`)
  }
  return value
}

function parseLine(value: unknown): LedgerJournalLine {
  if (!isRecord(value)) throw new Error('Invalid ledger line')
  const side = stringField(value, 'side')
  const amount = finiteNumber(value, 'amount')
  const baseAmount = finiteNumber(value, 'baseAmount')
  const sequence = finiteNumber(value, 'sequence')
  const currencyCode = stringField(value, 'currencyCode')
  const baseCurrencyCode = stringField(value, 'baseCurrencyCode')
  if (side !== 'DEBIT' && side !== 'CREDIT') throw new Error('Invalid ledger side')
  if (amount < 0 || baseAmount < 0) throw new Error('Invalid ledger amount')
  if (!Number.isInteger(sequence) || sequence < 0) throw new Error('Invalid ledger sequence')
  if (!/^[A-Z]{3}$/.test(currencyCode) || !/^[A-Z]{3}$/.test(baseCurrencyCode)) throw new Error('Invalid ledger currency')
  if (value.subAccountId !== null && typeof value.subAccountId !== 'string') throw new Error('Invalid ledger subAccountId')

  return {
    id: stringField(value, 'id'),
    glAccountId: stringField(value, 'glAccountId'),
    side,
    amount,
    currencyCode,
    baseAmount,
    baseCurrencyCode,
    sequence,
    subAccountId: value.subAccountId,
  }
}

function parseEntry(value: unknown): LedgerJournalEntry {
  if (!isRecord(value)) throw new Error('Invalid ledger entry')
  const status = stringField(value, 'status')
  const entryNumber = value.entryNumber
  const createdAt = stringField(value, 'createdAt')
  if (!STATUSES.has(status)) throw new Error('Invalid ledger status')
  if (entryNumber !== null && (typeof entryNumber !== 'number' || !Number.isInteger(entryNumber))) throw new Error('Invalid ledger entryNumber')
  if (value.description !== null && typeof value.description !== 'string') throw new Error('Invalid ledger description')
  if (!Array.isArray(value.lines)) throw new Error('Invalid ledger lines')
  if (typeof value.synthetic !== 'boolean') throw new Error('Invalid ledger synthetic')
  if (Number.isNaN(Date.parse(createdAt))) throw new Error('Invalid ledger createdAt')

  return {
    id: stringField(value, 'id'),
    entryNumber,
    transactionId: stringField(value, 'transactionId'),
    entryDate: dateOnly(value, 'entryDate'),
    valueDate: dateOnly(value, 'valueDate'),
    description: nullableString(value, 'description'),
    status: status as JournalStatus,
    lines: value.lines.map(parseLine),
    createdAt,
    synthetic: value.synthetic,
  }
}

function optionalCursor(record: Record<string, unknown>, field: string): string | undefined {
  const value = record[field]
  if (value === undefined || value === null) return undefined
  if (typeof value !== 'string' || value === '') throw new Error(`Invalid ledger ${field}`)
  return value
}

export function parseLedgerJournalPage(raw: unknown): LedgerJournalPage {
  if (!isRecord(raw) || !Array.isArray(raw.data) || !isRecord(raw.pagination)) throw new Error('Invalid ledger page')
  const limit = finiteNumber(raw.pagination, 'limit')
  const hasNextPage = raw.pagination.hasNextPage
  const nextCursor = optionalCursor(raw.pagination, 'nextCursor')
  const previousCursor = optionalCursor(raw.pagination, 'previousCursor')
  const totalCount = raw.pagination.totalCount
  if (!Number.isInteger(limit) || limit <= 0) throw new Error('Invalid ledger limit')
  if (typeof hasNextPage !== 'boolean') throw new Error('Invalid ledger hasNextPage')
  if (hasNextPage && !nextCursor) throw new Error('Invalid ledger nextCursor')
  if (totalCount !== undefined && totalCount !== null && (typeof totalCount !== 'number' || !Number.isInteger(totalCount) || totalCount < 0)) {
    throw new Error('Invalid ledger totalCount')
  }

  return {
    data: raw.data.map(parseEntry),
    pagination: {
      limit,
      hasNextPage,
      ...(nextCursor ? { nextCursor } : {}),
      ...(previousCursor ? { previousCursor } : {}),
      ...(typeof totalCount === 'number' ? { totalCount } : {}),
    },
  }
}
