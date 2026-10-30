// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.rest

/** The one idempotency header every pension-service POST binds and its openapi.yaml publishes. */
const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

const val MAX_IDEMPOTENCY_KEY_LENGTH = 256

/**
 * Required on every POST (money-path idempotency rule, ADR-0334 S8); validated before any work is
 * done. A replay with the same key is answered from the stored first response by
 * [IdempotencyReplayFilter], so the handler body runs once per key.
 */
fun requireIdempotencyKey(value: String?): String {
    val key = requireNotNull(value) { "header '$IDEMPOTENCY_KEY_HEADER' is required" }
    require(key.isNotBlank() && key.length <= MAX_IDEMPOTENCY_KEY_LENGTH) {
        "$IDEMPOTENCY_KEY_HEADER must be 1..$MAX_IDEMPOTENCY_KEY_LENGTH characters"
    }
    return key
}
