// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.idempotency

import java.security.MessageDigest

/**
 * The request an `Idempotency-Key` is bound to: lowercase hex SHA-256 of
 * `METHOD\npath\ncanonicalBody`. Without it, the same key with a different payload replays
 * the first response — a second, different payment is silently answered as the first one.
 *
 * Canonicalising the body (e.g. sorted JSON keys) is the caller's job; this stays framework-free
 * (ADR-0122) and hashes exactly the string it is given. The method is upper-cased so `post` and
 * `POST` fingerprint the same; the path is taken verbatim.
 */
object RequestFingerprint {
    fun of(method: String, path: String, canonicalBody: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${method.uppercase()}\n$path\n${canonicalBody.orEmpty()}".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * The same `Idempotency-Key` was reused for a different request. libs-runtime maps it to
 * **409 IDEMPOTENCY_KEY_REUSED** (the fleet convention). Deliberately not an
 * [IllegalStateException] so no generic mapper can claim it with a different code.
 *
 * The message never carries the key: stores often key on a composite internal namespace
 * (e.g. `party:operation:key`), and echoing client input into logs invites log injection.
 */
class IdempotencyKeyReusedException : RuntimeException("Idempotency-Key was already used for a different request")

/**
 * The same request is still being processed under this `Idempotency-Key` (an in-flight marker
 * from [IdempotencyStore.reserve]). libs-runtime maps it to **409 IDEMPOTENCY_REQUEST_IN_PROGRESS**
 * — a distinct code from reuse, because here the client should simply retry later.
 */
class IdempotencyRequestInProgressException :
    RuntimeException("A request with this Idempotency-Key is still being processed")
