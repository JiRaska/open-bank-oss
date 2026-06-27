// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.web

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.container.ContainerResponseFilter
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

@Provider
class RateLimitFilter(
    @ConfigProperty(name = "openbank.rate-limit.max-concurrent-requests", defaultValue = "200")
    private val maxConcurrent: Int,

    @ConfigProperty(name = "openbank.rate-limit.enabled", defaultValue = "true")
    private val enabled: Boolean,
) : ContainerRequestFilter,
    ContainerResponseFilter {

    private val semaphore by lazy { Semaphore(maxConcurrent, true) }
    private val activeRequests = AtomicInteger(0)

    override fun filter(ctx: ContainerRequestContext) {
        if (!enabled) return
        if (ctx.uriInfo.path.startsWith("/q/")) return

        if (!semaphore.tryAcquire()) {
            ctx.abortWith(
                Response.status(429)
                    .header("Retry-After", "1")
                    .header("X-RateLimit-Limit", maxConcurrent)
                    .header("X-RateLimit-Remaining", 0)
                    .entity(
                        mapOf(
                            "error" to "TOO_MANY_REQUESTS",
                            "message" to "Server is busy, please retry after 1 second",
                        ),
                    )
                    .build(),
            )
            return
        }
        activeRequests.incrementAndGet()
        ctx.setProperty("rate-limit-acquired", true)
    }

    override fun filter(req: ContainerRequestContext, resp: ContainerResponseContext) {
        if (req.getProperty("rate-limit-acquired") == true) {
            semaphore.release()
            val remaining = semaphore.availablePermits()
            activeRequests.decrementAndGet()
            resp.headers.putSingle("X-RateLimit-Limit", maxConcurrent)
            resp.headers.putSingle("X-RateLimit-Remaining", remaining)
        }
    }
}
