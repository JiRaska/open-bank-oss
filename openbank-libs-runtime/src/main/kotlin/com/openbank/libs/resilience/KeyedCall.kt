// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.resilience

import jakarta.ws.rs.ProcessingException

/**
 * ADR-0321 D2 step 3: classifies the failures [KeyedCallFilter] cannot see. Wrap the client call
 * inside the money-sync `@Retry` method:
 *
 * ```kotlin
 * KeyedCall.invoke(keyed = idempotencyKey != null) { client.post(idempotencyKey, body) }
 * ```
 *
 * The caller built the header, so it knows whether the call is keyed. A `ProcessingException`
 * (connect refused, socket/read timeout) becomes [RetryableKeyedCallException] when keyed and
 * [UpstreamCallException] when not. A `ProcessingException` that merely wraps one of those two
 * (a [KeyedCallFilter] 5xx decision surfaced through the client) is unwrapped, never reclassified.
 * Everything else — notably a 4xx `WebApplicationException` — propagates unchanged.
 */
object KeyedCall {
    inline fun <T> invoke(keyed: Boolean, call: () -> T): T = try {
        call()
    } catch (e: ProcessingException) {
        throw classify(keyed, e)
    }

    fun classify(keyed: Boolean, e: ProcessingException): RuntimeException {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is RetryableKeyedCallException || cause is UpstreamCallException) {
                return cause as RuntimeException
            }
            cause = cause.cause
        }
        val message = "upstream call failed before a response: ${e.message}"
        return if (keyed) RetryableKeyedCallException(message, e) else UpstreamCallException(message, e)
    }
}
