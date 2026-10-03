// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.idempotency

import java.security.MessageDigest

/**
 * Who an `Idempotency-Key` belongs to. A key is only ever meaningful for the caller that sent it
 * and the service that received it, so every stored record is namespaced by both: the same key
 * from another principal, or the same key reaching another service over a shared Redis, is a
 * different key.
 *
 * [principal] is the authenticated identity's name (for a machine caller, its client id). It is
 * never written to storage verbatim — [storeKey] uses its SHA-256.
 */
class IdempotencyScope(val service: String, principal: String) {
    private val principalDigest: String

    init {
        require(SERVICE_PATTERN.matches(service)) { "idempotency scope service must match $SERVICE_PATTERN" }
        require(principal.isNotBlank()) { "an idempotent request requires an authenticated principal" }
        principalDigest = sha256(principal)
    }

    /**
     * The namespaced storage key for the caller-supplied [key]; validates [key] first
     * ([IdempotencyKeys.requireValid], an [IllegalArgumentException] → 400).
     */
    fun storeKey(key: String): String {
        IdempotencyKeys.requireValid(key)
        return "$VERSION:$service:$principalDigest:$key"
    }

    override fun equals(other: Any?): Boolean =
        other is IdempotencyScope && other.service == service && other.principalDigest == principalDigest

    override fun hashCode(): Int = 31 * service.hashCode() + principalDigest.hashCode()

    /** Never prints the principal. */
    override fun toString(): String = "IdempotencyScope(service=$service)"

    companion object {
        const val VERSION = "v2"
        private val SERVICE_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,62}")

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** Shape rules for a caller-supplied `Idempotency-Key`. */
object IdempotencyKeys {
    const val MAX_LENGTH = 128
    private val ALLOWED = Regex("[A-Za-z0-9._:-]+")

    /** Throws [IllegalArgumentException] (→ 400) unless [key] is 1..[MAX_LENGTH] chars of `[A-Za-z0-9._:-]`. */
    fun requireValid(key: String) {
        require(key.length in 1..MAX_LENGTH) { "Idempotency-Key must be 1 to $MAX_LENGTH characters" }
        require(ALLOWED.matches(key)) { "Idempotency-Key may contain only letters, digits and . _ : -" }
    }
}

/**
 * A stored idempotency record could not be decoded (for example a non-numeric status). It is never
 * replayed as a fabricated response: the request fails through the generic 500 `INTERNAL_ERROR`.
 */
class IdempotencyRecordCorruptException(message: String) : RuntimeException(message)
