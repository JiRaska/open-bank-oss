// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

export type JournalStatus = 'PENDING' | 'POSTED' | 'REVERSED'

export interface LedgerJournalLine {
  id: string; glAccountId: string; side: 'DEBIT' | 'CREDIT'; amount: number
  currencyCode: string; baseAmount: number; baseCurrencyCode: string; sequence: number
  subAccountId: string | null
}

export interface LedgerJournalEntry {
  id: string; entryNumber: number | null; transactionId: string; entryDate: string; valueDate: string
  description: string | null; status: JournalStatus; lines: LedgerJournalLine[]; createdAt: string
  synthetic: boolean
}

export interface LedgerJournalPage {
  data: LedgerJournalEntry[]
  pagination: { limit: number; hasNextPage: boolean; nextCursor?: string; previousCursor?: string }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function text(record: Record<string, unknown>, field: string, empty = false): string {
  const value = record[field]
  if (typeof value !== 'string' || (!empty && value.trim() === '')) throw new Error(`Invalid ledger ${field}`)
  return value
}

function finite(record: Record<string, unknown>, field: string): number {
  const value = record[field]
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`Invalid ledger ${field}`)
  return value
}

function isoDate(record: Record<string, unknown>, field: string): string {
  const value = text(record, field)
  const parsed = /^\d{4}-\d{2}-\d{2}$/.test(value) ? new Date(`${value}T00:00:00Z`) : null
  if (!parsed || Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== value) {
    throw new Error(`Invalid ledger ${field}`)
  }
  return value
}

function optionalText(record: Record<string, unknown>, field: string): string | undefined {
  const value = record[field]
  if (value === undefined || value === null) return undefined
  return text(record, field)
}

function parseLine(value: unknown): LedgerJournalLine {
  if (!isRecord(value)) throw new Error('Invalid ledger line')
  const side = text(value, 'side')
  const amount = finite(value, 'amount')
  const baseAmount = finite(value, 'baseAmount')
  const sequence = finite(value, 'sequence')
  const currencyCode = text(value, 'currencyCode')
  const baseCurrencyCode = text(value, 'baseCurrencyCode')
  if (side !== 'DEBIT' && side !== 'CREDIT') throw new Error('Invalid ledger side')
  if (amount < 0 || baseAmount < 0) throw new Error('Invalid ledger amount')
  if (!Number.isInteger(sequence) || sequence < 0) throw new Error('Invalid ledger sequence')
  if (!/^[A-Z]{3}$/.test(currencyCode) || !/^[A-Z]{3}$/.test(baseCurrencyCode)) throw new Error('Invalid ledger currency')
  if (value.subAccountId !== null && value.subAccountId !== undefined && typeof value.subAccountId !== 'string') {
    throw new Error('Invalid ledger subAccountId')
  }
  return {
    id: text(value, 'id'), glAccountId: text(value, 'glAccountId'), side,
    amount, currencyCode, baseAmount, baseCurrencyCode, sequence,
    subAccountId: typeof value.subAccountId === 'string' ? value.subAccountId : null,
  }
}

function parseEntry(value: unknown): LedgerJournalEntry {
  if (!isRecord(value) || !Array.isArray(value.lines)) throw new Error('Invalid ledger entry')
  const status = text(value, 'status')
  if (!['PENDING', 'POSTED', 'REVERSED'].includes(status)) throw new Error('Invalid ledger status')
  if (value.entryNumber !== null && (typeof value.entryNumber !== 'number' || !Number.isInteger(value.entryNumber) || value.entryNumber < 0)) {
    throw new Error('Invalid ledger entryNumber')
  }
  if (value.description !== null && typeof value.description !== 'string') throw new Error('Invalid ledger description')
  if (typeof value.synthetic !== 'boolean') throw new Error('Invalid ledger synthetic provenance')
  const createdAt = text(value, 'createdAt')
  if (!Number.isFinite(Date.parse(createdAt))) throw new Error('Invalid ledger createdAt')
  const lines = value.lines.map(parseLine)
  if (new Set(lines.map(line => line.id)).size !== lines.length) throw new Error('Duplicate ledger line')
  if (new Set(lines.map(line => line.sequence)).size !== lines.length) throw new Error('Duplicate ledger sequence')
  return {
    id: text(value, 'id'), entryNumber: value.entryNumber as number | null,
    transactionId: text(value, 'transactionId'), entryDate: isoDate(value, 'entryDate'), valueDate: isoDate(value, 'valueDate'),
    description: value.description as string | null, status: status as JournalStatus,
    lines, createdAt, synthetic: value.synthetic,
  }
}

export function parseLedgerJournalPage(raw: unknown, expectedLimit: number): LedgerJournalPage {
  if (!isRecord(raw) || !Array.isArray(raw.data) || !isRecord(raw.pagination)) throw new Error('Invalid ledger page')
  if (raw.pagination.limit !== expectedLimit || raw.data.length > expectedLimit) throw new Error('Invalid ledger limit')
  if (typeof raw.pagination.hasNextPage !== 'boolean') throw new Error('Invalid ledger hasNextPage')
  const nextCursor = optionalText(raw.pagination, 'nextCursor')
  const previousCursor = optionalText(raw.pagination, 'previousCursor')
  if (raw.pagination.hasNextPage && !nextCursor) throw new Error('Invalid ledger nextCursor')
  const data = raw.data.map(parseEntry)
  if (new Set(data.map(entry => entry.id)).size !== data.length) throw new Error('Duplicate ledger entry')
  return {
    data,
    pagination: {
      limit: expectedLimit, hasNextPage: raw.pagination.hasNextPage,
      ...(nextCursor ? { nextCursor } : {}), ...(previousCursor ? { previousCursor } : {}),
    },
  }
}
