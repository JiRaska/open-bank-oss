// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Request builders for the card-processing token and dispute actions that reach a card network.
//
// card-processing requires an `Idempotency-Key` on every such POST and reserves it before the
// network is called: a retry with the same key replays the first result instead of asking the
// network again, and a request sent WITHOUT a key is a 400. One fresh key per operator action —
// a key is bound to its request body, so reusing one for a different action is a 409.

import type { NetworkTokenStatus } from '@/lib/cards/lifecycleTypes'

export interface LifecycleRequest {
  path: string
  init: RequestInit
}

/** One fresh key per operator action. */
export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}

/** POST /api/v1/card-tokens/{tokenReference}/status — suspend, resume or delete a token. */
export function tokenStatusRequest(
  tokenReference: string,
  status: NetworkTokenStatus,
  idempotencyKey: string = newIdempotencyKey(),
): LifecycleRequest {
  return {
    path: `/api/v1/card-tokens/${encodeURIComponent(tokenReference)}/status`,
    init: {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
      body: JSON.stringify({ status }),
    },
  }
}
