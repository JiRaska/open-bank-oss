// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Closed vocabularies of the ADR-0344 treasury workbench: the 16 products and what each needs on a
// ticket, the code lists a ticket offers, and labels. Pure data — no React.
type T = (cs: string, en: string) => string

export const ALL_PRODUCTS = [
  'MM_PLACEMENT', 'MM_BORROWING', 'CNB_DEPOSIT_FACILITY', 'CNB_LOMBARD', 'FX_SPOT',
  'CASH', 'TREASURY_BILL', 'BOND', 'BILL_OF_EXCHANGE', 'LOAN',
  'FX_FORWARD', 'FX_SWAP', 'FRA', 'IRS', 'REPO', 'REVERSE_REPO',
] as const
export type AnyProduct = typeof ALL_PRODUCTS[number]

/** The five products the flat (principal / rate / value date) form has always booked. */
export const LEGACY_PRODUCTS: readonly AnyProduct[] = ['MM_PLACEMENT', 'MM_BORROWING', 'CNB_DEPOSIT_FACILITY', 'CNB_LOMBARD', 'FX_SPOT']

export type Panel = 'securities' | 'fx' | 'fra' | 'collateral' | 'deposit'
export type Profile = {
  legacy: boolean
  /** Interest-bearing leg panels the dealer edits (0 = none, derived server-side). */
  legs: 0 | 1 | 2
  panels: Panel[]
  /** The booking needs a portfolio/book (every product beyond the legacy five). */
  needsBook: boolean
  /** Legs are sent explicitly (contract 2: required for IRS, FRA, FX_*, REPO, LOAN). */
  sendLegs: boolean
}

export function profileOf(p: AnyProduct): Profile {
  if (LEGACY_PRODUCTS.includes(p)) return { legacy: true, legs: 0, panels: [], needsBook: false, sendLegs: false }
  switch (p) {
    case 'CASH': return { legacy: false, legs: 1, panels: ['deposit'], needsBook: true, sendLegs: false }
    case 'LOAN': return { legacy: false, legs: 1, panels: ['deposit'], needsBook: true, sendLegs: true }
    case 'TREASURY_BILL':
    case 'BOND':
    case 'BILL_OF_EXCHANGE': return { legacy: false, legs: 0, panels: ['securities'], needsBook: true, sendLegs: false }
    case 'FX_FORWARD':
    case 'FX_SWAP': return { legacy: false, legs: 0, panels: ['fx'], needsBook: true, sendLegs: true }
    case 'FRA': return { legacy: false, legs: 1, panels: ['fra'], needsBook: true, sendLegs: true }
    case 'IRS': return { legacy: false, legs: 2, panels: [], needsBook: true, sendLegs: true }
    default: return { legacy: false, legs: 1, panels: ['collateral'], needsBook: true, sendLegs: true }
  }
}

export function productName(p: string, t: T): string {
  switch (p) {
    case 'MM_PLACEMENT': return t('Umístění na peněžním trhu', 'Money-market placement')
    case 'MM_BORROWING': return t('Přijetí na peněžním trhu', 'Money-market borrowing')
    case 'CNB_DEPOSIT_FACILITY': return t('Depozitní facilita ČNB', 'ČNB deposit facility')
    case 'CNB_LOMBARD': return t('ČNB lombardní úvěr', 'ČNB lombard borrowing')
    case 'FX_SPOT': return t('FX spot', 'FX spot')
    case 'CASH': return t('Hotovost / vklad', 'Cash / deposit')
    case 'TREASURY_BILL': return t('Pokladniční poukázka', 'Treasury bill')
    case 'BOND': return t('Dluhopis', 'Bond')
    case 'BILL_OF_EXCHANGE': return t('Směnka', 'Bill of exchange')
    case 'LOAN': return t('Úvěr', 'Loan')
    case 'FX_FORWARD': return t('FX forward', 'FX forward')
    case 'FX_SWAP': return t('FX swap', 'FX swap')
    case 'FRA': return t('FRA (forward rate agreement)', 'FRA (forward rate agreement)')
    case 'IRS': return t('Úrokový swap (IRS)', 'Interest-rate swap (IRS)')
    case 'REPO': return t('Repo', 'Repo')
    case 'REVERSE_REPO': return t('Reverzní repo', 'Reverse repo')
    default: return p
  }
}

export const KINDS = ['ACTUAL', 'PLANNED', 'HYPOTHETICAL'] as const
export const CHANNELS = ['PLATFORM', 'VOICE', 'ELECTRONIC', 'INTERNAL'] as const
export const BDCS = ['UNADJUSTED', 'FOLLOWING', 'MODIFIED_FOLLOWING', 'PRECEDING', 'MODIFIED_PRECEDING'] as const
export const DAY_COUNTS = ['ACT/360', 'ACT/365F', 'ACT/ACT_ISDA', '30/360', '30E/360'] as const
export const FREQUENCIES = ['ONCE', 'DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'SEMI_ANNUAL', 'ANNUAL'] as const
export const STUBS = ['NONE', 'SHORT_INITIAL', 'LONG_INITIAL', 'SHORT_FINAL', 'LONG_FINAL'] as const
export const AMORTISATIONS = ['BULLET', 'LINEAR', 'ANNUITY', 'CUSTOM'] as const
export const RATE_TYPES = ['FIXED', 'FLOATING', 'NONE'] as const
export const CAP_APPLIES = ['INDEX', 'INDEX_PLUS_SPREAD'] as const
export const ROLL_DIRECTIONS = ['FROM_START', 'FROM_END'] as const
export const COLLATERALISATION = ['UNCOLLATERALISED', 'PARTIALLY', 'ONE_WAY', 'FULLY'] as const
export const DOC_TYPES = ['CONFIRMATION', 'TERM_SHEET', 'SIGNED_SCAN', 'OTHER'] as const
export const LINK_RELATIONS = ['HEDGE', 'ROLLOVER', 'NETTING_SET', 'AMENDS', 'ORIGINAL'] as const
export const FRA_SETTLEMENTS = ['ADVANCE_DISCOUNTED', 'ARREARS'] as const
export const CURRENCY_OPTIONS = ['CZK', 'EUR', 'USD', 'GBP', 'CHF', 'PLN', 'HUF'] as const

export function enumLabel(v: string, t: T): string {
  const m: Record<string, [string, string]> = {
    ACTUAL: ['Skutečný', 'Actual'], PLANNED: ['Plánovaný', 'Planned'], HYPOTHETICAL: ['Hypotetický (jen ocenění)', 'Hypothetical (pricing only)'],
    PLATFORM: ['Obchodní platforma', 'Dealing platform'], VOICE: ['Telefon', 'Voice'], ELECTRONIC: ['Elektronicky', 'Electronic'], INTERNAL: ['Interní', 'Internal'],
    FROM_START: ['Od začátku', 'From start'], FROM_END: ['Od konce', 'From end'],
    PAY: ['Platím (pay)', 'Pay'], RECEIVE: ['Přijímám (receive)', 'Receive'],
    FIXED: ['Pevná sazba', 'Fixed'], FLOATING: ['Pohyblivá sazba', 'Floating'], NONE: ['Bez sazby', 'None'],
    BULLET: ['Jednorázově', 'Bullet'], LINEAR: ['Lineárně', 'Linear'], ANNUITY: ['Anuitně', 'Annuity'], CUSTOM: ['Vlastní splátkový kalendář', 'Custom'],
    INDEX: ['Na index', 'On index'], INDEX_PLUS_SPREAD: ['Na index + marži', 'On index + spread'],
    UNCOLLATERALISED: ['Bez kolaterálu', 'Uncollateralised'], PARTIALLY: ['Částečně', 'Partially'], ONE_WAY: ['Jednostranně', 'One way'], FULLY: ['Plně', 'Fully'],
    CONFIRMATION: ['Konfirmace', 'Confirmation'], TERM_SHEET: ['Term sheet', 'Term sheet'], SIGNED_SCAN: ['Podepsaný sken', 'Signed scan'], OTHER: ['Jiný', 'Other'],
    HEDGE: ['Zajištění', 'Hedge'], ROLLOVER: ['Prodloužení', 'Roll-over'], NETTING_SET: ['Netting set', 'Netting set'], AMENDS: ['Mění', 'Amends'], ORIGINAL: ['Původní', 'Original'],
    ADVANCE_DISCOUNTED: ['Diskontně předem', 'Advance, discounted'], ARREARS: ['Zpětně', 'In arrears'],
    ONCE: ['Jednou', 'Once'], DAILY: ['Denně', 'Daily'], WEEKLY: ['Týdně', 'Weekly'], MONTHLY: ['Měsíčně', 'Monthly'], QUARTERLY: ['Čtvrtletně', 'Quarterly'],
    SEMI_ANNUAL: ['Půlročně', 'Semi-annual'], ANNUAL: ['Ročně', 'Annual'],
    SHORT_INITIAL: ['Krátké počáteční', 'Short initial'], LONG_INITIAL: ['Dlouhé počáteční', 'Long initial'], SHORT_FINAL: ['Krátké závěrečné', 'Short final'], LONG_FINAL: ['Dlouhé závěrečné', 'Long final'],
  }
  const x = m[v]
  return x ? t(x[0], x[1]) : v
}

// ── flow / event / payment / fixing status vocabulary ──────────────────────────────────────────
export function flowKindLabel(k: string, t: T): string {
  switch (k) {
    case 'PRINCIPAL': return t('Jistina', 'Principal')
    case 'INTEREST': return t('Úrok', 'Interest')
    case 'FEE': return t('Poplatek', 'Fee')
    case 'NOTIONAL_EXCHANGE': return t('Výměna jistiny', 'Notional exchange')
    case 'PREMIUM': return t('Prémie', 'Premium')
    default: return k
  }
}
export function rateSourceLabel(k: string, t: T): string {
  switch (k) {
    case 'FIXED': return t('Pevná', 'Fixed')
    case 'OBSERVED': return t('Zafixovaná', 'Observed')
    case 'PROJECTED': return t('Projekce', 'Projected')
    case 'RATE_UNTIL_FIRST_FIXING': return t('Do prvního fixingu', 'Until first fixing')
    case 'MISSING': return t('Chybí', 'Missing')
    default: return k
  }
}
export function eventTypeLabel(k: string, t: T): string {
  switch (k) {
    case 'REFIXING': return t('Refixing', 'Refixing')
    case 'INTEREST_PAYMENT': return t('Platba úroku', 'Interest payment')
    case 'PRINCIPAL_PAYMENT': return t('Platba jistiny', 'Principal payment')
    case 'MATURITY': return t('Splatnost', 'Maturity')
    case 'OPTION_EXERCISE': return t('Uplatnění opce', 'Option exercise')
    case 'MARGIN_CALL': return t('Margin call', 'Margin call')
    case 'SETTLEMENT': return t('Vypořádání', 'Settlement')
    default: return k
  }
}
