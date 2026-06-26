// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.web

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.ext.Provider
import org.jboss.logging.MDC
import java.util.UUID

const val HEADER_CORRELATION_ID = "X-Correlation-ID"
const val HEADER_REQUEST_ID = "X-Request-ID"
const val MDC_CORRELATION_ID = "correlationId"
const val MDC_REQUEST_ID = "requestId"

@Provider
class CorrelationIdRequestFilter : ContainerRequestFilter {
    override fun filter(ctx: ContainerRequestContext) {
        val correlationId = ctx.getHeaderString(HEADER_CORRELATION_ID)
            ?: UUID.randomUUID().toString()
        val requestId = ctx.getHeaderString(HEADER_REQUEST_ID)
            ?: UUID.randomUUID().toString()

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
