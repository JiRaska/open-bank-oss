// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The governed query registry — ADR-0286 (issue #8943).
//
// This file IS the audit inventory of what the Admin UI reports from the ClickHouse warehouse.
// Every entry is a named, curated query over a GOLD view (never bronze, never silver directly —
// one definition of each business figure lives with the schema, in a V__ migration), with a
// declared parameter schema and a required permission. The browser selects a query by name and
// supplies parameters; it NEVER sends SQL.
//
// THE PARAMETER VALIDATOR IS THE INJECTION BOUNDARY. ClickHouse's HTTP interface takes raw SQL and
// the BFF route builds it, so the only values ever interpolated into a query string are ones that
// passed the per-type validators below — the same role UUID_RE plays in /api/customer-360/[partyId],
// made declarative. A new parameter type lands here with its validator, never ad-hoc in an entry.

import type { Permission } from '@/lib/auth/roles'

const DB = 'openbank_analytics'

// ── Parameter types and their validators ─────────────────────────────────────

const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/

/**
 * Shape AND calendar range: DATE_RE alone accepts '2026-13-40', which is injection-safe (no quote)
 * but reaches ClickHouse as a toDate() error and degrades a well-formed report into a fake
 * "warehouse unavailable" — a bad date must fail at the boundary, where it can be named.
 */
function isIsoDate(value: string): boolean {
  const m = DATE_RE.exec(value)
  if (!m) return false
  const month = Number(m[2])
  const day = Number(m[3])
  if (month < 1 || month > 12 || day < 1 || day > 31) return false
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}

const MONTH_RE = /^(\d{4})-(\d{2})$/

/**
 * An accounting PERIOD, `YYYY-MM`. Shape and calendar month, for the same reason as [isIsoDate]:
 * '2026-13' carries no quote and is injection-safe, but reaches ClickHouse as a comparison that
 * silently matches nothing, turning a typo into an empty report rather than a named error.
 *
 * The format is not a guess. `LoanProvisioningEntity.period` is `@Column(name = "period",
 * length = 7)` and lending-service's own tests pin the values — `runProvisioningCycle("2026-06",
 * LocalDate.parse("2026-06-01"), ...)` and `period = "2026-08"`.
 */
function isIsoMonth(value: string): boolean {
  const m = MONTH_RE.exec(value)
  if (!m) return false
  const month = Number(m[2])
  return month >= 1 && month <= 12
}

export type ParamValue = string

export interface ReportParam {
  name: string
  labelCs: string
  labelEn: string
  type: 'date' | 'month' | 'number' | 'enum'
  required: boolean
  /** Fallback applied when a non-required parameter is absent. Must itself validate. */
  defaultValue?: string
  /** Closed allow-list for type 'enum'. */
  options?: readonly string[]
}

/**
 * Validate one parameter value. Returns the canonical value to interpolate, or null when invalid.
 * The returned value is what the SQL builder receives — validators therefore canonicalise (a
 * number is re-serialised from Number(), never passed through as raw text).
 */
export function validateParam(param: ReportParam, raw: string | null): string | null {
  const value = raw ?? param.defaultValue ?? null
  if (value === null || value === '') {
    return param.required ? null : null
  }
  switch (param.type) {
    case 'date':
      return isIsoDate(value) ? value : null
    case 'month':
      return isIsoMonth(value) ? value : null
    case 'number': {
      if (!/^\d+(\.\d+)?$/.test(value)) return null
      const n = Number(value)
      return Number.isFinite(n) && n >= 0 ? String(n) : null
    }
    case 'enum':
      return param.options?.includes(value) ? value : null
  }
}

// ── Registry entries ─────────────────────────────────────────────────────────

export interface ReportColumn {
  key: string
  labelCs: string
  labelEn: string
  format: 'text' | 'number' | 'money' | 'datetime'
}

export interface ReportEntry {
  /** Slug used in /api/reporting/[queryId]. */
  id: string
  titleCs: string
  titleEn: string
  descriptionCs: string
  descriptionEn: string
  /** Enforced at the BFF boundary via requireApiPermission. */
  permission: Permission
  params: readonly ReportParam[]
  columns: readonly ReportColumn[]
  /**
   * Build the SQL for VALIDATED parameter values only (keys = param names, values passed
   * validateParam). Never read req/nextUrl here — the route owns validation.
   */
  sql: (params: Record<string, string>) => string
}

const FROM: ReportParam = { name: 'from', labelCs: 'Od', labelEn: 'From', type: 'date', required: true }
const TO: ReportParam = { name: 'to', labelCs: 'Do', labelEn: 'To', type: 'date', required: true }

const dayRange = (p: Record<string, string>, column = 'day') =>
  `${column} BETWEEN toDate('${p.from}') AND toDate('${p.to}')`

const FROM_MONTH: ReportParam = {
  name: 'fromMonth', labelCs: 'Od období', labelEn: 'From period', type: 'month', required: true,
}
const TO_MONTH: ReportParam = {
  name: 'toMonth', labelCs: 'Do období', labelEn: 'To period', type: 'month', required: true,
}

/**
 * `period` is a `YYYY-MM` String column, not a date, so the range is a lexicographic BETWEEN.
 * That is exact for this format — zero-padded, fixed width, most-significant first — and it is
 * the reason the validator insists on both halves rather than accepting `2026-9`.
 */
const monthRange = (p: Record<string, string>, column = 'period') =>
  `${column} BETWEEN '${p.fromMonth}' AND '${p.toMonth}'`

export const REPORT_REGISTRY: readonly ReportEntry[] = [
  {
    id: 'risk-settlement-daily',
    titleCs: 'Denní objem zúčtovaných transakcí',
    titleEn: 'Daily settled transaction volume',
    descriptionCs: 'Počet a objem zúčtovaných transakcí podle dne, měny a platební sítě.',
    descriptionEn: 'Settled transaction count and volume per day, currency and rail. The baseline risk read.',
    permission: 'compliance:view',
    params: [FROM, TO],
    columns: [
      { key: 'day', labelCs: 'Den', labelEn: 'Day', format: 'text' },
      { key: 'currency_code', labelCs: 'Měna', labelEn: 'Currency', format: 'text' },
      { key: 'rail', labelCs: 'Rail', labelEn: 'Rail', format: 'text' },
      { key: 'settled_count', labelCs: 'Počet', labelEn: 'Count', format: 'number' },
      { key: 'settled_amount', labelCs: 'Objem', labelEn: 'Amount', format: 'number' },
      { key: 'largest_amount', labelCs: 'Největší transakce', labelEn: 'Largest transaction', format: 'number' },
    ],
    sql: (p) => `
      SELECT day, currency_code, rail, settled_count, settled_amount, largest_amount
      FROM ${DB}.gold_risk_settlement_daily
      WHERE ${dayRange(p)}
      ORDER BY day DESC, settled_amount DESC`,
  },
  {
    id: 'risk-failures-daily',
    titleCs: 'Denní selhané transakce',
    titleEn: 'Daily failed transactions',
    descriptionCs: 'Počet selhaných transakcí za den. Růst bez obchodní příčiny je risk signál.',
    descriptionEn: 'Failed transaction count per day. Growth without a business cause is a risk signal.',
    permission: 'compliance:view',
    params: [FROM, TO],
    columns: [
      { key: 'day', labelCs: 'Den', labelEn: 'Day', format: 'text' },
      { key: 'failed_count', labelCs: 'Selhalo', labelEn: 'Failed', format: 'number' },
      { key: 'distinct_transactions', labelCs: 'Unikátních transakcí', labelEn: 'Distinct transactions', format: 'number' },
    ],
    sql: (p) => `
      SELECT day, failed_count, distinct_transactions
      FROM ${DB}.gold_risk_failures_daily
      WHERE ${dayRange(p)}
      ORDER BY day DESC`,
  },
  {
    id: 'risk-large-settled',
    titleCs: 'Velké zúčtované transakce',
    titleEn: 'Large settled transactions',
    descriptionCs: 'Zúčtované transakce nad zvolenou hranicí. Hranice je parametr reportu, ne součást view.',
    descriptionEn: 'Settled transactions above a chosen threshold. The threshold is a report parameter, not part of the view.',
    permission: 'compliance:view',
    params: [
      FROM,
      TO,
      {
        name: 'minAmount', labelCs: 'Minimální částka', labelEn: 'Minimum amount',
        type: 'number', required: false, defaultValue: '100000',
      },
      {
        name: 'currency', labelCs: 'Měna', labelEn: 'Currency',
        type: 'enum', required: false, options: ['CZK', 'EUR'] as const,
      },
    ],
    columns: [
      { key: 'settled_at', labelCs: 'Zúčtováno', labelEn: 'Settled at', format: 'datetime' },
      { key: 'transaction_id', labelCs: 'ID transakce', labelEn: 'Transaction ID', format: 'text' },
      { key: 'amount', labelCs: 'Částka', labelEn: 'Amount', format: 'number' },
      { key: 'currency_code', labelCs: 'Měna', labelEn: 'Currency', format: 'text' },
      { key: 'rail', labelCs: 'Rail', labelEn: 'Rail', format: 'text' },
      { key: 'transaction_type', labelCs: 'Typ', labelEn: 'Type', format: 'text' },
    ],
    sql: (p) => `
      SELECT transaction_id, settled_at, amount, currency_code, rail, transaction_type
      FROM ${DB}.gold_risk_settled_transactions
      WHERE settled_day BETWEEN toDate('${p.from}') AND toDate('${p.to}')
        AND amount >= toDecimal64(${p.minAmount}, 2)
        ${p.currency ? `AND currency_code = '${p.currency}'` : ''}
      ORDER BY amount DESC
      LIMIT 500`,
  },
  {
    id: 'risk-credit-distress-daily',
    titleCs: 'Úvěrové distress signály',
    titleEn: 'Credit distress signals',
    descriptionCs: 'Potlačené nabídky a opuštěné úvěrové žádosti po dnech.',
    descriptionEn: 'Suppressed quotes and abandoned applications per day (ADR-0269 gold marts).',
    permission: 'compliance:view',
    params: [FROM, TO],
    columns: [
      { key: 'day', labelCs: 'Den', labelEn: 'Day', format: 'text' },
      { key: 'requested', labelCs: 'Vyžádané nabídky', labelEn: 'Quotes requested', format: 'number' },
      { key: 'suppressed', labelCs: 'Potlačené nabídky', labelEn: 'Quotes suppressed', format: 'number' },
      { key: 'applications_started', labelCs: 'Zahájené žádosti', labelEn: 'Applications started', format: 'number' },
      { key: 'applications_abandoned', labelCs: 'Opuštěné žádosti', labelEn: 'Applications abandoned', format: 'number' },
    ],
    sql: (p) => `
      SELECT day, requested, suppressed, applications_started, applications_abandoned
      FROM ${DB}.gold_credit_quote_outcomes
      WHERE ${dayRange(p)}
      ORDER BY day DESC`,
  },
  {
    id: 'warehouse-event-volume',
    titleCs: 'Objem událostí ve warehouse',
    titleEn: 'Warehouse event volume',
    descriptionCs: 'Příchozí události podle dne a domény. Pomáhá odhalit mezery v dodávce dat.',
    descriptionEn: 'Events per day and domain — the completeness context: a day with no settlement events is only visible here.',
    permission: 'compliance:view',
    params: [FROM, TO],
    columns: [
      { key: 'day', labelCs: 'Den', labelEn: 'Day', format: 'text' },
      { key: 'aggregate_type', labelCs: 'Doména', labelEn: 'Domain', format: 'text' },
      { key: 'events', labelCs: 'Událostí', labelEn: 'Events', format: 'number' },
      { key: 'distinct_aggregates', labelCs: 'Unikátních agregátů', labelEn: 'Distinct aggregates', format: 'number' },
      { key: 'last_event_at', labelCs: 'Poslední událost', labelEn: 'Last event', format: 'datetime' },
    ],
    sql: (p) => `
      SELECT day, aggregate_type, events, distinct_aggregates, last_event_at
      FROM ${DB}.gold_risk_event_volume_daily
      WHERE ${dayRange(p)}
      ORDER BY day DESC, events DESC`,
  },
  // ── Financial pack (ADR-0286, #8976) ───────────────────────────────────────
  //
  // These three reuse the V14 credit-lifecycle gold views rather than re-deriving their figures in
  // a new migration. That is deliberate and is the rule this registry is built on: one definition
  // of each business figure lives with the schema. `gold_loan_provisioning_by_stage` already sums
  // expected credit loss per period and stage; a second definition here would be a second answer
  // to the same question, free to drift from the first.
  //
  // They tolerate an empty source by construction. The V14 marts were written ahead of their data
  // (the lending subscription lands separately), so an empty result is the normal early state —
  // the BFF's `available: false` path covers the warehouse being absent, and zero rows are simply
  // zero rows.
  {
    id: 'finance-ifrs9-provisioning',
    titleCs: 'IFRS 9 — opravné položky podle stupně',
    titleEn: 'IFRS 9 — provisioning by stage',
    descriptionCs: 'Očekávaná úvěrová ztráta a její pohyb podle období a stupně znehodnocení.',
    descriptionEn: 'Expected credit loss and its movement per accounting period and impairment stage.',
    permission: 'compliance:view',
    params: [FROM_MONTH, TO_MONTH],
    columns: [
      { key: 'period', labelCs: 'Období', labelEn: 'Period', format: 'text' },
      { key: 'stage', labelCs: 'Stupeň', labelEn: 'Stage', format: 'text' },
      { key: 'loans', labelCs: 'Úvěrů', labelEn: 'Loans', format: 'number' },
      { key: 'expected_credit_loss', labelCs: 'ECL', labelEn: 'ECL', format: 'money' },
      { key: 'ecl_movement', labelCs: 'Pohyb ECL', labelEn: 'ECL movement', format: 'money' },
    ],
    sql: (p) => `
      SELECT period, stage, loans, expected_credit_loss, ecl_movement
      FROM ${DB}.gold_loan_provisioning_by_stage
      WHERE ${monthRange(p)}
      ORDER BY period DESC, stage`,
  },
  {
    id: 'finance-stage-migration',
    titleCs: 'Migrace mezi stupni IFRS 9',
    titleEn: 'IFRS 9 stage migration',
    descriptionCs: 'Přechody mezi stupni za období. Vyléčení se sleduje stejně jako zhoršení.',
    descriptionEn: 'Stage transitions per period. Cures are tracked alongside deteriorations — a matrix that counts only one direction reads as a book that never recovers.',
    permission: 'compliance:view',
    params: [FROM_MONTH, TO_MONTH],
    columns: [
      { key: 'period', labelCs: 'Období', labelEn: 'Period', format: 'text' },
      { key: 'previous_stage', labelCs: 'Z', labelEn: 'From', format: 'text' },
      { key: 'new_stage', labelCs: 'Do', labelEn: 'To', format: 'text' },
      { key: 'loans', labelCs: 'Úvěrů', labelEn: 'Loans', format: 'number' },
      { key: 'deteriorations', labelCs: 'Zhoršení', labelEn: 'Deteriorations', format: 'number' },
      { key: 'cures', labelCs: 'Vyléčení', labelEn: 'Cures', format: 'number' },
    ],
    sql: (p) => `
      SELECT period, previous_stage, new_stage, loans, deteriorations, cures
      FROM ${DB}.gold_loan_stage_migration
      WHERE ${monthRange(p)}
      ORDER BY period DESC, previous_stage, new_stage`,
  },
  {
    id: 'finance-loan-vintage',
    titleCs: 'Vintage křivka úvěrů',
    titleEn: 'Loan vintage curve',
    descriptionCs: 'Úvěry podle měsíce čerpání proti nejhoršímu dosaženému stupni.',
    descriptionEn: 'Loans grouped by disbursement month against the worst stage each has reached. `never_migrated` is reported as its own fact, not asserted as Stage 1 — "no event" and "an event saying Stage 1" are different measurements.',
    permission: 'compliance:view',
    params: [FROM_MONTH, TO_MONTH],
    columns: [
      { key: 'vintage_month', labelCs: 'Měsíc čerpání', labelEn: 'Vintage month', format: 'text' },
      { key: 'loans', labelCs: 'Úvěrů', labelEn: 'Loans', format: 'number' },
      { key: 'never_migrated', labelCs: 'Bez migrace', labelEn: 'Never migrated', format: 'number' },
      { key: 'reached_stage_2', labelCs: 'Dosáhlo st. 2', labelEn: 'Reached stage 2', format: 'number' },
      { key: 'reached_stage_3', labelCs: 'Dosáhlo st. 3', labelEn: 'Reached stage 3', format: 'number' },
      { key: 'ever_90_plus_dpd', labelCs: '90+ dpd', labelEn: 'Ever 90+ dpd', format: 'number' },
    ],
    sql: (p) => `
      SELECT vintage_month, loans, never_migrated, reached_stage_2, reached_stage_3, ever_90_plus_dpd
      FROM ${DB}.gold_loan_vintage
      WHERE ${monthRange(p, 'vintage_month')}
      ORDER BY vintage_month DESC`,
  },

  // ── Managerial pack (ADR-0286, #8976) ──────────────────────────────────────
  //
  // Day-keyed KPIs over gold views that already exist. No new DDL for the same reason as above.
  {
    id: 'mgmt-credit-decisions-daily',
    titleCs: 'Denní rozhodnutí o úvěrech',
    titleEn: 'Daily credit decisions',
    descriptionCs: 'Schváleno / postoupeno / zamítnuto za den, včetně cenového pásma u schválených.',
    descriptionEn: 'Approved / referred / declined per day, with the price band of approvals.',
    permission: 'compliance:view',
    params: [FROM, TO],
    columns: [
      { key: 'day', labelCs: 'Den', labelEn: 'Day', format: 'text' },
      { key: 'evaluated', labelCs: 'Posouzeno', labelEn: 'Evaluated', format: 'number' },
      { key: 'approved', labelCs: 'Schváleno', labelEn: 'Approved', format: 'number' },
      { key: 'referred', labelCs: 'Postoupeno', labelEn: 'Referred', format: 'number' },
      { key: 'declined', labelCs: 'Zamítnuto', labelEn: 'Declined', format: 'number' },
      { key: 'approved_prime', labelCs: 'Schváleno PRIME', labelEn: 'Approved PRIME', format: 'number' },
      { key: 'parties_evaluated', labelCs: 'Klientů', labelEn: 'Parties', format: 'number' },
    ],
    sql: (p) => `
      SELECT day, evaluated, approved, referred, declined, approved_prime, parties_evaluated
      FROM ${DB}.gold_credit_decision_outcomes
      WHERE ${dayRange(p)}
      ORDER BY day DESC`,
  },
  {
    id: 'mgmt-platform-event-volume',
    titleCs: 'Objem událostí platformy',
    titleEn: 'Platform event volume',
    descriptionCs: 'Události podle dne a služby. Syntetický provoz je z pohledu vyloučen.',
    descriptionEn: 'Events per day and producing service. Synthetic traffic is excluded by the view (ADR-0252), so this is a management number and not a test-traffic number.',
    permission: 'compliance:view',
    params: [FROM, TO],
    columns: [
      { key: 'day', labelCs: 'Den', labelEn: 'Day', format: 'text' },
      { key: 'source_service', labelCs: 'Služba', labelEn: 'Service', format: 'text' },
      { key: 'aggregate_type', labelCs: 'Typ agregátu', labelEn: 'Aggregate type', format: 'text' },
      { key: 'events', labelCs: 'Událostí', labelEn: 'Events', format: 'number' },
    ],
    sql: (p) => `
      SELECT day, source_service, aggregate_type, sum(events) AS events
      FROM ${DB}.gold_daily_event_volume
      WHERE ${dayRange(p)}
      GROUP BY day, source_service, aggregate_type
      ORDER BY day DESC, events DESC`,
  },
  {
    id: 'mgmt-onboarding-funnel-daily',
    titleCs: 'Denní onboarding funnel',
    titleEn: 'Daily onboarding funnel',
    descriptionCs: 'Kroky onboardingu za den podle akce a metody KYC.',
    descriptionEn: 'Onboarding steps per day by action and KYC method.',
    permission: 'compliance:view',
    params: [FROM, TO],
    columns: [
      { key: 'day', labelCs: 'Den', labelEn: 'Day', format: 'text' },
      { key: 'step', labelCs: 'Krok', labelEn: 'Step', format: 'text' },
      { key: 'action', labelCs: 'Akce', labelEn: 'Action', format: 'text' },
      { key: 'kyc_method', labelCs: 'Metoda KYC', labelEn: 'KYC method', format: 'text' },
      { key: 'sessions', labelCs: 'Relací', labelEn: 'Sessions', format: 'number' },
    ],
    sql: (p) => `
      SELECT day, step, action, kyc_method, uniqExact(session_id) AS sessions
      FROM ${DB}.gold_onboarding_funnel_events
      WHERE ${dayRange(p)}
      GROUP BY day, step, action, kyc_method
      ORDER BY day DESC, sessions DESC`,
  },
]

const BY_ID = new Map(REPORT_REGISTRY.map((e) => [e.id, e]))

export function getReport(id: string): ReportEntry | null {
  // The id comes from the URL path segment. A slug lookup against a closed map is itself the
  // first validation: an unknown id can never reach an SQL builder.
  return BY_ID.get(id) ?? null
}

/**
 * Validate all parameters of an entry against raw query-string values. Returns the validated
 * map, or the name of the first offending parameter. A report NEVER runs with a partial or
 * invalid parameter set.
 */
export function validateParams(
  entry: ReportEntry,
  raw: Record<string, string | null>,
): { ok: true; params: Record<string, string> } | { ok: false; param: string } {
  const params: Record<string, string> = {}
  for (const param of entry.params) {
    const supplied = raw[param.name]
    const value = validateParam(param, supplied ?? null)
    if (value === null) {
      // A required param with no valid value fails outright. An optional param left ABSENT falls
      // back to its default or is omitted from the query — but an optional param that was SUPPLIED
      // and failed validation must error too: silently dropping it would run a different report
      // than the operator asked for, and a report that answers a different question than its
      // label is the worst shape a risk surface can take.
      if (param.required || (supplied !== undefined && supplied !== null && supplied !== '')) {
        return { ok: false, param: param.name }
      }
      continue
    }
    params[param.name] = value
  }
  if (params.from && params.to && params.from > params.to) return { ok: false, param: 'to' }
  return { ok: true, params }
}
