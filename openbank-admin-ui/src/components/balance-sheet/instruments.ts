// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Pure derivations for the snapshot's instrument table (#11107). A risk officer reads this table,
// so every raw engine value (kind enum, exposure-class wire name, rate fraction, UUID) is turned
// into a business label here, and every value the engine does NOT hold becomes an explicit
// "unknown, because …" — never a guess and never a silent zero.
//
// Sources, all already served by risk-engine (no new backend field):
//   GET /snapshots/{id}/instruments — kind, currency, outstanding, maturity, rate terms,
//                                     counterpartyRef (an opaque party id), IFRS 9 stage, GL.
//   GET /snapshots/{id}/capital     — one exposure line per position, carrying instrumentId,
//                                     exposure class, risk weight, EAD and RWA.
// The readable reference is the loan's contract number (UV-YYYY-NNNNNN, #11107) when risk-engine
// carries one, otherwise a shortened id; the full id stays one click away in the row detail.
import type { Capital, Instrument } from './contracts'

export type Lang = 'cs' | 'en'

const KIND_LABELS: Record<string, { cs: [string, string]; en: [string, string] }> = {
  AMORTISING_LOAN: { cs: ['Splátkový úvěr', 'Splátkové úvěry'], en: ['Amortising loan', 'Amortising loans'] },
  BULLET: { cs: ['Jednorázově splatný úvěr', 'Jednorázově splatné úvěry'], en: ['Bullet loan', 'Bullet loans'] },
  NON_MATURITY_DEPOSIT: { cs: ['Vklad bez splatnosti', 'Vklady bez splatnosti'], en: ['Non-maturity deposit', 'Non-maturity deposits'] },
  TERM_DEPOSIT: { cs: ['Termínovaný vklad', 'Termínované vklady'], en: ['Term deposit', 'Term deposits'] },
  CURRENT_ACCOUNT: { cs: ['Běžný účet', 'Běžné účty'], en: ['Current account', 'Current accounts'] },
  FX_POSITION: { cs: ['Devizová pozice', 'Devizové pozice'], en: ['FX position', 'FX positions'] },
  CASH_NOSTRO: { cs: ['Nostro účet', 'Nostro účty'], en: ['Nostro account', 'Nostro accounts'] },
  BOND: { cs: ['Dluhopis', 'Dluhopisy'], en: ['Bond', 'Bonds'] },
  MONEY_MARKET_DEAL: { cs: ['Obchod peněžního trhu', 'Obchody peněžního trhu'], en: ['Money-market deal', 'Money-market deals'] },
  DERIVATIVE_LEG: { cs: ['Noha derivátu', 'Nohy derivátů'], en: ['Derivative leg', 'Derivative legs'] },
  EQUITY_CAPITAL: { cs: ['Vlastní kapitál', 'Vlastní kapitál'], en: ['Equity capital', 'Equity capital'] },
}

/** Singular label of an instrument kind; an unknown kind shows its raw name rather than a guess. */
export function kindLabel(kind: string, lang: Lang): string {
  return KIND_LABELS[kind]?.[lang][0] ?? kind
}

/** Plural label for the group header ("Splátkové úvěry: 44"). */
export function kindGroupLabel(kind: string, lang: Lang): string {
  return KIND_LABELS[kind]?.[lang][1] ?? kind
}

const LOAN_KINDS = new Set(['AMORTISING_LOAN', 'BULLET'])

/**
 * Readable reference: the source contract's number when risk-engine carries one (a loan's
 * UV-YYYY-NNNNNN, #11107). Otherwise — other kinds, an older risk-engine, runs recorded before
 * the number existed — the id's first block, upper-cased and prefixed by what the instrument is.
 * A display handle, not a business key; search also matches the full id.
 */
export function instrumentReference(i: Pick<Instrument, 'id' | 'kind' | 'contractNumber'>, lang: Lang): string {
  if (i.contractNumber) return i.contractNumber
  const prefix = LOAN_KINDS.has(i.kind) ? (lang === 'cs' ? 'Úvěr' : 'Loan') : (lang === 'cs' ? 'Nástroj' : 'Instr.')
  return `${prefix} ${i.id.split('-')[0].slice(0, 8).toUpperCase()}`
}

const EXPOSURE_CLASS_LABELS: Record<string, { cs: string; en: string }> = {
  'sovereign-and-central-bank': { cs: 'Ústřední vlády a centrální banky', en: 'Sovereigns and central banks' },
  bank: { cs: 'Instituce', en: 'Institutions' },
  retail: { cs: 'Retailové', en: 'Retail' },
  corporate: { cs: 'Podnikové', en: 'Corporate' },
  defaulted: { cs: 'V selhání', en: 'Defaulted' },
  cash: { cs: 'Hotovost', en: 'Cash' },
  'cash-items-in-collection': { cs: 'Hotovost v inkasu', en: 'Cash items in collection' },
  'other-asset': { cs: 'Ostatní aktiva', en: 'Other assets' },
}

export function exposureClassLabel(cls: string, lang: Lang): string {
  return EXPOSURE_CLASS_LABELS[cls]?.[lang] ?? cls
}

/** Credit-risk figures of one instrument, summed over its exposure lines in its own currency. */
export type InstrumentCapital = { exposureClass: string; riskWeight: number; ead: number; rwa: number }

/**
 * Index the capital result by instrument. Only the per-currency lines are read — `total` restates
 * them converted into CZK, and reading both would double an instrument. An instrument with several
 * lines (one per position) sums EAD and RWA; its risk weight is then RWA / EAD, which equals the
 * line weight whenever there is one line.
 */
export function capitalByInstrument(capital: Capital): Map<string, InstrumentCapital> {
  const out = new Map<string, InstrumentCapital>()
  for (const c of capital.currencies) {
    for (const l of c.lines) {
      if (!l.instrumentId) continue
      const prev = out.get(l.instrumentId)
      if (!prev) {
        out.set(l.instrumentId, { exposureClass: l.exposureClass, riskWeight: l.riskWeight, ead: l.ead, rwa: l.rwa })
      } else {
        const ead = prev.ead + l.ead
        const rwa = prev.rwa + l.rwa
        out.set(l.instrumentId, { exposureClass: prev.exposureClass, ead, rwa, riskWeight: ead !== 0 ? rwa / ead : prev.riskWeight })
      }
    }
  }
  return out
}

export type InstrumentRow = {
  instrument: Instrument
  reference: string
  kindLabel: string
  obligorName: string | null
  capital: InstrumentCapital | null
}

export function buildRows(
  instruments: Instrument[],
  lang: Lang,
  capital: Map<string, InstrumentCapital> | null,
  names: ReadonlyMap<string, string>,
): InstrumentRow[] {
  return instruments.map(instrument => ({
    instrument,
    reference: instrumentReference(instrument, lang),
    kindLabel: kindLabel(instrument.kind, lang),
    obligorName: instrument.counterpartyRef ? names.get(instrument.counterpartyRef) ?? null : null,
    capital: capital?.get(instrument.id) ?? null,
  }))
}

/**
 * Largest exposure first. Outstanding is trial-balance signed (loans +, deposits −), so the sort is
 * by magnitude; ties fall back to the reference so the order is stable across reloads.
 */
export function sortByOutstanding(rows: InstrumentRow[]): InstrumentRow[] {
  return [...rows].sort((a, b) =>
    Math.abs(b.instrument.outstanding) - Math.abs(a.instrument.outstanding) || a.instrument.id.localeCompare(b.instrument.id))
}

/** Case-insensitive match on the reference, the full ids, the obligor's resolved name and the kind. */
export function filterRows(rows: InstrumentRow[], query: string): InstrumentRow[] {
  const q = query.trim().toLowerCase()
  if (!q) return rows
  return rows.filter(r => [r.reference, r.instrument.id, r.instrument.counterpartyRef, r.obligorName, r.kindLabel]
    .some(v => v != null && v.toLowerCase().includes(q)))
}

export type CurrencyTotal = { currency: string; count: number; outstanding: number; rwa: number; rwaKnown: number }

/**
 * Totals per currency — amounts in different currencies are never added together. `rwaKnown`
 * counts the rows whose RWA the capital result states, so a partial sum is labelled as partial.
 */
export function totalsByCurrency(rows: InstrumentRow[]): CurrencyTotal[] {
  const by = new Map<string, CurrencyTotal>()
  for (const r of rows) {
    const t = by.get(r.instrument.currency) ?? { currency: r.instrument.currency, count: 0, outstanding: 0, rwa: 0, rwaKnown: 0 }
    t.count += 1
    t.outstanding += r.instrument.outstanding
    if (r.capital) { t.rwa += r.capital.rwa; t.rwaKnown += 1 }
    by.set(r.instrument.currency, t)
  }
  return [...by.values()].sort((a, b) => a.currency.localeCompare(b.currency))
}

/** Group header counts, largest group first: [["AMORTISING_LOAN", 44], …]. */
export function kindCounts(instruments: Instrument[]): [string, number][] {
  const counts = new Map<string, number>()
  for (const i of instruments) counts.set(i.kind, (counts.get(i.kind) ?? 0) + 1)
  return [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
}

export function formatMoney(amount: number, currency: string, locale: string): string {
  try {
    return amount.toLocaleString(locale, { style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 2 })
  } catch {
    // An ISO code Intl does not know still renders, with the code beside the number.
    return `${amount.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}`
  }
}

/** A rate stated as a fraction (0.0525) rendered as a percentage ("5,25 %"). */
export function formatPercent(fraction: number, locale: string): string {
  return `${(fraction * 100).toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 3 })} %`
}

export function formatDate(iso: string, locale: string): string {
  const d = new Date(`${iso}T00:00:00Z`)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleDateString(locale, { timeZone: 'UTC' })
}
