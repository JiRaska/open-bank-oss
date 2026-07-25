// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The card state machine, mirrored from the service that owns it.
//
// Source of truth: openbank-card-issuance-service
//   - domain/model/Card.kt  — activate()/suspend()/resume()/block()/cancel() each
//     `require(...)` their legal source statuses; CANCELLABLE_STATUSES and
//     TERMINAL_STATUSES are the aggregate's own constants.
//   - infrastructure/rest/CardResource.kt — one POST per transition, all under
//     `X-Operator-Id`; block/cancel additionally take a `{ "reason": … }` body.
//
// Why the UI holds a copy: an illegal transition is refused by the aggregate with
// an IllegalArgumentException, which libs' CommonExceptionMappers turns into a
// bare 400. Offering the operator a button that can only ever produce that 400 is
// the anti-pattern this table exists to prevent — the console offers exactly the
// transitions the aggregate would accept, and nothing else.
//
// Keep this in sync with Card.kt if the aggregate's guards ever change.

export const CARD_STATUSES = [
  'PENDING',
  'ACTIVE',
  'SUSPENDED',
  'BLOCKED',
  'EXPIRED',
  'CANCELLED',
] as const

export type CardStatus = (typeof CARD_STATUSES)[number]

/** The five lifecycle endpoints on CardResource; the value is the URL segment. */
export type CardAction = 'activate' | 'suspend' | 'resume' | 'block' | 'cancel'

export interface CardTransition {
  action: CardAction
  /** Status the card holds once the service accepts the call. */
  to: CardStatus
  /**
   * True when the target status can never be left again *by this action's intent*.
   * BLOCKED is not strictly terminal (a blocked card may still be cancelled), but
   * it is irreversible — nothing puts a blocked card back in service — so the UI
   * treats block and cancel alike: both need a confirmation and a written reason.
   */
  irreversible: boolean
  /** The service requires a non-blank reason (block); cancel carries one for audit. */
  reason: boolean
}

const BLOCK: CardTransition = { action: 'block', to: 'BLOCKED', irreversible: true, reason: true }
const CANCEL: CardTransition = { action: 'cancel', to: 'CANCELLED', irreversible: true, reason: true }

/**
 * Legal transitions per source status.
 *
 * EXPIRED and CANCELLED are terminal (Card.TERMINAL_STATUSES) — no action applies.
 * Note that **nothing in card-issuance produces EXPIRED**: there is no expiry job,
 * scheduler or batch anywhere in the service, so the status exists in the enum and
 * in the lifecycle diagram but is currently unreachable at runtime. The UI says so
 * rather than implying an expiry sweep that does not exist.
 */
export const CARD_TRANSITIONS: Record<CardStatus, readonly CardTransition[]> = {
  PENDING: [{ action: 'activate', to: 'ACTIVE', irreversible: false, reason: false }, CANCEL],
  ACTIVE: [{ action: 'suspend', to: 'SUSPENDED', irreversible: false, reason: false }, BLOCK, CANCEL],
  SUSPENDED: [{ action: 'resume', to: 'ACTIVE', irreversible: false, reason: false }, BLOCK, CANCEL],
  BLOCKED: [CANCEL],
  EXPIRED: [],
  CANCELLED: [],
}

/** Transitions the aggregate would accept from `status`; empty for an unknown status. */
export function legalTransitions(status: string | undefined | null): readonly CardTransition[] {
  if (!status) return []
  return CARD_TRANSITIONS[status as CardStatus] ?? []
}

/** True for a status a card can never leave (Card.TERMINAL_STATUSES). */
export function isTerminal(status: string | undefined | null): boolean {
  return status === 'CANCELLED' || status === 'EXPIRED'
}
