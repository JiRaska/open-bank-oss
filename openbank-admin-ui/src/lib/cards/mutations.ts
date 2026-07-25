// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// How a card MUTATION failed — the read-path classifier, extended.
//
// `classifyBffFailure` is built for reads and lumps every 4xx that isn't 401/404
// into `error`. A card write has failure modes an operator must be able to tell
// apart, so they get their own kinds:
//   400/422 — the aggregate refused it. Card.kt guards every transition, limit and
//             control change with `require(...)`; libs' CommonExceptionMappers maps
//             the IllegalArgumentException to a bare 400 (NOT 409). In practice:
//             the card moved under us, or the invariant we mirror drifted.
//   409     — CardEntitlementException. Its body carries the domain's own
//             machine-readable `code` (CardExceptionMappers.kt → ApiError.code), so
//             we can say WHICH product rule refused instead of "a rule did".
//   403     — the operator's Keycloak roles don't cover this endpoint.
//
// Extracted from the Cards page so the list and the detail view classify (and
// therefore explain) a failure identically.

import { classifyBffFailure, type BffFailure } from '@/lib/services/bff'
import type { IssueBlocker } from './entitlements'

export type MutationFailure =
  | BffFailure
  | 'illegal_transition'
  | 'conflict'
  | 'forbidden'
  | `conflict:${IssueBlocker}`

const ENTITLEMENT_CODES: readonly string[] = [
  'CARD_QUOTA_EXCEEDED',
  'CARD_PRODUCT_DISABLED',
  'CARD_NETWORK_NOT_ALLOWED',
  'CARD_VIRTUAL_NOT_ALLOWED',
]

/** The domain error code on an ApiError body, when there is one. Never throws. */
export async function cardErrorCode(res: Response): Promise<string | null> {
  try {
    const body = (await res.clone().json()) as { code?: unknown }
    return typeof body?.code === 'string' ? body.code : null
  } catch {
    return null
  }
}

export async function classifyMutation(res: Response): Promise<MutationFailure> {
  if (res.status === 400 || res.status === 422) return 'illegal_transition'
  if (res.status === 409) {
    const code = await cardErrorCode(res)
    return code && ENTITLEMENT_CODES.includes(code)
      ? (`conflict:${code}` as MutationFailure)
      : 'conflict'
  }
  if (res.status === 403) return 'forbidden'
  return classifyBffFailure(res)
}
