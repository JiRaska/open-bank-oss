// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

export interface CurrencyReconciliation {
  currency: string
  ledgerControlBalance: number | string
  subLedgerBookedSum: number | string
  difference: number | string
  withinTolerance: boolean
  futureValueDatedPipeline?: number | string
}

export interface ReconciliationReport {
  asOf: string
  generatedAt: string
  tolerance: number | string
  currencies: CurrencyReconciliation[]
}

export interface CloseRun {
  id: string
  trigger: 'SCHEDULED' | 'MANUAL'
  status: 'RUNNING' | 'COMPLETED' | 'COMPLETED_WITH_FAILURES'
  periodFrom: string | null
  periodTo: string | null
  accountsEnumerated: number
  pocketsClosed: number
  pocketsFailed: number
  pocketsSkipped: number
  startedAt: string
  finishedAt: string | null
}

export interface CloseFailure {
  id: string
  runId: string
  accountId: string
  pocketCurrency: string
  periodFrom: string
  periodTo: string
  reason: 'RECONCILIATION' | 'UPSTREAM' | 'UNKNOWN'
  detail: string | null
  failedAt: string
}

export interface EvidenceResult<T> {
  value: T | null
  excludedCount: number
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/
const RFC3339 = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/
const DECIMAL = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/
const MAX_ROWS = 1_000
const isText = (value: unknown, maxLength = 5_000): value is string =>
  typeof value === 'string' && value.trim().length > 0 && value.length <= maxLength
const isUuid = (value: unknown): value is string => isText(value)
  && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)
const isCurrency = (value: unknown): value is string => isText(value) && /^[A-Z]{3}$/.test(value)
const isDate = (value: unknown): value is string => isText(value) && ISO_DATE.test(value)
  && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value
const isInstant = (value: unknown): value is string => isText(value) && RFC3339.test(value)
  && !Number.isNaN(Date.parse(value))
const isNullableDate = (value: unknown): value is string | null => value === null || isDate(value)
const isAmount = (value: unknown): value is number | string =>
  ((typeof value === 'number' && Number.isFinite(value)) ||
    (isText(value, 100) && DECIMAL.test(value.trim()) && Number.isFinite(Number(value))))
const isCount = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0

const isCurrencyRow = (value: unknown): value is CurrencyReconciliation => {
  if (!isRecord(value)) return false
  return isCurrency(value.currency)
    && isAmount(value.ledgerControlBalance)
    && isAmount(value.subLedgerBookedSum)
    && isAmount(value.difference)
    && typeof value.withinTolerance === 'boolean'
    && (value.futureValueDatedPipeline === undefined || isAmount(value.futureValueDatedPipeline))
}

export function parseReconciliationReport(raw: unknown): EvidenceResult<ReconciliationReport> {
  if (!isRecord(raw) || !isDate(raw.asOf) || !isInstant(raw.generatedAt)
    || !isAmount(raw.tolerance) || Number(raw.tolerance) < 0 || !Array.isArray(raw.currencies)
    || raw.currencies.length > MAX_ROWS) {
    return { value: null, excludedCount: 0 }
  }
  const currencies = raw.currencies.filter((row): row is CurrencyReconciliation =>
    isCurrencyRow(row) && row.withinTolerance === (Math.abs(Number(row.difference)) <= Number(raw.tolerance)))
  if (new Set(currencies.map(row => row.currency)).size !== currencies.length) {
    return { value: null, excludedCount: 0 }
  }
  return {
    value: { asOf: raw.asOf, generatedAt: raw.generatedAt, tolerance: raw.tolerance, currencies },
    excludedCount: raw.currencies.length - currencies.length,
  }
}

export const isCloseRun = (value: unknown): value is CloseRun => {
  if (!isRecord(value)) return false
  const validShape = isUuid(value.id)
    && (value.trigger === 'SCHEDULED' || value.trigger === 'MANUAL')
    && (value.status === 'RUNNING' || value.status === 'COMPLETED' || value.status === 'COMPLETED_WITH_FAILURES')
    && isNullableDate(value.periodFrom)
    && isNullableDate(value.periodTo)
    && isCount(value.accountsEnumerated)
    && isCount(value.pocketsClosed)
    && isCount(value.pocketsFailed)
    && isCount(value.pocketsSkipped)
    && isInstant(value.startedAt)
    && (value.finishedAt === null || isInstant(value.finishedAt))
  if (!validShape) return false
  const run = value as unknown as CloseRun
  if ((run.periodFrom === null) !== (run.periodTo === null)) return false
  if (run.periodFrom !== null && run.periodTo !== null && run.periodFrom > run.periodTo) return false
  if (run.status === 'RUNNING') return run.finishedAt === null
  if (run.finishedAt === null || Date.parse(run.finishedAt) < Date.parse(run.startedAt)) return false
  return run.status === 'COMPLETED' ? run.pocketsFailed === 0 : run.pocketsFailed > 0
}

export function parseCloseRun(raw: unknown): CloseRun | null {
  return isCloseRun(raw) ? raw : null
}

export function parseCloseRuns(raw: unknown): EvidenceResult<CloseRun[]> {
  if (!Array.isArray(raw) || raw.length > MAX_ROWS) return { value: null, excludedCount: 0 }
  const valid = raw.filter(isCloseRun)
  const counts = new Map<string, number>()
  valid.forEach(run => counts.set(run.id, (counts.get(run.id) ?? 0) + 1))
  const value = valid.filter(run => counts.get(run.id) === 1)
    .sort((left, right) => Date.parse(right.startedAt) - Date.parse(left.startedAt))
  return { value, excludedCount: raw.length - value.length }
}

const isCloseFailure = (value: unknown): value is CloseFailure => {
  if (!isRecord(value)) return false
  return isUuid(value.id)
    && isUuid(value.runId)
    && isUuid(value.accountId)
    && isCurrency(value.pocketCurrency)
    && isDate(value.periodFrom)
    && isDate(value.periodTo)
    && (value.reason === 'RECONCILIATION' || value.reason === 'UPSTREAM' || value.reason === 'UNKNOWN')
    && (value.detail === null || isText(value.detail))
    && isInstant(value.failedAt)
    && value.periodFrom <= value.periodTo
}

export function parseCloseFailures(raw: unknown, expectedRunId?: string): EvidenceResult<CloseFailure[]> {
  if (!Array.isArray(raw) || raw.length > MAX_ROWS) return { value: null, excludedCount: 0 }
  const valid = raw.filter((item): item is CloseFailure =>
    isCloseFailure(item) && (expectedRunId === undefined || item.runId === expectedRunId))
  const counts = new Map<string, number>()
  valid.forEach(failure => counts.set(failure.id, (counts.get(failure.id) ?? 0) + 1))
  const value = valid.filter(failure => counts.get(failure.id) === 1)
  return { value, excludedCount: raw.length - value.length }
}
