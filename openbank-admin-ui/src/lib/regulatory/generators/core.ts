// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * Report generators for the regulatory catalogue reports that finrep-service does not render
 * (SDAT payments/deposits, FAÚ SAR, ECB payment statistics, FATCA, DORA ICT incidents).
 *
 * Each generator reads the operational system of record through its existing REST API and
 * aggregates it into the same template/cell shape the FINREP/COREP preview already renders, so
 * the preview table, the export gate (`evaluateExportReadiness`) and the CSV/JSON export all work
 * unchanged. Two rules hold throughout:
 *
 *   - **No partial reads.** Every roster is walked to its end; a failed page, a malformed payload
 *     or a roster above the safety cap fails the WHOLE report (`ReportSourceError`) rather than
 *     producing an under-counted figure that looks complete.
 *   - **No invented values.** A figure the platform cannot derive (an ATM count, the MLRO contact)
 *     comes from explicit deployment configuration, or it is a flagged data gap — which the export
 *     gate refuses — never a zero.
 *
 * Generation only. Transmission to the regulator is deliberately not part of this module.
 */

export type GeneratedReportId = 'sdat-payments' | 'sdat-accounts' | 'aml-far' | 'ecb-payments' | 'fatca-report' | 'dora-ict'

export const GENERATED_REPORT_IDS: readonly GeneratedReportId[] = [
  'sdat-payments', 'sdat-accounts', 'aml-far', 'ecb-payments', 'fatca-report', 'dora-ict',
]

export function isGeneratedReportId(value: string): value is GeneratedReportId {
  return (GENERATED_REPORT_IDS as readonly string[]).includes(value)
}

export interface GeneratedCell {
  rowRef: string
  colRef: string
  /** Numeric value; ignored for display when `text` is set. */
  value: number
  /** ISO currency, `%`, or `ks` (a count). */
  currency: string
  label: string
  /** A non-numeric value (an identifier, a date, a name) rendered verbatim. */
  text?: string
  isDataGap?: boolean
  gapReason?: string | null
}

export interface GeneratedTemplate {
  templateId: string
  period: string
  cells: GeneratedCell[]
  hasDataGaps: boolean
}

export interface GeneratedReport {
  reportId: GeneratedReportId
  period: string
  periodStart: string
  periodEnd: string
  /** True when the period has ended — a report over a running period is a working preview. */
  closed: boolean
  generatedAt: string
  sources: string[]
  templates: GeneratedTemplate[]
}

export type ReportSourceFailure = 'not_deployed' | 'discovery_unavailable' | 'unauthorized' | 'unreachable' | 'error' | 'result_limit'

export class ReportSourceError extends Error {
  constructor(readonly kind: ReportSourceFailure, readonly service: string, detail: string) {
    super(`${service}: ${detail}`)
  }
}

/** Reads one service. Implementations resolve the base URL and attach the operator's token. */
export interface ReportSourceClient {
  get(service: string, path: string, query?: Record<string, string>): Promise<unknown>
  /** Status-aware variant for the few reads where 204/404/409 carry meaning. */
  request(service: string, method: 'GET' | 'POST', path: string, options?: { query?: Record<string, string>; body?: unknown }): Promise<{ status: number; body: unknown }>
}

export interface GenerationConfig {
  /** ATMs the bank operates (ECB payment statistics). Unset = data gap. */
  atmCount?: number
  /** MLRO contact for SAR (FAÚ). Unset = data gap. */
  mlroContact?: string
  /** Statutory-return reporting entity in tax-reporting-service. */
  taxEntityId: string
}

export const ROSTER_LIMIT = 20_000

// ---------------------------------------------------------------------------------------------
// Periods
// ---------------------------------------------------------------------------------------------

export type PeriodKind = 'month' | 'year'

export function periodKindOf(reportId: GeneratedReportId): PeriodKind {
  return reportId === 'ecb-payments' || reportId === 'fatca-report' ? 'year' : 'month'
}

const MONTH = /^(\d{4})-(0[1-9]|1[0-2])$/
const YEAR = /^(\d{4})$/

function iso(date: Date): string {
  return date.toISOString().slice(0, 10)
}

export function periodBounds(kind: PeriodKind, period: string): { start: string; end: string } | null {
  if (kind === 'month') {
    const m = MONTH.exec(period)
    if (!m) return null
    const y = Number(m[1])
    const mo = Number(m[2])
    return { start: iso(new Date(Date.UTC(y, mo - 1, 1))), end: iso(new Date(Date.UTC(y, mo, 0))) }
  }
  const m = YEAR.exec(period)
  if (!m) return null
  return { start: `${m[1]}-01-01`, end: `${m[1]}-12-31` }
}

/** The last COMPLETED period — the one a report is normally filed for. */
export function defaultPeriod(kind: PeriodKind, now: Date): string {
  if (kind === 'year') return String(now.getUTCFullYear() - 1)
  const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - 1, 1))
  return d.toISOString().slice(0, 7)
}

/** Selectable periods, newest first: the running period, then `count` completed ones. */
export function selectablePeriods(kind: PeriodKind, now: Date, count = 12): string[] {
  if (kind === 'year') {
    return Array.from({ length: Math.min(count, 6) }, (_, i) => String(now.getUTCFullYear() - i))
  }
  return Array.from({ length: count + 1 }, (_, i) =>
    new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - i, 1)).toISOString().slice(0, 7))
}

// ---------------------------------------------------------------------------------------------
// Deadlines (replaces the static catalogue dates, which went stale)
// ---------------------------------------------------------------------------------------------

export type DeadlineRule =
  | { kind: 'monthly-day'; day: number }
  | { kind: 'days-after-month'; days: number }
  | { kind: 'days-after-quarter'; days: number }
  | { kind: 'annual'; month: number; day: number }
  | { kind: 'ad-hoc' }

/** The next filing deadline on or after `today` (YYYY-MM-DD), or null for ad-hoc reports. */
export function nextDeadline(rule: DeadlineRule, today: Date): string | null {
  const t = iso(today)
  const y = today.getUTCFullYear()
  const m = today.getUTCMonth()
  const candidates: Date[] = []
  switch (rule.kind) {
    case 'ad-hoc':
      return null
    case 'monthly-day':
      for (let i = 0; i < 3; i++) candidates.push(new Date(Date.UTC(y, m + i, rule.day)))
      break
    case 'days-after-month':
      for (let i = -1; i < 2; i++) {
        const monthEnd = new Date(Date.UTC(y, m + i + 1, 0))
        candidates.push(new Date(monthEnd.getTime() + rule.days * 86_400_000))
      }
      break
    case 'days-after-quarter':
      for (let q = -1; q < 5; q++) {
        const quarterEndMonth = Math.floor(m / 3) * 3 + q * 3 + 3
        const quarterEnd = new Date(Date.UTC(y, quarterEndMonth, 0))
        candidates.push(new Date(quarterEnd.getTime() + rule.days * 86_400_000))
      }
      break
    case 'annual':
      candidates.push(new Date(Date.UTC(y, rule.month - 1, rule.day)), new Date(Date.UTC(y + 1, rule.month - 1, rule.day)))
      break
  }
  return candidates.map(iso).filter(d => d >= t).sort()[0] ?? null
}

// ---------------------------------------------------------------------------------------------
// Payload readers — strict: a malformed row fails the report, it is never skipped.
// ---------------------------------------------------------------------------------------------

export type Row = Record<string, unknown>

export function malformed(service: string, what: string): never {
  throw new ReportSourceError('error', service, `malformed response: ${what}`)
}

export function rows(service: string, value: unknown, key?: string): Row[] {
  const list = key && value && typeof value === 'object' && !Array.isArray(value) ? (value as Row)[key] : value
  if (!Array.isArray(list)) malformed(service, key ? `expected array at '${key}'` : 'expected array')
  return list.map(item => (item && typeof item === 'object' ? item as Row : malformed(service, 'non-object row')))
}

export function str(row: Row, field: string): string | null {
  const v = row[field]
  return typeof v === 'string' && v.length > 0 ? v : null
}

export function num(row: Row, field: string): number | null {
  const v = row[field]
  if (typeof v === 'number' && Number.isFinite(v)) return v
  if (typeof v === 'string' && v.trim() !== '' && Number.isFinite(Number(v))) return Number(v)
  return null
}

export function within(instant: string | null, start: string, end: string): boolean {
  if (!instant) return false
  const day = instant.slice(0, 10)
  return day >= start && day <= end
}

/** Offset-paginated roster (limit/offset), walked until a short page. */
export async function offsetRoster(client: ReportSourceClient, service: string, path: string, query: Record<string, string>, pageSize: number, key?: string): Promise<Row[]> {
  const all: Row[] = []
  for (let offset = 0; ; offset += pageSize) {
    const page = rows(service, await client.get(service, path, { ...query, limit: String(pageSize), offset: String(offset) }), key)
    all.push(...page)
    if (all.length > ROSTER_LIMIT) throw new ReportSourceError('result_limit', service, `more than ${ROSTER_LIMIT} rows`)
    if (page.length < pageSize) return all
  }
}

/** account-service's cursor-paginated ACTIVE roster. */
export async function activeAccounts(client: ReportSourceClient): Promise<Row[]> {
  const service = 'account-service'
  const all: Row[] = []
  let cursor: string | null = null
  for (;;) {
    const raw = await client.get(service, '/api/v1/accounts/active', { limit: '200', ...(cursor ? { cursor } : {}) })
    all.push(...rows(service, raw, 'data'))
    if (all.length > ROSTER_LIMIT) throw new ReportSourceError('result_limit', service, `more than ${ROSTER_LIMIT} accounts`)
    const pagination = (raw as Row).pagination as Row | undefined
    const next = pagination ? str(pagination, 'nextCursor') : null
    if (!next || pagination?.hasMore === false) return all
    cursor = next
  }
}

export async function completedTransactions(client: ReportSourceClient, start: string, end: string): Promise<Row[]> {
  return offsetRoster(client, 'transaction-service', '/api/v1/transactions/search', { status: 'COMPLETED', dateFrom: start, dateTo: end }, 200, 'data')
}

/** Bounded-concurrency map — per-account reads must not open thousands of sockets at once. */
export async function mapLimit<T, R>(items: T[], limit: number, fn: (item: T) => Promise<R>): Promise<R[]> {
  const out: R[] = new Array(items.length)
  let next = 0
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const i = next++
      out[i] = await fn(items[i])
    }
  }))
  return out
}

// ---------------------------------------------------------------------------------------------
// Cell helpers
// ---------------------------------------------------------------------------------------------

export function round2(n: number): number {
  return Math.round(n * 100) / 100
}

export function count(row: string, label: string, value: number): GeneratedCell {
  return { rowRef: row, colRef: 'c0010', value, currency: 'ks', label }
}

export function amount(row: string, label: string, value: number, currency: string): GeneratedCell {
  return { rowRef: row, colRef: 'c0010', value: round2(value), currency, label }
}

export function text(row: string, col: string, label: string, value: string | null, gapReason?: string): GeneratedCell {
  if (value === null && gapReason) return { rowRef: row, colRef: col, value: 0, currency: 'ks', label, isDataGap: true, gapReason }
  return { rowRef: row, colRef: col, value: 0, currency: 'ks', label, text: value ?? '—' }
}

export function gap(row: string, label: string, currency: string, reason: string): GeneratedCell {
  return { rowRef: row, colRef: 'c0010', value: 0, currency, label, isDataGap: true, gapReason: reason }
}

export function template(templateId: string, period: string, cells: GeneratedCell[]): GeneratedTemplate {
  return { templateId, period, cells, hasDataGaps: cells.some(c => c.isDataGap === true) }
}

export function sumByCurrency(items: Row[], amountField: string, currencyField: string): Map<string, number> {
  const totals = new Map<string, number>()
  for (const item of items) {
    const value = num(item, amountField)
    const currency = str(item, currencyField)
    if (value === null || !currency) malformed('transaction-service', `row without ${amountField}/${currencyField}`)
    totals.set(currency, (totals.get(currency) ?? 0) + value)
  }
  return totals
}

export function currencyCells(prefix: string, label: string, totals: Map<string, number>, start: number): GeneratedCell[] {
  const ordered = [...totals.entries()].sort(([a], [b]) => (a === 'CZK' ? -1 : b === 'CZK' ? 1 : a.localeCompare(b)))
  return ordered.map(([currency, value], i) => amount(`${prefix}${String(start + i * 10).padStart(4, '0')}`, `${label} (${currency})`, value, currency))
}


// ---------------------------------------------------------------------------------------------
// Shared classification
// ---------------------------------------------------------------------------------------------

const PAYMENT_RAILS_EXCLUDED = new Set(['FEE', 'INTEREST'])
const PAYMENT_TYPES_EXCLUDED = new Set(['FEE', 'INTEREST', 'ADJUSTMENT', 'REVERSAL'])

export function isPayment(tx: Row): boolean {
  return !PAYMENT_RAILS_EXCLUDED.has(str(tx, 'rail') ?? '') && !PAYMENT_TYPES_EXCLUDED.has(str(tx, 'type') ?? '')
}

