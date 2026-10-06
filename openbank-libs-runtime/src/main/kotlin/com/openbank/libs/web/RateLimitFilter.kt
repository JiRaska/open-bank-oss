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
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveContainerRequestContext
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
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
        val lease = PermitLease()
        ctx.setProperty("rate-limit-acquired", lease)
        if (ctx is ResteasyReactiveContainerRequestContext) {
            val server = ctx.serverRequestContext
            server.registerCompletionCallback { lease.release() }
            server.serverResponse().addCloseHandler { lease.release() }
        }
    }

    override fun filter(req: ContainerRequestContext, resp: ContainerResponseContext) {
        val lease = req.getProperty("rate-limit-acquired") as? PermitLease
        if (lease != null) {
            lease.release()
            val remaining = semaphore.availablePermits()
            resp.headers.putSingle("X-RateLimit-Limit", maxConcurrent)
            resp.headers.putSingle("X-RateLimit-Remaining", remaining)
        }
    }

    private inner class PermitLease {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) {
                semaphore.release()
                activeRequests.decrementAndGet()
            }
        }
    }
}
