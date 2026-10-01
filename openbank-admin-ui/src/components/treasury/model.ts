// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Pure view logic for the Treasury section (ADR-0315). Kept out of the pages so it is unit-tested
// without rendering, and so every page agrees on who may do what.
import type { Tone } from '@/components/ui/tone'
import { hasPermission } from '@/lib/auth/roles'
import { isIsoDate } from '@/components/balance-sheet/model'
import { CNB_COUNTERPARTY_ID, type Counterparty, type Deal, type DealProduct, type DealState, type MatchType, type Product } from './contracts'
import type { WriteResult } from './api'

type T = (cs: string, en: string) => string

export const STATE_TONE: Record<DealState, Tone> = {
  DRAFT: 'neutral', PENDING_APPROVAL: 'warning', BOOKED: 'info', CONFIRMED: 'info', SETTLED: 'success',
  MATURED: 'success', CANCELLED: 'neutral', REVERSED: 'danger',
}

/** A response product is extensible; unknown future products render as their raw name. */
export function productLabel(p: DealProduct, t: T): string {
  switch (p) {
    case 'MM_PLACEMENT': return t('Umístění na peněžním trhu', 'MM placement')
    case 'MM_BORROWING': return t('Přijetí na peněžním trhu', 'MM borrowing')
    case 'CNB_DEPOSIT_FACILITY': return t('Depozitní facilita ČNB', 'ČNB deposit facility')
    case 'CNB_LOMBARD': return t('ČNB lombardní úvěr', 'ČNB lombard borrowing')
    case 'FX_SPOT': return t('FX spot', 'FX spot')
    default: return p
  }
}

// ── FX spot (#10896) ─────────────────────────────────────────────────────────────────────────────

/** Mirrors the domain's `DayCount.spotDate`: T+2 business days, weekends skipped, no holiday
 * calendar. Client-side preview only — the server (Deal.kt) is the actual control. */
export function fxSpotDate(tradeDateIso: string): string {
  const nextBusinessDay = (d: Date): Date => {
    const next = new Date(d)
    next.setUTCDate(next.getUTCDate() + 1)
    while (next.getUTCDay() === 0 || next.getUTCDay() === 6) next.setUTCDate(next.getUTCDate() + 1)
    return next
  }
  let d = new Date(`${tradeDateIso}T00:00:00Z`)
  for (let i = 0; i < 2; i += 1) d = nextBusinessDay(d)
  return d.toISOString().slice(0, 10)
}

/** Mirrors `Deal.counterAmountOf`: foreign amount x rate, half-up to 2 dp. A PREVIEW only — the
 * server recomputes and stores the authoritative value (`Deal.fx.counterAmount`). */
export function fxCounterAmount(foreignAmount: number, rate: number): number | null {
  if (!Number.isFinite(foreignAmount) || !Number.isFinite(rate)) return null
  return Math.round((foreignAmount * rate + Number.EPSILON) * 100) / 100
}

/** Exactly one leg must be CZK (openapi.yaml DraftDealRequest: "exactly one of buy/sell is CZK").
 * Both CZK or neither CZK is refused — client-side courtesy; the server 400s regardless. */
export function isValidFxPair(buyCurrency: string, sellCurrency: string): boolean {
  return buyCurrency !== sellCurrency && (buyCurrency === 'CZK') !== (sellCurrency === 'CZK')
}

/** The foreign currency is whichever leg is not CZK. */
export function fxForeignCurrency(buyCurrency: string, sellCurrency: string): string | null {
  if (!isValidFxPair(buyCurrency, sellCurrency)) return null
  return buyCurrency === 'CZK' ? sellCurrency : buyCurrency
}

export function stateLabel(s: DealState, t: T): string {
  switch (s) {
    case 'DRAFT': return t('Koncept', 'Draft')
    case 'PENDING_APPROVAL': return t('Čeká na schválení', 'Pending approval')
    case 'BOOKED': return t('Zaúčtováno', 'Booked')
    case 'CONFIRMED': return t('Potvrzeno protistranou', 'Confirmed')
    case 'SETTLED': return t('Vypořádáno', 'Settled')
    case 'MATURED': return t('Splatné', 'Matured')
    case 'CANCELLED': return t('Zrušeno', 'Cancelled')
    case 'REVERSED': return t('Stornováno', 'Reversed')
  }
}

/** Assets consume the counterparty's credit limit (ProductType.isAsset); borrowing does not —
 * MM_BORROWING and the ČNB lombard facility are both liabilities (Deal.kt ProductType.isAsset). */
export const isAssetProduct = (p: Product) => p !== 'MM_BORROWING' && p !== 'CNB_LOMBARD'

/** Products fixed to CZK, counterparty ČNB, and an overnight (next-business-day) maturity
 * (Deal.kt ProductType.isCnbFacility). */
export const isCnbFacility = (p: Product) => p === 'CNB_DEPOSIT_FACILITY' || p === 'CNB_LOMBARD'

/** Counterparties eligible for a product: ČNB only for a ČNB facility, banks otherwise. */
export function eligibleCounterparties(all: Counterparty[], product: Product): Counterparty[] {
  return all.filter(c => (isCnbFacility(product) ? c.counterpartyId === CNB_COUNTERPARTY_ID : c.kind === 'BANK'))
}

/**
 * The next business day after `valueDate` (Monday-Friday; no holiday calendar), mirroring the
 * backend's `DayCount.nextBusinessDay` (treasury `Deal.kt`) — a Friday value date matures the
 * following Monday. Returns '' for an invalid/empty input rather than a bogus date.
 */
export function nextBusinessDay(valueDate: string): string {
  if (!isIsoDate(valueDate)) return ''
  const d = new Date(`${valueDate}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + 1)
  while (d.getUTCDay() === 0 || d.getUTCDay() === 6) d.setUTCDate(d.getUTCDate() + 1)
  return d.toISOString().slice(0, 10)
}

/** The distinct counterparties (the API returns one row per counterparty and currency). */
export function distinctCounterparties(all: Counterparty[]): Counterparty[] {
  const seen = new Map<string, Counterparty>()
  for (const c of all) if (!seen.has(c.counterpartyId)) seen.set(c.counterpartyId, c)
  return [...seen.values()]
}

export function headroomFor(all: Counterparty[], counterpartyId: string, currency: string): Counterparty | null {
  return all.find(c => c.counterpartyId === counterpartyId && c.currency === currency) ?? null
}

/** Client-side courtesy warning only — the server's LIMIT_BREACHED 422 is the control. */
export function exceedsHeadroom(product: Product, principal: number, row: Counterparty | null): boolean {
  return isAssetProduct(product) && row !== null && Number.isFinite(principal) && principal > row.headroom
}

export function utilisation(row: Pick<Counterparty, 'limit' | 'exposure'>): number {
  if (row.limit <= 0) return row.exposure > 0 ? 1 : 0
  return Math.min(1, Math.max(0, row.exposure / row.limit))
}

export type DealActions = {
  submit: boolean; cancel: boolean; approve: boolean; reject: boolean
  /** Counterparty confirmation received (ADR-0315 D2): back office, never the deal's own creator/submitter. */
  confirm: boolean
  settle: boolean; mature: boolean; reverse: boolean
  /** Senior override of a breached limit (ADR-0315 D4) — a role and state narrower than approve. */
  overrideLimit: boolean
  /** Approve is withheld because the viewer created or submitted this deal (four-eyes courtesy). */
  ownDeal: boolean
}

/**
 * Which buttons a person sees on a deal. Roles mirror TreasuryResource's method-level
 * @RolesAllowed; states mirror Deal's lifecycle checks. FOUR-EYES: approve is hidden when the
 * viewer's principal name (principalNameFromToken — the claim treasury records as createdBy /
 * submittedBy) matches either — a courtesy, not the control: the server's 422 still is.
 */
export function dealActions(
  deal: Pick<Deal, 'state' | 'createdBy' | 'submittedBy'> & Partial<Pick<Deal, 'limitCheck' | 'limitOverride'>>,
  actor: string | null,
  roles: string[],
): DealActions {
  const dealer = hasPermission(roles, 'treasury:deal:create')
  const approver = hasPermission(roles, 'treasury:deal:approve')
  const canCancel = hasPermission(roles, 'treasury:deal:cancel')
  const canOverrideLimit = hasPermission(roles, 'treasury:deal:override-limit')
  const ownDeal = actor !== null && (deal.createdBy === actor || deal.submittedBy === actor)
  const pending = deal.state === 'PENDING_APPROVAL'
  // Mirrors the domain's own guard (Deal.overrideLimit): PENDING_APPROVAL, breached, and not
  // already overridden — an override already recorded needs a fresh submit/limit check, not a
  // second one. Four-eyes (never the deal's own creator/submitter) is the server's 422; withheld
  // here too as the same courtesy `approve` gets.
  const overridable = pending && deal.limitCheck?.breached === true && !deal.limitOverride
  return {
    submit: dealer && deal.state === 'DRAFT',
    cancel: canCancel && (deal.state === 'DRAFT' || pending),
    approve: approver && pending && !ownDeal,
    reject: approver && pending,
    confirm: approver && deal.state === 'BOOKED' && !ownDeal,
    // Settlement needs CONFIRMED; a deployment with confirmation.required=false also settles from
    // BOOKED, so the button stays offered there and the server's 409 is the answer where it is not.
    settle: approver && (deal.state === 'CONFIRMED' || deal.state === 'BOOKED'),
    mature: approver && deal.state === 'SETTLED',
    reverse: approver && (deal.state === 'BOOKED' || deal.state === 'CONFIRMED' || deal.state === 'SETTLED'),
    overrideLimit: canOverrideLimit && overridable && !ownDeal,
    ownDeal: pending && ownDeal,
  }
}

export const MATCH_TYPE_TONE: Record<MatchType, Tone> = { EXACT: 'success', AMOUNT_DATE: 'warning' }

export function matchTypeLabel(m: MatchType, t: T): string {
  switch (m) {
    case 'EXACT': return t('Přesná shoda', 'Exact match')
    case 'AMOUNT_DATE': return t('Podle částky a data', 'Amount + date')
  }
}

/**
 * A `null` difference is NOT a `0` — the server has not computed it, and the two must never render
 * the same (Instant.EPOCH-style trap: a sentinel a reader agrees is "clean" is not evidence it is).
 * Returns null so the caller renders a dash/placeholder instead of a number.
 */
export function formatDifference(v: number | null, money: (n: number) => string): string | null {
  return v === null ? null : money(v)
}

/** A non-zero difference (after the null check above) is the one thing this page must highlight. */
export const isNonZeroDifference = (v: number | null): boolean => v !== null && v !== 0

/** A readable sentence for a refused write — never a bare status line (graceful-state rule). */
export function refusalText(res: WriteResult<unknown>, action: string, t: T): string {
  if (res.ok) return ''
  const detail = res.message ? ` — ${res.message}` : ''
  if (res.kind === 'forbidden') {
    return t(`${action}: nemáte oprávnění k tomuto kroku.`, `${action}: you are not permitted to take this step.`)
  }
  if (res.kind === 'unavailable') return t(`${action}: treasury-service je nedostupný.`, `${action}: treasury-service is unavailable.`)
  switch (res.code) {
    case 'FOUR_EYES_VIOLATION':
      return t(`${action}: server odmítl podle pravidla čtyř očí — schválit musí jiná osoba, než která obchod vytvořila či předložila${detail}`, `${action}: refused under the four-eyes rule — a different person from the one who created or submitted the deal must approve${detail}`)
    case 'LIMIT_BREACHED':
      return t(`${action}: překročen limit protistrany${detail}`, `${action}: counterparty limit breached${detail}`)
    case 'ACTOR_NOT_PERMITTED':
      return t(`${action}: tento krok smí provést jen člověk${detail}`, `${action}: only a person may take this step${detail}`)
    case 'INVALID_STATE':
      return t(`${action}: obchod je v jiném stavu, obnovte stránku${detail}`, `${action}: the deal is in a different state, refresh the page${detail}`)
    case 'NOT_FOUND':
      return t(`${action}: obchod nebyl nalezen.`, `${action}: deal not found.`)
    default:
      return t(`${action}: treasury-service žádost odmítl${detail}`, `${action}: treasury-service refused the request${detail}`)
  }
}
