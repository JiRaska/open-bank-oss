// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.web

import com.openbank.libs.domain.identifiers.Ids
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.MDC

const val HEADER_CORRELATION_ID = "X-Correlation-ID"
const val HEADER_REQUEST_ID = "X-Request-ID"
const val MDC_CORRELATION_ID = "correlationId"
const val MDC_REQUEST_ID = "requestId"

/** Longest correlation/request id accepted from a caller; anything longer is replaced. */
const val MAX_INBOUND_ID_LENGTH = 64

/**
 * The only shape a caller-supplied correlation/request id may have: ASCII letters, digits, `.`,
 * `_` and `-`, between 1 and [MAX_INBOUND_ID_LENGTH] characters. It covers every id the platform
 * itself generates (UUIDs, ULIDs, W3C trace ids) and nothing that needs escaping in a log line,
 * an HTTP header or a JSON body.
 */
private val INBOUND_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,$MAX_INBOUND_ID_LENGTH}")

/**
 * Returns [raw] when it has the accepted id shape, or `null` otherwise.
 *
 * Uses a whole-input match on purpose: a `find` with `^…$` anchors accepts a value that ends in a
 * line terminator, because `$` matches just before one.
 */
fun acceptedInboundId(raw: String?): String? = raw?.takeIf { INBOUND_ID_PATTERN.matches(it) }

/**
 * The id this service uses for a caller-supplied header value: the value itself when it has the
 * accepted shape, a fresh UUID when it is absent or malformed. A malformed id never fails the
 * request — correlation is a convenience for the caller, not an input worth a 400.
 */
fun inboundIdOrFresh(raw: String?): String = acceptedInboundId(raw) ?: Ids.randomId().toString()

@Provider
class CorrelationIdRequestFilter : ContainerRequestFilter {
    override fun filter(ctx: ContainerRequestContext) {
        val correlationId = inboundIdOrFresh(ctx.getHeaderString(HEADER_CORRELATION_ID))
        val requestId = inboundIdOrFresh(ctx.getHeaderString(HEADER_REQUEST_ID))

        ctx.setProperty(ApiVersionResponseFilter.CORRELATION_ID_KEY, correlationId)
        ctx.setProperty(MDC_REQUEST_ID, requestId)

        MDC.put(MDC_CORRELATION_ID, correlationId)
        MDC.put(MDC_REQUEST_ID, requestId)
    }
}

@Provider
class CorrelationIdResponseFilter : ContainerResponseFilter {
    override fun filter(req: ContainerRequestContext, resp: ContainerResponseContext) {
        MDC.remove(MDC_CORRELATION_ID)
        MDC.remove(MDC_REQUEST_ID)
    }
}
