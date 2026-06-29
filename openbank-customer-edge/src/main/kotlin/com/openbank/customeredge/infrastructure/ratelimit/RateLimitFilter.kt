// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.ratelimit

import io.quarkus.logging.Log
import io.smallrye.common.annotation.Blocking
import jakarta.inject.Inject
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken

/**
 * JAX-RS filter that enforces a per-party-identity request rate limit (ADR-0132).
 *
 * The identity is the `party_id` claim from the customer JWT (or `sub` as fallback).
 * Anonymous requests (no valid JWT, e.g. POST /onboarding/start) are skipped —
 * per-IP throttling at ingress-nginx is the first line of defence for unauthenticated traffic.
 *
 * A 429 response includes `X-RateLimit-Limit` and `Retry-After` headers so the mobile
 * client can back off gracefully without hammering the endpoint.
 *
 * @Blocking is required because [RateLimiter] calls the blocking Valkey client.
 */
@Provider
@Blocking
class RateLimitFilter : ContainerRequestFilter {

    @Inject
    lateinit var rateLimiter: RateLimiter

    @Inject
    lateinit var jwt: JsonWebToken

    @ConfigProperty(name = "openbank.rate-limit.per-party.requests-per-minute", defaultValue = DEFAULT_LIMIT_STR)
    var limitPerMinute: Int = DEFAULT_LIMIT

    override fun filter(ctx: ContainerRequestContext) {
        val partyId = resolvePartyId() ?: return
        if (!rateLimiter.isAllowed(partyId, limitPerMinute)) {
            Log.debugf("Rate limit exceeded for party=%s limit=%d/min", partyId, limitPerMinute)
            ctx.abortWith(
                Response.status(HTTP_TOO_MANY_REQUESTS)
                    .header("X-RateLimit-Limit", limitPerMinute)
                    .header("X-RateLimit-Window", "60s")
                    .header("Retry-After", RETRY_AFTER_SECONDS)
                    .entity(
                        mapOf(
                            "code" to "RATE_LIMIT_EXCEEDED",
                            "message" to "Too many requests. Limit: $limitPerMinute/min.",
                        ),
                    )
                    .build(),
            )
        }
    }

    private fun resolvePartyId(): String? = try {
        jwt.getClaim<String>("party_id")?.takeIf { it.isNotBlank() }
            ?: jwt.subject?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val DEFAULT_LIMIT = 100
        const val DEFAULT_LIMIT_STR = "100"
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val RETRY_AFTER_SECONDS = 60
    }
}
