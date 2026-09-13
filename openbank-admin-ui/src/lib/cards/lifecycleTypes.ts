// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The shapes card-processing serves for network tokens and dispute cases (ADR-0283 phase 3).
// Mirrors openbank-card-processing-service/src/main/resources/openapi.yaml 1.1.0.

/** Where a token list came from. Required, never optional — see [TokenListResponse]. */
export type TokenReadSource = 'NETWORK' | 'LOCAL_MIRROR'

export type NetworkTokenStatus = 'ACTIVE' | 'SUSPENDED' | 'DELETED'

export interface NetworkTokenView {
  id: string
  cardId: string
  tokenReference: string
  requestorId: string
  requestorLabel: string
  last4: string
  status: NetworkTokenStatus
  scheme: string
  expiry: string | null
  provisionedAt: string
  updatedAt: string
}

/**
 * A token list AND the provenance of the answer.
 *
 * `source` is not decoration. `NETWORK` means the scheme answered on this request; `LOCAL_MIRROR`
 * means it did not and these are the rows the bank last recorded — possibly stale, and a screen
 * that renders them as current is telling an operator something it does not know.
 */
export interface TokenListResponse {
  tokens: NetworkTokenView[]
  source: TokenReadSource
  degradedReason: string | null
  count: number
}

export type DisputeStatus = 'OPEN' | 'EVIDENCE_SUBMITTED' | 'WON' | 'LOST' | 'WITHDRAWN'

export interface DisputeView {
  id: string
  authorizationId: string
  cardId: string
  networkCaseId: string
  reasonCode: string
  amountMinorUnits: number
  currencyCode: string
  status: DisputeStatus
  scheme: string
  /** The network's own status string, verbatim. Shown beside the bank status, never instead of it. */
  schemeStatus: string
  respondByDate: string | null
  evidenceReference: string | null
  openedAt: string
  updatedAt: string
}

export interface DisputeListResponse {
  disputes: DisputeView[]
  count: number
}

/** Minor units to a display string. The service speaks minor units; only the screen formats. */
export function formatMinorUnits(amountMinorUnits: number, currencyCode: string, locale: string): string {
  return new Intl.NumberFormat(locale, { style: 'currency', currency: currencyCode }).format(
    amountMinorUnits / 100,
  )
}

/**
 * Days left to respond, or null when the network gave no deadline.
 *
 * Negative is a real answer and is NOT clamped to zero: an expired window is the state an operator
 * most needs to see, and rendering it as "0 days" makes a missed deadline look like an urgent one.
 */
export function daysUntil(respondByDate: string | null, now: Date): number | null {
  if (!respondByDate) return null
  const due = Date.parse(`${respondByDate}T00:00:00Z`)
  if (Number.isNaN(due)) return null
  return Math.ceil((due - now.getTime()) / 86_400_000)
}
