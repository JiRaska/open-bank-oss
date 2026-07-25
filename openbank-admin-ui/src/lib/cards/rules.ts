// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The aggregate's non-lifecycle invariants, mirrored for the operator console.
//
// Source of truth: openbank-card-issuance-service domain/model/Card.kt
//   - withLimits()   `require(status in setOf(ACTIVE, SUSPENDED, PENDING))`
//                    `require(dailyMinor >= 0 && monthlyMinor >= 0)`
//                    `require(dailyMinor <= monthlyMinor)`
//   - withControls() `require(status in setOf(ACTIVE, SUSPENDED, PENDING))`
//
// Same reasoning as lifecycle.ts: an IllegalArgumentException from the aggregate
// comes back as a bare 400 through libs' CommonExceptionMappers, so a form that
// can only ever produce that 400 is a form that lies to the operator. We check
// the invariant before the call AND say which invariant is failing — a greyed-out
// button with no explanation is the thing this file exists to avoid.

import type { CardStatus } from './lifecycle'

/**
 * Statuses on which limits and controls may be changed. Not a lifecycle concept:
 * a BLOCKED card is still `cancel`-able but has no spend left to cap.
 */
export const MUTABLE_SETTINGS_STATUSES: readonly CardStatus[] = ['PENDING', 'ACTIVE', 'SUSPENDED']

export function canEditSettings(status: string | null | undefined): boolean {
  return MUTABLE_SETTINGS_STATUSES.includes(status as CardStatus)
}

/** Why a limits edit would be refused. Ordered as the aggregate checks them. */
export type LimitViolation =
  /** Card.withLimits: status is not ACTIVE/SUSPENDED/PENDING. */
  | 'status'
  /** The text in the field is not a value this currency can hold. */
  | 'daily_not_a_number'
  | 'monthly_not_a_number'
  /** Card.withLimits: `require(dailyMinor <= monthlyMinor)`. */
  | 'daily_exceeds_monthly'

export interface LimitDraft {
  status: string | null | undefined
  /** Minor units, or `null` when the field's text did not parse. */
  dailyMinorUnits: number | null
  monthlyMinorUnits: number | null
}

/**
 * Every invariant a limits edit violates, empty when the aggregate would accept it.
 *
 * Note there is no `negative` violation: `parseMajorToMinor` refuses a minus sign
 * outright, so a negative value can never reach here from the form. The aggregate's
 * `require(>= 0)` is still mirrored — a negative that arrives some other way is
 * reported as an unparseable field rather than being sent and 400'd.
 */
export function validateLimits(draft: LimitDraft): LimitViolation[] {
  const violations: LimitViolation[] = []
  if (!canEditSettings(draft.status)) violations.push('status')
  const { dailyMinorUnits: daily, monthlyMinorUnits: monthly } = draft
  if (daily === null || daily < 0) violations.push('daily_not_a_number')
  if (monthly === null || monthly < 0) violations.push('monthly_not_a_number')
  if (daily !== null && monthly !== null && daily >= 0 && monthly >= 0 && daily > monthly) {
    violations.push('daily_exceeds_monthly')
  }
  return violations
}

/** True when the aggregate would accept this limits edit. */
export function limitsAcceptable(draft: LimitDraft): boolean {
  return validateLimits(draft).length === 0
}

/**
 * Whether an operator-visible control is currently offered, and if not, WHY.
 * The reason is what the UI renders next to the disabled control — "no action"
 * is a dead end, "terminal card: cancelled cards keep their last limits" is not.
 */
export type SettingsBlock = 'terminal' | 'blocked' | 'unknown_status' | null

export function settingsBlock(status: string | null | undefined): SettingsBlock {
  if (canEditSettings(status)) return null
  if (status === 'CANCELLED' || status === 'EXPIRED') return 'terminal'
  if (status === 'BLOCKED') return 'blocked'
  return 'unknown_status'
}
