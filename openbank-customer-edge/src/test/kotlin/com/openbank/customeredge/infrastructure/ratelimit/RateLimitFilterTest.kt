// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.infrastructure.ratelimit

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.container.ContainerRequestContext
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Test

class RateLimitFilterTest {

    private fun filterWith(rateLimiter: RateLimiter, jwt: JsonWebToken, limit: Int = 100) = RateLimitFilter().apply {
        this.rateLimiter = rateLimiter
        this.jwt = jwt
        this.limitPerMinute = limit
    }

    @Test
    fun `allows the request and never touches the context when under the limit`() {
        val jwt = mockk<JsonWebToken> {
            every { getClaim<String>("party_id") } returns "party-1"
        }
        val rateLimiter = mockk<RateLimiter> { every { isAllowed("party-1", 100) } returns true }
        val ctx = mockk<ContainerRequestContext>(relaxed = true)

        filterWith(rateLimiter, jwt).filter(ctx)

        verify(exactly = 0) { ctx.abortWith(any()) }
    }

    @Test
    fun `aborts with 429 and rate-limit headers once the party exceeds the limit`() {
        val jwt = mockk<JsonWebToken> {
            every { getClaim<String>("party_id") } returns "party-1"
        }
        val rateLimiter = mockk<RateLimiter> { every { isAllowed("party-1", 5) } returns false }
        val ctx = mockk<ContainerRequestContext>(relaxed = true)
        val responseSlot = mutableListOf<jakarta.ws.rs.core.Response>()
        every { ctx.abortWith(any()) } answers { responseSlot.add(firstArg()) }

        filterWith(rateLimiter, jwt, limit = 5).filter(ctx)

        assertThat(responseSlot).hasSize(1)
        val response = responseSlot.first()
        assertThat(response.status).isEqualTo(429)
        assertThat(response.getHeaderString("X-RateLimit-Limit")).isEqualTo("5")
        assertThat(response.getHeaderString("Retry-After")).isEqualTo("60")
    }

    @Test
    fun `falls back to the JWT subject when party_id claim is absent`() {
        val jwt = mockk<JsonWebToken> {
            every { getClaim<String>("party_id") } returns null
            every { subject } returns "sub-42"
        }
        val rateLimiter = mockk<RateLimiter> { every { isAllowed("sub-42", 100) } returns true }
        val ctx = mockk<ContainerRequestContext>(relaxed = true)

        filterWith(rateLimiter, jwt).filter(ctx)

        verify { rateLimiter.isAllowed("sub-42", 100) }
    }

    @Test
    fun `skips rate limiting entirely for anonymous requests (no party_id, no subject)`() {
        val jwt = mockk<JsonWebToken> {
            every { getClaim<String>("party_id") } returns null
            every { subject } returns null
        }
        val rateLimiter = mockk<RateLimiter>()
        val ctx = mockk<ContainerRequestContext>(relaxed = true)

        filterWith(rateLimiter, jwt).filter(ctx)

        verify(exactly = 0) { rateLimiter.isAllowed(any(), any()) }
        verify(exactly = 0) { ctx.abortWith(any()) }
    }

    @Test
    fun `treats a blank party_id claim as absent and falls back to subject`() {
        val jwt = mockk<JsonWebToken> {
            every { getClaim<String>("party_id") } returns "   "
            every { subject } returns "sub-77"
        }
        val rateLimiter = mockk<RateLimiter> { every { isAllowed("sub-77", 100) } returns true }
        val ctx = mockk<ContainerRequestContext>(relaxed = true)

        filterWith(rateLimiter, jwt).filter(ctx)

        verify { rateLimiter.isAllowed("sub-77", 100) }
    }

    @Test
    fun `skips rate limiting when reading the JWT claim throws (malformed token)`() {
        val jwt = mockk<JsonWebToken> {
            every { getClaim<String>("party_id") } throws IllegalStateException("no active request")
        }
        val rateLimiter = mockk<RateLimiter>()
        val ctx = mockk<ContainerRequestContext>(relaxed = true)

        filterWith(rateLimiter, jwt).filter(ctx)

        verify(exactly = 0) { rateLimiter.isAllowed(any(), any()) }
        verify(exactly = 0) { ctx.abortWith(any()) }
    }
}
