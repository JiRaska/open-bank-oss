// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.ratelimit

import com.openbank.vop.infrastructure.observability.VopMetricsAdapter
import com.openbank.vop.infrastructure.ratelimit.VopRateLimitFilter
import com.openbank.vop.infrastructure.ratelimit.VopRateLimiter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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

    // The REAL metrics adapter over a SimpleMeterRegistry, not a mock port: the outcome assertions
    // below then fail if the filter stops reporting a decision.
    private val registry = SimpleMeterRegistry()

    private fun filter(enabled: Boolean = true, limit: Int = 60) = VopRateLimitFilter().apply {
        rateLimiter = limiter
        this.identity = this@VopRateLimitFilterTest.identity
        metrics = VopMetricsAdapter(registry)
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

    @Test
    fun `a throttled requester and an unreachable store are counted as DIFFERENT outcomes`() {
        // Both answer 429 and are indistinguishable on the wire, but they mean opposite things:
        // `throttled` is one caller enumerating, `store_unavailable` is every caller being rejected
        // because Valkey is down. Collapsing them into one counter would hide a fleet-wide outage
        // inside what looks like healthy enumeration defence.
        principal("enumerator")
        every { limiter.isAllowed("enumerator", 60) } returns true andThen false andThenThrows
            RuntimeException("valkey unreachable")
        val f = filter()

        f.filter(context())
        f.filter(context())
        f.filter(context())

        assertThat(decisions("allowed")).isEqualTo(1.0)
        assertThat(decisions("throttled")).isEqualTo(1.0)
        assertThat(decisions("store_unavailable")).isEqualTo(1.0)
    }

    @Test
    fun `a request that never reaches the limiter records no decision`() {
        // An unauthenticated or management request consumes no window slot, so it must not show up
        // as `allowed` either — that would dilute the very ratio the limit is tuned from.
        principal(null)

        filter().filter(context())
        filter().filter(context(path = "/q/health/ready"))

        assertThat(registry.find("openbank.vop.rate_limit.decisions").counters()).isEmpty()
    }

    private fun decisions(outcome: String): Double = registry.get("openbank.vop.rate_limit.decisions")
        .tag("service", "vop")
        .tag("outcome", outcome)
        .counter().count()
}
