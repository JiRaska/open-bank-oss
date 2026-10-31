// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Pure presentation and four-eyes logic for the pension console (ADR-0334). The four-eyes checks
// here are a courtesy that hides a button the service would refuse: pension-fund-service answers
// 409/422 on a self-approval whatever the UI shows, and the page renders that refusal readably.
import type { Allocation, AnnuityProvider, Nav, PensionContract, StrategyChange } from './contracts'
import type { WriteResult } from './api'

type T = (cs: string, en: string) => string

const STATUS_LABELS: Record<string, [string, string]> = {
  DRAFT: ['Koncept', 'Draft'],
  PENDING_ACTIVATION: ['Čeká na aktivaci', 'Pending activation'],
  ACTIVE: ['Aktivní', 'Active'],
  SUSPENDED: ['Přerušené placení', 'Contributions paused'],
  TERMINATING: ['Ukončuje se', 'Terminating'],
  PAID_OUT: ['Vyplaceno', 'Paid out'],
  TRANSFERRED_OUT: ['Převedeno', 'Transferred out'],
  CLOSED: ['Uzavřeno', 'Closed'],
  CALCULATED: ['Spočteno', 'Calculated'],
  PUBLISHED: ['Zveřejněno', 'Published'],
  REJECTED: ['Zamítnuto', 'Rejected'],
  SUPERSEDED: ['Nahrazeno', 'Superseded'],
  PENDING_APPROVAL: ['Čeká na schválení', 'Pending approval'],
  APPROVED: ['Schváleno', 'Approved'],
  APPLIED: ['Účinné', 'Applied'],
  DISABLED: ['Vypnuto', 'Disabled'],
  OFFERED: ['Nabídnuto', 'Offered'],
  SELECTED: ['Vybráno', 'Selected'],
  PREMIUM_SENT: ['Pojistné odesláno', 'Premium sent'],
  CANCELLED: ['Zrušeno', 'Cancelled'],
  FAILED: ['Selhalo', 'Failed'],
  DUE: ['K vrácení', 'Due'],
  REPORTED: ['Nahlášeno', 'Reported'],
  CONFIRMED: ['Potvrzeno', 'Confirmed'],
  SETTLED: ['Vypořádáno', 'Settled'],
}

/** A status the console does not know yet renders verbatim, never as an error. */
export function statusLabel(status: string, t: T): string {
  const pair = STATUS_LABELS[status]
  return pair ? t(pair[0], pair[1]) : status
}

export type TimelineEvent = { at: string; label: string }

/**
 * The contract's lifecycle as far as the contract itself records it: creation, every strategy
 * election, the start date and the current status. Transition-level history (who moved it, when)
 * is not in the S1 contract; this timeline does not invent it.
 */
export function contractTimeline(c: PensionContract, t: T): TimelineEvent[] {
  const events: TimelineEvent[] = []
  if (c.createdAt) events.push({ at: c.createdAt, label: t('Smlouva založena', 'Contract created') })
  for (const s of c.strategyHistory) {
    const at = s.electedAt ?? s.effectiveFrom
    if (at) events.push({ at, label: t(`Strategie ${s.strategyCode} od ${s.effectiveFrom ?? '—'}`, `Strategy ${s.strategyCode} from ${s.effectiveFrom ?? '—'}`) })
  }
  if (c.startDate) events.push({ at: c.startDate, label: t('Počátek smlouvy', 'Contract start') })
  if (c.updatedAt) events.push({ at: c.updatedAt, label: t(`Aktuální stav: ${statusLabel(c.status, t)}`, `Current status: ${statusLabel(c.status, t)}`) })
  return events.sort((a, b) => a.at.localeCompare(b.at))
}

/** Approve/reject is offered only on a pending change somebody ELSE submitted. */
export function canDecideChange(change: StrategyChange, actor: string | null): boolean {
  return change.status === 'PENDING_APPROVAL' && actor !== null && change.submittedBy !== actor
}

/** Apply is offered on an approved change whose effective date has arrived. */
export function canApplyChange(change: StrategyChange, today: string): boolean {
  return change.status === 'APPROVED' && change.effectiveDate <= today
}

/** Publish/reject is offered only on a calculated NAV somebody ELSE calculated. */
export function canDecideNav(nav: Nav, actor: string | null): boolean {
  return nav.status === 'CALCULATED' && actor !== null && nav.calculatedBy !== actor
}

/**
 * The same invariants FundStrategy enforces, checked before the call so the operator sees which
 * row is wrong: weights and bands in [0, 1], weight inside its band, each fund once, sum exactly 1.
 */
export function allocationProblem(rows: Allocation[], t: T): string | null {
  if (rows.length === 0) return t('Alokace musí obsahovat aspoň jeden fond.', 'An allocation needs at least one fund.')
  if (new Set(rows.map(r => r.fundId)).size !== rows.length) return t('Každý fond smí být v alokaci jen jednou.', 'A fund may appear only once.')
  for (const r of rows) {
    if (!r.fundId) return t('Vyberte fond.', 'Choose a fund.')
    if ([r.weight, r.lowerBand, r.upperBand].some(v => !Number.isFinite(v) || v < 0 || v > 1)) {
      return t('Váhy a pásma musí ležet v intervalu 0–1.', 'Weights and bands must lie within 0–1.')
    }
    if (r.lowerBand > r.weight || r.weight > r.upperBand) return t('Váha musí ležet uvnitř svého pásma.', 'Each weight must lie inside its band.')
  }
  // Integer basis points: 0.1 + 0.2 must count as 0.3.
  const total = rows.reduce((sum, r) => sum + Math.round(r.weight * 10000), 0)
  if (total !== 10000) return t(`Součet vah musí být přesně 1 (nyní ${total / 10000}).`, `Weights must sum to exactly 1 (now ${total / 10000}).`)
  return null
}

/** A refusal rendered for a person: the service's code when it sent one, never a bare status line. */
export function refusalText(res: Extract<WriteResult<unknown>, { ok: false }>, action: string, t: T): string {
  if (res.kind === 'forbidden') return t(`${action}: k této akci nemáte oprávnění.`, `${action}: you are not permitted to do this.`)
  if (res.kind === 'refused') {
    return res.code
      ? t(`${action} odmítnuto: ${res.code}`, `${action} refused: ${res.code}`)
      : t(`${action} odmítnuto službou.`, `${action} was refused by the service.`)
  }
  return t(`${action} se nepodařilo — služba není dostupná.`, `${action} failed — the service is not available.`)
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

/** Contract and fund ids are UUIDs; anything else is rejected before a call is made. */
export function isUuid(value: string): boolean {
  return UUID.test(value.trim())
}

/**
 * Activation approval is offered only on a partner pending activation that somebody ELSE both
 * edited and asked to activate. The service refuses a self-approval (403) whatever the UI shows.
 */
export function canApproveAnnuityProvider(p: AnnuityProvider, actor: string | null): boolean {
  return p.status === 'PENDING_ACTIVATION' && actor !== null
    && p.proposedBy !== actor && p.activationRequestedBy !== actor
}

/** Activation can be requested on a DRAFT with proposed terms. */
export function canRequestAnnuityActivation(p: AnnuityProvider): boolean {
  return p.status === 'DRAFT' && p.proposedTerms != null
}
