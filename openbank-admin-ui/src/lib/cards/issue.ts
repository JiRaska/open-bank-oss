// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The operator-side "issue a card" flow, as a pure state machine.
//
// The flow exists to answer one design constraint: an operator must never type a
// UUID. `POST /api/v1/cards` needs a partyId, an accountId, a productCode and a
// currency — four values that are all *derived* here, by walking
//   party search → that party's accounts → that account's product → its currency,
// so the only things a human chooses are the card type and the network, and the
// only things they type are a search term and (optionally) the embossed name.
//
// Keeping the machine pure (no fetch, no React) is what makes the rules testable:
// "review is unreachable until an account is picked" is an assertion, not a hope.

import type { AccountRef, CardEntitlements, CardNetwork, CardType, PartyRef } from './types'
import { issueBlockers } from './entitlements'
import { validateLimits, type LimitViolation } from './rules'

/** IssueCardRequest defaults (CardDtos.kt) — the service applies these when omitted. */
export const DEFAULT_DAILY_LIMIT_MINOR = 500_000
export const DEFAULT_MONTHLY_LIMIT_MINOR = 5_000_000

export const ISSUE_STEPS = ['party', 'account', 'configure', 'review'] as const
export type IssueStep = (typeof ISSUE_STEPS)[number]

export interface IssueDraft {
  party: PartyRef | null
  account: AccountRef | null
  /** Resolved from the account's product (product-catalog `code`), never typed. */
  productCode: string | null
  /** The party's entitlements on that product; null while unresolved. */
  entitlements: CardEntitlements | null
  cardType: CardType
  network: CardNetwork
  cardholderName: string
  embossedName: string
  dailyMinorUnits: number | null
  monthlyMinorUnits: number | null
}

export function initialDraft(): IssueDraft {
  return {
    party: null,
    account: null,
    productCode: null,
    entitlements: null,
    cardType: 'DEBIT',
    network: 'VISA',
    cardholderName: '',
    embossedName: '',
    dailyMinorUnits: DEFAULT_DAILY_LIMIT_MINOR,
    monthlyMinorUnits: DEFAULT_MONTHLY_LIMIT_MINOR,
  }
}

/**
 * Embossing line: ASCII, upper case, ≤ 26 characters.
 *
 * ISO/IEC 7813 track 1 gives the cardholder name 26 characters and no accents, so
 * "Bohuslava Čermáková-Nováková" has to become "BOHUSLAVA CERMAKOVA-NOVAK" *somewhere*.
 * Doing it here — visibly, in a field the operator can still correct — beats letting
 * a personalisation bureau silently truncate it into something the customer disowns.
 */
export function toEmbossedName(legalName: string): string {
  return legalName
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^A-Za-z \-.']/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .toUpperCase()
    .slice(0, 26)
}

/** Party display name — trading name where a company has one, else the legal name. */
export function partyDisplayName(party: PartyRef | null | undefined): string {
  if (!party) return ''
  return (party.tradingName?.trim() || party.legalName?.trim() || '').trim()
}

/** Prefill the two name fields from the chosen party; never asks the operator to retype. */
export function withPartySelected(draft: IssueDraft, party: PartyRef): IssueDraft {
  const name = partyDisplayName(party)
  return {
    ...draft,
    party,
    // A different party invalidates everything downstream — account, product and
    // the entitlement document are all party-scoped.
    account: null,
    productCode: null,
    entitlements: null,
    cardholderName: name,
    embossedName: toEmbossedName(name),
  }
}

/** Choosing an account re-derives the product and therefore the entitlements. */
export function withAccountSelected(draft: IssueDraft, account: AccountRef): IssueDraft {
  return { ...draft, account, productCode: null, entitlements: null }
}

/** The card's currency is the account's — never a free choice, never typed. */
export function draftCurrency(draft: IssueDraft): string | null {
  return draft.account?.currencyCode ?? null
}

/** Reasons the flow refuses to leave `step`. Empty ⇒ the operator may continue. */
export type StepBlocker =
  | 'no_party'
  | 'no_account'
  | 'account_not_active'
  | 'no_product'
  | 'no_cardholder_name'
  | 'no_embossed_name'
  | `entitlement:${string}`
  | `limits:${LimitViolation}`

export function stepBlockers(step: IssueStep, draft: IssueDraft): StepBlocker[] {
  const blockers: StepBlocker[] = []
  if (step === 'party') {
    if (!draft.party) blockers.push('no_party')
    return blockers
  }
  if (step === 'account') {
    if (!draft.account) blockers.push('no_account')
    // An account must be able to carry a card: a CLOSED/FROZEN account still
    // exists and still lists, but issuing onto it is not a thing an operator
    // should be able to do by accident.
    else if (draft.account.status !== 'ACTIVE') blockers.push('account_not_active')
    return blockers
  }
  if (step === 'configure' || step === 'review') {
    if (!draft.productCode) blockers.push('no_product')
    if (!draft.cardholderName.trim()) blockers.push('no_cardholder_name')
    if (!draft.embossedName.trim()) blockers.push('no_embossed_name')
    for (const b of issueBlockers(draft.entitlements, { cardType: draft.cardType, network: draft.network })) {
      blockers.push(`entitlement:${b}`)
    }
    for (const v of validateLimits({
      // A card being issued lands in PENDING, which Card.withLimits accepts —
      // the limits carried by the issue request face the same invariants.
      status: 'PENDING',
      dailyMinorUnits: draft.dailyMinorUnits,
      monthlyMinorUnits: draft.monthlyMinorUnits,
    })) {
      blockers.push(`limits:${v}`)
    }
    return blockers
  }
  return blockers
}

export function canAdvance(step: IssueStep, draft: IssueDraft): boolean {
  return stepBlockers(step, draft).length === 0
}

export function nextStep(step: IssueStep): IssueStep {
  const i = ISSUE_STEPS.indexOf(step)
  return ISSUE_STEPS[Math.min(i + 1, ISSUE_STEPS.length - 1)]
}

export function prevStep(step: IssueStep): IssueStep {
  const i = ISSUE_STEPS.indexOf(step)
  return ISSUE_STEPS[Math.max(i - 1, 0)]
}

/** True when every step up to and including `step` is satisfied — drives the stepper. */
export function reachable(step: IssueStep, draft: IssueDraft): boolean {
  const target = ISSUE_STEPS.indexOf(step)
  for (let i = 0; i < target; i++) {
    if (!canAdvance(ISSUE_STEPS[i], draft)) return false
  }
  return true
}

export interface IssueCardRequestBody {
  partyId: string
  accountId: string
  productCode: string
  cardType: CardType
  network: CardNetwork
  cardholderName: string
  embossedName: string
  currency: string
  dailyLimitMinorUnits: number
  monthlyLimitMinorUnits: number
}

/**
 * The POST body, or `null` when the draft is not complete enough to send one.
 * Returning null rather than a partial object is deliberate: a half-filled issue
 * request is a 400 at best and a wrong card at worst.
 */
export function issueRequestBody(draft: IssueDraft): IssueCardRequestBody | null {
  const currency = draftCurrency(draft)
  if (!draft.party || !draft.account || !draft.productCode || !currency) return null
  // EVERY step, not just the last one: a body must not exist while an earlier
  // choice (e.g. a non-ACTIVE account) is still refused.
  if (!reachable('review', draft) || !canAdvance('review', draft)) return null
  if (draft.dailyMinorUnits === null || draft.monthlyMinorUnits === null) return null
  return {
    partyId: draft.party.id,
    accountId: draft.account.id,
    productCode: draft.productCode,
    cardType: draft.cardType,
    network: draft.network,
    cardholderName: draft.cardholderName.trim(),
    embossedName: draft.embossedName.trim(),
    currency,
    dailyLimitMinorUnits: draft.dailyMinorUnits,
    monthlyLimitMinorUnits: draft.monthlyMinorUnits,
  }
}
