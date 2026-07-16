// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.ratelimit

import com.openbank.vop.infrastructure.ratelimit.VopRateLimitFilter
import com.openbank.vop.infrastructure.ratelimit.VopRateLimiter
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.Principal

class VopRateLimitFilterTest {

    private val limiter = mockk<VopRateLimiter>()
    private val identity = mockk<SecurityIdentity>()

    private fun filter(enabled: Boolean = true, limit: Int = 60) = VopRateLimitFilter().apply {
        rateLimiter = limiter
        this.identity = this@VopRateLimitFilterTest.identity
        limitPerMinute = limit
        this.enabled = enabled
    }

    private fun context(path: String = "api/v1/vop/verify"): ContainerRequestContext {
        val uriInfo = mockk<UriInfo> { every { this@mockk.path } returns path }
        return mockk(relaxed = true) { every { this@mockk.uriInfo } returns uriInfo }
    }

    private fun principal(name: String?) {
        every { identity.principal } returns name?.let { n -> mockk<Principal> { every { this@mockk.name } returns n } }
    }

    @Test
    fun `a requester under the limit passes through untouched`() {
        principal("operator-jana")
        every { limiter.isAllowed("operator-jana", 60) } returns true
        val ctx = context()

        filter().filter(ctx)

        verify(exactly = 0) { ctx.abortWith(any()) }
    }

    @Test
    fun `a requester over the limit gets 429 with backoff headers`() {
        principal("enumerator")
        every { limiter.isAllowed("enumerator", 60) } returns false
        val ctx = context()
        val response = slot<Response>()

        filter().filter(ctx)

        verify { ctx.abortWith(capture(response)) }
        assertThat(response.captured.status).isEqualTo(429)
        assertThat(response.captured.getHeaderString("Retry-After")).isEqualTo("60")
        assertThat(response.captured.getHeaderString("X-RateLimit-Limit")).isEqualTo("60")
    }

    @Test
    fun `an unreachable rate-limit store fails CLOSED`() {
        // The enumeration control must not disappear when Valkey does. This does NOT contradict
        // VoP failing open (ADR-0171 §3): a 429 makes the caller render no_data, so the payment
        // still flows with a warning. Failing open here would trade a real security hole for no
        // payment-availability gain. If someone "fixes" this to fail open for consistency with
        // the service's own fail-open rule, this test is what should stop them.
        principal("someone")
        every { limiter.isAllowed(any(), any()) } throws RuntimeException("valkey unreachable")
        val ctx = context()
        val response = slot<Response>()

        filter().filter(ctx)

        verify { ctx.abortWith(capture(response)) }
        assertThat(response.captured.status).isEqualTo(429)
    }

    @Test
    fun `an unauthenticated request is left to the security layer, not masked as a 429`() {
        principal(null)
        val ctx = context()

        filter().filter(ctx)

        verify(exactly = 0) { ctx.abortWith(any()) }
        // Never consume a window slot for a request that has no identity to attribute it to.
        verify(exactly = 0) { limiter.isAllowed(any(), any()) }
    }

    @Test
    fun `management endpoints are not rate limited`() {
        val ctx = context(path = "/q/health/ready")

        filter().filter(ctx)

        verify(exactly = 0) { limiter.isAllowed(any(), any()) }
    }

    @Test
    fun `the limit can be disabled for local development`() {
        val ctx = context()

        filter(enabled = false).filter(ctx)

        verify(exactly = 0) { limiter.isAllowed(any(), any()) }
        verify(exactly = 0) { ctx.abortWith(any()) }
    }
}
