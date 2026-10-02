// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Human-readable view of the back-post void page. Its readers are risk and finance people, so a
// back-post run is identified by WHAT it is (when it ran, what it booked, who ran it) and never by
// its id; the id stays available as a secondary reference for support. Every figure here is
// derived from data lending already returns — nothing is invented and no extra call is made.
import type { BackfillPlan, BackfillRequest, VoidRequest } from './contracts'

export type Translate = (cs: string, en: string) => string
type State = VoidRequest['state']

/** Plain-language label for a request state. The raw enum is kept only for the tooltip/tone. */
export function voidStateLabel(state: State, t: Translate): string {
  switch (state) {
    case 'PROPOSED': return t('Čeká na schválení', 'Awaiting approval')
    case 'APPROVED': return t('Schváleno, čeká na provedení', 'Approved, awaiting execution')
    case 'REJECTED': return t('Zamítnuto', 'Rejected')
    case 'WITHDRAWN': return t('Staženo navrhovatelem', 'Withdrawn by proposer')
    case 'EXECUTED': return t('Provedeno', 'Executed')
  }
}

/** Loan status after a void, in words. Unknown values fall through unchanged. */
export function loanOutcomeLabel(status: string, t: Translate): string {
  switch (status) {
    case 'VOIDED': case 'UNWOUND': return t('Stornováno', 'Voided')
    case 'FAILED': return t('Selhalo', 'Failed')
    case 'SKIPPED': return t('Přeskočeno', 'Skipped')
    default: return status
  }
}

/** Current loan status in words (the plan reports the loan's own lifecycle status). */
export function loanStatusLabel(status: string, t: Translate): string {
  switch (status) {
    case 'ACTIVE': return t('Aktivní', 'Active')
    case 'DISBURSED': return t('Vyplacený', 'Disbursed')
    case 'REPAID': case 'CLOSED': return t('Splacený', 'Repaid')
    case 'DEFAULTED': return t('V selhání', 'Defaulted')
    case 'WRITTEN_OFF': return t('Odepsaný', 'Written off')
    case 'UNWOUND': return t('Stornovaný', 'Voided')
    default: return status
  }
}

export const localeFor = (language: string) => (language === 'cs' ? 'cs-CZ' : 'en-GB')

/** Date and time in Prague, whatever the browser's zone — the bank's business clock. */
export function formatPragueDateTime(iso: string | null | undefined, locale: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString(locale, { timeZone: 'Europe/Prague', day: 'numeric', month: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit' })
}

/** A calendar date (yyyy-mm-dd, no zone) rendered as a local date without shifting it a day. */
export function formatBusinessDate(date: string | null | undefined, locale: string): string {
  if (!date) return '—'
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(date)
  if (!m) return date
  return new Date(Date.UTC(+m[1], +m[2] - 1, +m[3])).toLocaleDateString(locale, { timeZone: 'UTC', day: 'numeric', month: 'numeric', year: 'numeric' })
}

export function formatAmount(amount: number, currency: string, locale: string): string {
  try {
    return amount.toLocaleString(locale, { style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 2 })
  } catch {
    return `${amount.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}`
  }
}

/** Amounts in several currencies, joined — never summed across currencies. */
export function formatAmounts(totals: Record<string, number>, locale: string): string {
  const entries = Object.entries(totals).sort(([a], [b]) => a.localeCompare(b))
  if (entries.length === 0) return '—'
  return entries.map(([ccy, v]) => formatAmount(v, ccy, locale)).join(' + ')
}

/**
 * A person as lending recorded them: the token principal (an e-mail or a username). Lending stores
 * no display name and the console has no user directory to resolve one, so the identity is shown
 * as-is and labelled for what it is — never guessed into a name.
 */
export function personLabel(principal: string | null | undefined, t: Translate): { text: string; kind: string } | null {
  if (!principal) return null
  return { text: principal, kind: principal.includes('@') ? t('e-mail', 'e-mail') : t('uživatel', 'user') }
}

/** One line identifying an executed back-post run by what it is, for the source picker. */
export function backPostRunLabel(run: BackfillRequest, t: Translate, locale: string): string {
  const when = run.executedAt ? formatPragueDateTime(run.executedAt, locale) : formatBusinessDate(run.cutoverDate, locale)
  const loans = run.loanCount.toLocaleString(locale)
  const entries = run.legCount.toLocaleString(locale)
  const who = run.executedBy ?? run.proposedBy
  return t(
    `Doúčtování z ${when} — ${loans} úvěrů, ${entries} zápisů${who ? `, provedl(a) ${who}` : ''}`,
    `Back-post of ${when} — ${loans} loans, ${entries} entries${who ? `, run by ${who}` : ''}`,
  )
}

type Leg = { kind?: unknown; amount?: unknown; currency?: unknown; valueDate?: unknown }
const asLeg = (v: unknown): Leg => (v && typeof v === 'object' ? (v as Leg) : {})

export type PlanLoanRow = {
  loanId: string
  currency: string
  status: string
  disbursedOn: string | null
  disbursed: number
  unpaidPrincipal: number
  entries: number
}

export type PlanSummary = {
  loanCount: number
  entryCount: number
  /** Σ debit per currency over the offset journals — the volume the mirror entries reverse. */
  totalByCurrency: Record<string, number>
  /** Σ principal paid out per currency (DISBURSEMENT legs). */
  disbursedByCurrency: Record<string, number>
  /** Earliest and latest value date of the journals being offset. */
  firstValueDate: string | null
  lastValueDate: string | null
  loans: PlanLoanRow[]
}

const add = (acc: Record<string, number>, ccy: string, v: number) => { acc[ccy] = (acc[ccy] ?? 0) + v }

export function summarizePlan(plan: BackfillPlan): PlanSummary {
  const totalByCurrency: Record<string, number> = {}
  for (const g of plan.glTotals) add(totalByCurrency, g.currency, g.debit)
  const disbursedByCurrency: Record<string, number> = {}
  const dates: string[] = []
  const loans: PlanLoanRow[] = plan.plan.loans.map(loan => {
    let disbursed = 0
    let disbursedOn: string | null = null
    for (const raw of loan.legs) {
      const leg = asLeg(raw)
      if (typeof leg.valueDate === 'string') dates.push(leg.valueDate)
      if (leg.kind === 'DISBURSEMENT' && typeof leg.amount === 'number') {
        disbursed += leg.amount
        if (typeof leg.valueDate === 'string' && (!disbursedOn || leg.valueDate < disbursedOn)) disbursedOn = leg.valueDate
      }
    }
    if (disbursed) add(disbursedByCurrency, loan.currency, disbursed)
    return { loanId: loan.loanId, currency: loan.currency, status: loan.status, disbursedOn, disbursed, unpaidPrincipal: loan.unpaidPrincipal, entries: loan.legs.length }
  })
  dates.sort()
  return {
    loanCount: plan.plan.loans.length,
    entryCount: plan.journalCount,
    totalByCurrency,
    disbursedByCurrency,
    firstValueDate: dates[0] ?? null,
    lastValueDate: dates[dates.length - 1] ?? null,
    loans,
  }
}

/** The dry-run as one sentence a finance reader can repeat to a colleague. */
export function planSentence(s: PlanSummary, t: Translate, locale: string): string {
  const loans = s.loanCount.toLocaleString(locale)
  const entries = s.entryCount.toLocaleString(locale)
  const total = formatAmounts(s.totalByCurrency, locale)
  return t(
    `Storno vrátí ${loans} úvěrů a vytvoří ${entries} protizápisů v celkové výši ${total}.`,
    `The void reverses ${loans} loans and creates ${entries} offsetting entries totalling ${total}.`,
  )
}
