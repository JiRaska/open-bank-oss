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
    /**
     * SHA-256 request fingerprint ([RequestFingerprint]) the response was stored under, or `null`
     * for a record written before fingerprints existed (or by a caller that never supplied one).
     * A `null` hash is treated as a match on read — see [IdempotencyStore.lookup].
     */
    val requestHash: String? = null,
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

    /**
     * Stores the response bound to [requestHash], so a later [lookup] with the same key but a
     * different request is refused instead of replayed. The default ignores the hash, which is
     * only correct for implementations that cannot persist one; the Redis store in libs-runtime overrides it.
     */
    suspend fun save(key: String, requestHash: String, statusCode: Int, responseBody: String, ttlSeconds: Long = 86400) =
        save(key, statusCode, responseBody, ttlSeconds)

    /**
     * Fingerprint-checked [get]: `null` when the key is unknown, the stored record when its
     * fingerprint equals [requestHash] (a genuine retry — replay it), and
     * [IdempotencyKeyReusedException] when the same key arrives with a different request.
     * A record stored without a fingerprint is treated as a match (backward compatibility).
     */
    suspend fun lookup(key: String, requestHash: String): IdempotencyRecord? =
        get(key)?.also { record ->
            val stored = record.requestHash
            if (stored != null && stored != requestHash) throw IdempotencyKeyReusedException(key)
        }
}
