// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.resilience

import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import jakarta.ws.rs.client.ClientResponseContext
import jakarta.ws.rs.client.ClientResponseFilter

/**
 * ADR-0321 D2 steps 1 and 2 for money-sync REST clients: marks each outgoing request keyed or
 * unkeyed by its `Idempotency-Key` header, and on a 5xx throws [RetryableKeyedCallException] for a
 * keyed call and [UpstreamCallException] otherwise. A 4xx and every 2xx/3xx pass through untouched,
 * so a 4xx keeps the client's default `WebApplicationException` mapping and is never retried.
 *
 * Register it per client with `@RegisterProvider(KeyedCallFilter::class)`. Deliberately NOT a
 * `@Provider` and not a CDI bean: a shared-library `@Provider` is registered in every consumer
 * (#6240, gate `provider-type-classpath`), and this one must only apply to clients that opted in.
 *
 * Connect-refused and timeouts produce no response, so this filter never sees them — that is step 3,
 * [KeyedCall.invoke]. Depending on the client, an exception thrown here can reach the caller wrapped
 * in a `ProcessingException`; [KeyedCall.invoke] unwraps it.
 */
class KeyedCallFilter :
    ClientRequestFilter,
    ClientResponseFilter {

    override fun filter(requestContext: ClientRequestContext) {
        val key = requestContext.headers.getFirst(IDEMPOTENCY_KEY_HEADER)?.toString()
        requestContext.setProperty(KEYED_PROPERTY, !key.isNullOrBlank())
    }

    override fun filter(requestContext: ClientRequestContext, responseContext: ClientResponseContext) {
        val status = responseContext.status
        if (status < SERVER_ERROR_MIN || status > SERVER_ERROR_MAX) return
        val message = "${requestContext.method} ${requestContext.uri.path} answered $status"
        if (requestContext.getProperty(KEYED_PROPERTY) == true) {
            throw RetryableKeyedCallException(message)
        }
        throw UpstreamCallException(message)
    }

    companion object {
        const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
        const val KEYED_PROPERTY = "openbank.keyed"
        private const val SERVER_ERROR_MIN = 500
        private const val SERVER_ERROR_MAX = 599
    }
}
