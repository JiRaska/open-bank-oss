// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Pure view logic for the Treasury section (ADR-0315). Kept out of the pages so it is unit-tested
// without rendering, and so every page agrees on who may do what.
import type { Tone } from '@/components/ui/tone'
import { hasPermission } from '@/lib/auth/roles'
import { CNB_COUNTERPARTY_ID, type Counterparty, type Deal, type DealState, type Product } from './contracts'
import type { WriteResult } from './api'

type T = (cs: string, en: string) => string

export const STATE_TONE: Record<DealState, Tone> = {
  DRAFT: 'neutral', PENDING_APPROVAL: 'warning', BOOKED: 'info', SETTLED: 'success',
  MATURED: 'success', CANCELLED: 'neutral', REVERSED: 'danger',
}

export function productLabel(p: Product, t: T): string {
  switch (p) {
    case 'MM_PLACEMENT': return t('Umístění na peněžním trhu', 'MM placement')
    case 'MM_BORROWING': return t('Přijetí na peněžním trhu', 'MM borrowing')
    case 'CNB_DEPOSIT_FACILITY': return t('Depozitní facilita ČNB', 'ČNB deposit facility')
  }
}

export function stateLabel(s: DealState, t: T): string {
  switch (s) {
    case 'DRAFT': return t('Koncept', 'Draft')
    case 'PENDING_APPROVAL': return t('Čeká na schválení', 'Pending approval')
    case 'BOOKED': return t('Zaúčtováno', 'Booked')
    case 'SETTLED': return t('Vypořádáno', 'Settled')
    case 'MATURED': return t('Splatné', 'Matured')
    case 'CANCELLED': return t('Zrušeno', 'Cancelled')
    case 'REVERSED': return t('Stornováno', 'Reversed')
  }
}

/** Assets consume the counterparty's credit limit (ProductType.isAsset); borrowing does not. */
export const isAssetProduct = (p: Product) => p !== 'MM_BORROWING'

/** Counterparties eligible for a product: ČNB only for the facility, banks otherwise. */
export function eligibleCounterparties(all: Counterparty[], product: Product): Counterparty[] {
  return all.filter(c => (product === 'CNB_DEPOSIT_FACILITY' ? c.counterpartyId === CNB_COUNTERPARTY_ID : c.kind === 'BANK'))
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
  settle: boolean; mature: boolean; reverse: boolean
  /** Approve is withheld because the viewer created or submitted this deal (four-eyes courtesy). */
  ownDeal: boolean
}

/**
 * Which buttons a person sees on a deal. Roles mirror TreasuryResource's method-level
 * @RolesAllowed; states mirror Deal's lifecycle checks. FOUR-EYES: approve is hidden when the
 * viewer's principal name (principalNameFromToken — the claim treasury records as createdBy /
 * submittedBy) matches either — a courtesy, not the control: the server's 422 still is.
 */
export function dealActions(deal: Pick<Deal, 'state' | 'createdBy' | 'submittedBy'>, actor: string | null, roles: string[]): DealActions {
  const dealer = hasPermission(roles, 'treasury:deal:create')
  const approver = hasPermission(roles, 'treasury:deal:approve')
  const canCancel = hasPermission(roles, 'treasury:deal:cancel')
  const ownDeal = actor !== null && (deal.createdBy === actor || deal.submittedBy === actor)
  const pending = deal.state === 'PENDING_APPROVAL'
  return {
    submit: dealer && deal.state === 'DRAFT',
    cancel: canCancel && (deal.state === 'DRAFT' || pending),
    approve: approver && pending && !ownDeal,
    reject: approver && pending,
    settle: approver && deal.state === 'BOOKED',
    mature: approver && deal.state === 'SETTLED',
    reverse: approver && (deal.state === 'BOOKED' || deal.state === 'SETTLED'),
    ownDeal: pending && ownDeal,
  }
}

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
