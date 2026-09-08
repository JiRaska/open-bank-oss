// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Types and PURE aggregation for the credit-risk console (ADR-0230 D1 over ADR-0213 evidence).
//
// Everything here is a function of the four `/api/v1/lending/risk/*` responses and nothing else:
// no fetch, no React, no locale. That is what lets the arithmetic be unit-tested against the
// exact JSON the service returns, and what keeps the page a rendering of numbers it did not
// invent. Thresholds are READ from the policy payload — the console never hard-codes 0.45.

export type EngineOutcome = 'APPROVE' | 'REFER' | 'DECLINE' | 'UNEVALUATED'

export type Reason = { code: string; ruleId: string | null }
export type Affordability = { dsti: number; dti: number; dstiIncludingExistingDebt: number }

export type Decision = {
  applicationId: string
  partyId: string
  status: string
  createdAt: string
  requestedAmount: number
  currency: string
  termPeriods: number
  nominalAnnualRate: number
  jurisdiction: string | null
  productType: string | null
  productKind: string
  packVersion: number | null
  engineOutcome: EngineOutcome
  priceBand: string | null
  reasons: Reason[]
  matchedRuleIds: string[]
  policyVersions: Record<string, number>
  inputSnapshotHash: string | null
  decidedEngineAt: string | null
  affordability: Affordability | null
  verifiedIncomeMonthly: number | null
  existingDebtServiceMonthly: number | null
  ageYears: number | null
  residency: string | null
  employmentTenureMonths: number | null
  humanDecidedBy: string | null
  humanDecisionReason: string | null
  humanDecidedAt: string | null
}

export type OutcomeSummary = { engineOutcome: string; priceBand: string | null; count: number }

export type Assessment = {
  period: string
  asOf: string
  outstandingBalance: number
  daysPastDue: number
  bucket: string
  stage: string
  expectedCreditLoss: number
  modelVersion: string
}

export type LoanRisk = {
  loanId: string
  applicationId: string
  partyId: string
  status: string
  principal: number
  currency: string
  nominalAnnualRate: number
  termPeriods: number
  disbursedAt: string
  assessment: Assessment | null
}

export type PolicyRule = {
  id: string
  attribute: string
  operator: string
  threshold: number | null
  values: string[]
  band: string | null
  detail: string
}
export type PolicyTable = {
  kind: string
  name: string
  version: number
  effectiveFrom: string
  effectiveTo: string | null
  rules: PolicyRule[]
}
export type Policy = { asOf: string; source: string; codeSeeded: boolean; tables: PolicyTable[] }

export const OUTCOMES: readonly EngineOutcome[] = ['APPROVE', 'REFER', 'DECLINE'] as const
export const STAGES = ['STAGE_1', 'STAGE_2', 'STAGE_3'] as const
export const BUCKETS = ['CURRENT', 'DPD_1_30', 'DPD_31_60', 'DPD_61_90', 'DPD_90_PLUS'] as const

/** Where an application ended up, as a human disposition the engine's outcome can be compared with. */
export type Disposition = 'approved' | 'declined' | 'lapsed' | 'inFlight'
const APPROVED_STATES = new Set(['OFFERED', 'AWAITING_SIGNATURE', 'SIGNED', 'REFLECTION_PERIOD', 'READY_TO_DISBURSE', 'DISBURSED'])
const LAPSED_STATES = new Set(['WITHDRAWN', 'EXPIRED'])

export function disposition(status: string): Disposition {
  if (APPROVED_STATES.has(status)) return 'approved'
  if (status === 'DECLINED') return 'declined'
  if (LAPSED_STATES.has(status)) return 'lapsed'
  return 'inFlight'
}

/** Book-wide totals from the DB-grouped summary — never from the capped list. */
export function outcomeTotals(summary: OutcomeSummary[]): Record<EngineOutcome, number> & { total: number } {
  const acc = { APPROVE: 0, REFER: 0, DECLINE: 0, UNEVALUATED: 0, total: 0 }
  for (const row of summary) {
    const k = row.engineOutcome as EngineOutcome
    if (k in acc) acc[k] += row.count
    acc.total += row.count
  }
  return acc
}

export function priceBandTotals(summary: OutcomeSummary[]): { band: string; count: number }[] {
  const m = new Map<string, number>()
  for (const row of summary) {
    if (row.engineOutcome !== 'APPROVE') continue
    const band = row.priceBand ?? '—'
    m.set(band, (m.get(band) ?? 0) + row.count)
  }
  return [...m.entries()].map(([band, count]) => ({ band, count })).sort((a, b) => b.count - a.count)
}

/** Monday of the ISO week containing the timestamp, as yyyy-mm-dd (UTC). */
export function weekStart(iso: string): string {
  const d = new Date(iso)
  const day = (d.getUTCDay() + 6) % 7
  d.setUTCDate(d.getUTCDate() - day)
  return d.toISOString().slice(0, 10)
}

export type WeeklyOutcome = { week: string; APPROVE: number; REFER: number; DECLINE: number }

export function weeklyOutcomes(decisions: Decision[]): WeeklyOutcome[] {
  const m = new Map<string, WeeklyOutcome>()
  for (const d of decisions) {
    const at = d.decidedEngineAt ?? d.createdAt
    const week = weekStart(at)
    const row = m.get(week) ?? { week, APPROVE: 0, REFER: 0, DECLINE: 0 }
    if (d.engineOutcome === 'APPROVE' || d.engineOutcome === 'REFER' || d.engineOutcome === 'DECLINE') row[d.engineOutcome] += 1
    m.set(week, row)
  }
  return [...m.values()].sort((a, b) => a.week.localeCompare(b.week))
}

export type ReasonCount = { code: string; ruleId: string | null; count: number }

/** The adverse-action Pareto: which reason (and which rule) refers or declines most. */
export function reasonPareto(decisions: Decision[]): ReasonCount[] {
  const m = new Map<string, ReasonCount>()
  for (const d of decisions) {
    for (const r of d.reasons) {
      const key = `${r.code}|${r.ruleId ?? ''}`
      const row = m.get(key) ?? { code: r.code, ruleId: r.ruleId, count: 0 }
      row.count += 1
      m.set(key, row)
    }
  }
  return [...m.values()].sort((a, b) => b.count - a.count)
}

/** How often each policy rule matched — the "is this rule doing anything" column of a decision table. */
export function ruleHits(decisions: Decision[]): Map<string, number> {
  const m = new Map<string, number>()
  for (const d of decisions) for (const id of d.matchedRuleIds) m.set(id, (m.get(id) ?? 0) + 1)
  return m
}

export type OverrideRow = {
  engine: EngineOutcome
  approved: number
  declined: number
  lapsed: number
  inFlight: number
  /** Human went the other way: engine APPROVE ended DECLINED, or engine DECLINE was approved. */
  overridden: number
}

export function overrideMatrix(decisions: Decision[]): OverrideRow[] {
  const rows = new Map<EngineOutcome, OverrideRow>()
  for (const o of OUTCOMES) rows.set(o, { engine: o, approved: 0, declined: 0, lapsed: 0, inFlight: 0, overridden: 0 })
  for (const d of decisions) {
    const row = rows.get(d.engineOutcome)
    if (!row) continue
    const disp = disposition(d.status)
    row[disp] += 1
    if ((d.engineOutcome === 'APPROVE' && disp === 'declined') || (d.engineOutcome === 'DECLINE' && disp === 'approved')) row.overridden += 1
  }
  return [...rows.values()]
}

/** Overrides as a share of the engine decisions a human has already disposed of. */
export function overrideRate(matrix: OverrideRow[]): number | null {
  let disposed = 0
  let overridden = 0
  for (const r of matrix) {
    disposed += r.approved + r.declined
    overridden += r.overridden
  }
  return disposed === 0 ? null : overridden / disposed
}

/** The numeric threshold a policy table applies to an attribute — the line the scatter overlays. */
export function threshold(policy: Policy | null, kind: string, attribute: string): number | null {
  if (!policy) return null
  const asOf = policy.asOf
  const tables = policy.tables
    .filter(t => t.kind === kind && t.effectiveFrom <= asOf && (t.effectiveTo === null || asOf < t.effectiveTo))
    .sort((a, b) => b.version - a.version)
  for (const t of tables) {
    const rule = t.rules.find(r => r.attribute === attribute && r.threshold !== null)
    if (rule) return rule.threshold
  }
  return null
}

export type JurisdictionRow = { jurisdiction: string; APPROVE: number; REFER: number; DECLINE: number; total: number }

export function byJurisdiction(decisions: Decision[]): JurisdictionRow[] {
  const m = new Map<string, JurisdictionRow>()
  for (const d of decisions) {
    const j = d.jurisdiction ?? '—'
    const row = m.get(j) ?? { jurisdiction: j, APPROVE: 0, REFER: 0, DECLINE: 0, total: 0 }
    if (d.engineOutcome === 'APPROVE' || d.engineOutcome === 'REFER' || d.engineOutcome === 'DECLINE') row[d.engineOutcome] += 1
    row.total += 1
    m.set(j, row)
  }
  return [...m.values()].sort((a, b) => b.total - a.total)
}

export type StageRow = { stage: string; count: number; outstanding: number; ecl: number; coverage: number | null }
export type PortfolioMix = {
  currency: string
  assessed: number
  unassessed: number
  stages: StageRow[]
  buckets: { bucket: string; count: number; outstanding: number }[]
  totalOutstanding: number
  totalEcl: number
  coverage: number | null
  /** Share of assessed outstanding in stages 2 and 3 — the "non-performing plus watch" figure. */
  stage23Share: number | null
  npl90Share: number | null
  modelVersions: string[]
}

/** Money is per currency; a book with two currencies gets two mixes, never one summed number. */
export function portfolioMix(loans: LoanRisk[]): PortfolioMix[] {
  const byCcy = new Map<string, LoanRisk[]>()
  for (const l of loans) byCcy.set(l.currency, [...(byCcy.get(l.currency) ?? []), l])
  return [...byCcy.entries()].map(([currency, rows]) => {
    const assessed = rows.filter(r => r.assessment !== null)
    const stages: StageRow[] = STAGES.map(stage => {
      const s = assessed.filter(r => r.assessment!.stage === stage)
      const outstanding = s.reduce((a, r) => a + r.assessment!.outstandingBalance, 0)
      const ecl = s.reduce((a, r) => a + r.assessment!.expectedCreditLoss, 0)
      return { stage, count: s.length, outstanding, ecl, coverage: outstanding > 0 ? ecl / outstanding : null }
    })
    const buckets = BUCKETS.map(bucket => {
      const b = assessed.filter(r => r.assessment!.bucket === bucket)
      return { bucket, count: b.length, outstanding: b.reduce((a, r) => a + r.assessment!.outstandingBalance, 0) }
    })
    const totalOutstanding = stages.reduce((a, s) => a + s.outstanding, 0)
    const totalEcl = stages.reduce((a, s) => a + s.ecl, 0)
    const s23 = stages.filter(s => s.stage !== 'STAGE_1').reduce((a, s) => a + s.outstanding, 0)
    const npl = buckets.find(b => b.bucket === 'DPD_90_PLUS')?.outstanding ?? 0
    return {
      currency,
      assessed: assessed.length,
      unassessed: rows.length - assessed.length,
      stages,
      buckets,
      totalOutstanding,
      totalEcl,
      coverage: totalOutstanding > 0 ? totalEcl / totalOutstanding : null,
      stage23Share: totalOutstanding > 0 ? s23 / totalOutstanding : null,
      npl90Share: totalOutstanding > 0 ? npl / totalOutstanding : null,
      modelVersions: [...new Set(assessed.map(r => r.assessment!.modelVersion))].sort(),
    }
  }).sort((a, b) => b.totalOutstanding - a.totalOutstanding)
}

export type VintageRow = { month: string; loans: number; STAGE_1: number; STAGE_2: number; STAGE_3: number; unassessed: number }

/** Disbursement month × current stage. The classic vintage read, on whatever the book holds. */
export function vintage(loans: LoanRisk[]): VintageRow[] {
  const m = new Map<string, VintageRow>()
  for (const l of loans) {
    const month = l.disbursedAt.slice(0, 7)
    const row = m.get(month) ?? { month, loans: 0, STAGE_1: 0, STAGE_2: 0, STAGE_3: 0, unassessed: 0 }
    row.loans += 1
    const stage = l.assessment?.stage
    if (stage === 'STAGE_1' || stage === 'STAGE_2' || stage === 'STAGE_3') row[stage] += 1
    else row.unassessed += 1
    m.set(month, row)
  }
  return [...m.values()].sort((a, b) => a.month.localeCompare(b.month))
}

const CSV_COLUMNS = [
  'applicationId', 'partyId', 'status', 'createdAt', 'decidedEngineAt', 'engineOutcome', 'priceBand',
  'reasons', 'matchedRuleIds', 'policyVersions', 'inputSnapshotHash', 'requestedAmount', 'currency',
  'termPeriods', 'nominalAnnualRate', 'jurisdiction', 'productType', 'productKind', 'packVersion',
  'dsti', 'dti', 'dstiIncludingExistingDebt', 'verifiedIncomeMonthly', 'existingDebtServiceMonthly',
  'ageYears', 'residency', 'employmentTenureMonths', 'humanDecidedBy', 'humanDecisionReason', 'humanDecidedAt',
] as const

function csvCell(v: unknown): string {
  if (v === null || v === undefined) return ''
  const s = typeof v === 'object' ? JSON.stringify(v) : String(v)
  return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

/** RFC 4180 flat export — one row per decision, reasons re-joined as `CODE:rule;…` for a notebook. */
export function decisionsToCsv(decisions: Decision[]): string {
  const rows = decisions.map(d => {
    const flat: Record<(typeof CSV_COLUMNS)[number], unknown> = {
      ...d,
      reasons: d.reasons.map(r => `${r.code}:${r.ruleId ?? '-'}`).join(';'),
      matchedRuleIds: d.matchedRuleIds.join(';'),
      policyVersions: Object.entries(d.policyVersions).map(([k, v]) => `${k}=${v}`).join(';'),
      dsti: d.affordability?.dsti ?? null,
      dti: d.affordability?.dti ?? null,
      dstiIncludingExistingDebt: d.affordability?.dstiIncludingExistingDebt ?? null,
    }
    return CSV_COLUMNS.map(c => csvCell(flat[c])).join(',')
  })
  return [CSV_COLUMNS.join(','), ...rows].join('\r\n') + '\r\n'
}
