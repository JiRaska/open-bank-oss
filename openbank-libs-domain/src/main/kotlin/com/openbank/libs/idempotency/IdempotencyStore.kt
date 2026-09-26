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

    /**
     * Legacy, fingerprint-less write. Kept for callers not yet migrated; it cannot detect a key
     * reused for a different request. New code uses [reserve] + the fingerprinted [save].
     */
    suspend fun save(key: String, statusCode: Int, responseBody: String, ttlSeconds: Long = 86400)

    /**
     * Stores the final response bound to [requestHash]. Deliberately abstract: a default that
     * dropped the hash would silently turn reuse detection off for any implementation that forgot
     * to override it.
     *
     * Never overwrites a record or in-flight marker holding a DIFFERENT fingerprint — that throws
     * [IdempotencyKeyReusedException] and leaves the stored value untouched. Overwriting a marker
     * or record with the same fingerprint is allowed (it completes a [reserve]).
     */
    suspend fun save(key: String, requestHash: String, statusCode: Int, responseBody: String, ttlSeconds: Long = 86400)

    /**
     * Atomic claim of [key] for the request fingerprinted as [requestHash] — closes the
     * lookup-then-save race in which two concurrent first requests both miss and both execute.
     *
     * - [ReserveResult.Reserved]: the key was free; an in-flight marker holding [requestHash] now
     *   occupies it for [inFlightTtlSeconds]. Do the work, then [save] (or [release] on failure).
     * - [ReserveResult.Replay]: a completed response for the same fingerprint (or a legacy record
     *   without one) exists — return it, do not execute.
     * - [ReserveResult.Mismatch]: the key holds a record or marker for a DIFFERENT request.
     * - [ReserveResult.InFlight]: the same request is being processed right now by another caller.
     *
     * [inFlightTtlSeconds] bounds how long a crashed holder can block the key.
     */
    suspend fun reserve(
        key: String,
        requestHash: String,
        inFlightTtlSeconds: Long = DEFAULT_IN_FLIGHT_TTL_SECONDS,
    ): ReserveResult

    /**
     * Drops the in-flight marker placed by [reserve] when the work failed, so a retry can run.
     * Removes ONLY a marker holding [requestHash]; a completed record or another request's
     * marker is left alone.
     */
    suspend fun release(key: String, requestHash: String)

    /**
     * Fingerprint-checked [get] (non-atomic; prefer [reserve]): `null` when the key is unknown,
     * the stored record when its fingerprint equals [requestHash], and
     * [IdempotencyKeyReusedException] when the same key arrives with a different request.
     * A record stored without a fingerprint is treated as a match (backward compatibility).
     */
    suspend fun lookup(key: String, requestHash: String): IdempotencyRecord? = get(key)?.also { record ->
        val stored = record.requestHash
        if (stored != null && stored != requestHash) throw IdempotencyKeyReusedException()
    }

    companion object {
        /** Default lifetime of an in-flight marker: long enough for a slow request, short enough to self-heal. */
        const val DEFAULT_IN_FLIGHT_TTL_SECONDS: Long = 300
    }
}

/** Outcome of [IdempotencyStore.reserve]. */
sealed interface ReserveResult {
    /** Key claimed for this request; execute it. */
    data object Reserved : ReserveResult

    /** Same request already completed; return [record] verbatim. */
    data class Replay(val record: IdempotencyRecord) : ReserveResult

    /** Key already bound to a different request → 409 IDEMPOTENCY_KEY_REUSED. */
    data object Mismatch : ReserveResult

    /** Same request still executing elsewhere → 409 IDEMPOTENCY_REQUEST_IN_PROGRESS. */
    data object InFlight : ReserveResult
}
