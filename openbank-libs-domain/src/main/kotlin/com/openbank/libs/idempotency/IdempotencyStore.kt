// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.idempotency

import java.time.OffsetDateTime

data class IdempotencyRecord(
    val key: String,
    val statusCode: Int,
    val responseBody: String,
    val createdAt: OffsetDateTime,
)

/**
 * Replay protection for a mutating endpoint. Call [get] before doing the work and [save]
 * after, keyed on the caller's `Idempotency-Key` header.
 *
 * There is deliberately **no `@Idempotent` annotation** (#4011). One existed and was inert —
 * a plain `RUNTIME` marker with no `@InterceptorBinding` and no interceptor — so applying it
 * to a payment endpoint compiled, reviewed as correct, and let a duplicate `POST` through.
 * Anyone adding a declarative form must land the binding and the interceptor in the same
 * change; that is the only order that is ever safe.
 */
interface IdempotencyStore {
    suspend fun get(key: String): IdempotencyRecord?
    suspend fun save(key: String, statusCode: Int, responseBody: String, ttlSeconds: Long = 86400)
}
