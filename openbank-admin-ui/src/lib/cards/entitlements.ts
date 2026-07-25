// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The product-catalog card entitlement gate, mirrored client-side.
//
// Source of truth: openbank-card-issuance-service
//   application/usecase/CardService.kt → enforceEntitlements(cmd), which throws a
//   CardEntitlementException (HTTP 409, body `code`) in exactly four cases:
//     CARD_PRODUCT_DISABLED     — cardConfig.enabled == false
//     CARD_VIRTUAL_NOT_ALLOWED  — VIRTUAL/SINGLE_USE requested, virtualCardAllowed == false
//     CARD_NETWORK_NOT_ALLOWED  — network not in cardConfig.networks
//     CARD_QUOTA_EXCEEDED       — live cards (PENDING/ACTIVE/SUSPENDED) >= maxCards
//
// The console evaluates the same four rules BEFORE submitting so the operator is
// told "this party already holds 3 of 3 cards on CURRENT_CZK" up front, rather
// than discovering it as a 409 after filling in a form. The server remains the
// authority — this is an explanation layer, never a substitute for its check.

import type { CardEntitlements, CardNetwork, CardType } from './types'
import { CARD_TYPES, VIRTUAL_FORM_TYPES } from './types'

/**
 * `maxCards`/`remaining` sentinel for "no known cap": the service answers -1 when
 * product-catalog could not be consulted (`source: 'FALLBACK'`) — deliberately not
 * 0, which would read as "nothing left". See CardEntitlements.UNLIMITED.
 */
export const UNLIMITED = -1

export interface Quota {
  /** False when the cap is unknown (FALLBACK) — do not render "x of y". */
  known: boolean
  max: number
  issued: number
  remaining: number
  exhausted: boolean
}

export function quotaOf(e: CardEntitlements | null | undefined): Quota {
  const max = e?.maxCards ?? UNLIMITED
  const issued = e?.issued ?? 0
  const remaining = e?.remaining ?? UNLIMITED
  const known = max !== UNLIMITED && remaining !== UNLIMITED
  return { known, max, issued, remaining, exhausted: known && remaining <= 0 }
}

/** Why an issue would be refused. Names mirror the service's CardErrorCode. */
export type IssueBlocker =
  | 'CARD_PRODUCT_DISABLED'
  | 'CARD_VIRTUAL_NOT_ALLOWED'
  | 'CARD_NETWORK_NOT_ALLOWED'
  | 'CARD_QUOTA_EXCEEDED'

/**
 * Every entitlement rule the requested (type, network) breaks — empty when the
 * service would accept it. A missing/unknown entitlement document blocks nothing:
 * the service itself skips the whole gate when the catalog lookup is Unavailable.
 */
export function issueBlockers(
  e: CardEntitlements | null | undefined,
  request: { cardType: CardType; network: CardNetwork },
): IssueBlocker[] {
  if (!e) return []
  const blockers: IssueBlocker[] = []
  if (!e.enabled) blockers.push('CARD_PRODUCT_DISABLED')
  if (VIRTUAL_FORM_TYPES.includes(request.cardType) && !virtualAllowed(e, request.cardType)) {
    blockers.push('CARD_VIRTUAL_NOT_ALLOWED')
  }
  if (e.networks.length > 0 && !e.networks.includes(request.network)) {
    blockers.push('CARD_NETWORK_NOT_ALLOWED')
  }
  if (quotaOf(e).exhausted) blockers.push('CARD_QUOTA_EXCEEDED')
  return blockers
}

/**
 * SINGLE_USE rides `virtualCardAllowed` in the catalog (the service sets
 * `singleUseAllowed = config.virtualCardAllowed`), but we honour the field the
 * API actually returned rather than assuming they agree.
 */
function virtualAllowed(e: CardEntitlements, type: CardType): boolean {
  return type === 'SINGLE_USE' ? e.singleUseAllowed : e.virtualCardAllowed
}

/** Card types this product allows. Physical form factors are never gated. */
export function allowedCardTypes(e: CardEntitlements | null | undefined): CardType[] {
  if (!e) return [...CARD_TYPES]
  return CARD_TYPES.filter(type => !VIRTUAL_FORM_TYPES.includes(type) || virtualAllowed(e, type))
}

/** Networks this product allows; all of them when the document lists none. */
export function allowedNetworks(
  e: CardEntitlements | null | undefined,
  all: readonly CardNetwork[],
): CardNetwork[] {
  if (!e || e.networks.length === 0) return [...all]
  return all.filter(n => e.networks.includes(n))
}
