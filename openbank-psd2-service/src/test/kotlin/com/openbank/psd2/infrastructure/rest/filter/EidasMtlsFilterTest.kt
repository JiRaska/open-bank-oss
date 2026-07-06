// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.infrastructure.rest.filter

import com.openbank.psd2.infrastructure.client.TppAuthorizationGuard
import com.openbank.psd2.infrastructure.client.TppAuthorizationResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException
import org.junit.jupiter.api.Test

/**
 * eIDAS QWAC transport-auth gate (ADR-0090 P1): gates the bespoke `open-banking/` surface (except
 * the open sandbox) and the Berlin `v1/` surface, resolves PISP vs AISP from the `/payments`
 * path segment, and fails closed on a downstream registry outage (circuit-open or any exception).
 */
class EidasMtlsFilterTest {

    private val guard = mockk<TppAuthorizationGuard>()
    private val filter = EidasMtlsFilter(guard)

    private fun ctxFor(path: String, tppIdHeader: String? = "tpp-1", sslDn: String? = null): ContainerRequestContext {
        val ctx = mockk<ContainerRequestContext>(relaxed = true)
        val uriInfo = mockk<UriInfo>()
        every { uriInfo.path } returns path
        every { ctx.uriInfo } returns uriInfo
        every { ctx.getHeaderString("X-TPP-ID") } returns tppIdHeader
        every { ctx.getHeaderString("SSL-CLIENT-S-DN") } returns sslDn
        return ctx
    }

    @Test
    fun `ungated paths (sandbox) are not intercepted`() {
        val ctx = ctxFor("open-banking/sandbox/ping", tppIdHeader = null)

        filter.filter(ctx)

        verify(exactly = 0) { ctx.abortWith(any()) }
        verify(exactly = 0) { ctx.setProperty(any(), any()) }
    }

    @Test
    fun `missing TPP identification aborts with 401 CERTIFICATE_MISSING`() {
        val ctx = ctxFor("v1/accounts", tppIdHeader = null, sslDn = null)
        val captured = slot<Response>()
        every { ctx.abortWith(capture(captured)) } returns Unit

        filter.filter(ctx)

        assertThat(captured.captured.status).isEqualTo(401)
    }

    @Test
    fun `falls back to SSL-CLIENT-S-DN when X-TPP-ID header absent`() {
        every { guard.requireAuthorized("cn=tpp-cert", "AISP") } returns
            TppAuthorizationResponse("cn=tpp-cert", true, setOf("AISP"), null)
        val ctx = ctxFor("v1/accounts", tppIdHeader = null, sslDn = "cn=tpp-cert")

        filter.filter(ctx)

        verify(exactly = 1) { ctx.setProperty("tppId", "cn=tpp-cert") }
    }

    @Test
    fun `payments path requires the PISP role`() {
        every { guard.requireAuthorized("tpp-1", "PISP") } returns
            TppAuthorizationResponse("tpp-1", true, setOf("PISP"), null)
        val ctx = ctxFor("v1/payments/sepa-credit-transfers")

        filter.filter(ctx)

        verify(exactly = 1) { guard.requireAuthorized("tpp-1", "PISP") }
        verify(exactly = 1) { ctx.setProperty("tppId", "tpp-1") }
    }

    @Test
    fun `non-payments path requires the AISP role`() {
        every { guard.requireAuthorized("tpp-1", "AISP") } returns
            TppAuthorizationResponse("tpp-1", true, setOf("AISP"), null)
        val ctx = ctxFor("v1/accounts")

        filter.filter(ctx)

        verify(exactly = 1) { guard.requireAuthorized("tpp-1", "AISP") }
    }

    @Test
    fun `unauthorized TPP aborts with 401 CERTIFICATE_INVALID`() {
        every { guard.requireAuthorized("tpp-1", "AISP") } returns
            TppAuthorizationResponse("tpp-1", false, emptySet(), "role revoked")
        val ctx = ctxFor("v1/accounts")
        val captured = slot<Response>()
        every { ctx.abortWith(capture(captured)) } returns Unit

        filter.filter(ctx)

        assertThat(captured.captured.status).isEqualTo(401)
        verify(exactly = 0) { ctx.setProperty("tppId", any()) }
    }

    @Test
    fun `circuit-open on the TPP registry aborts with 503`() {
        every { guard.requireAuthorized("tpp-1", "AISP") } throws CircuitBreakerOpenException("open")
        val ctx = ctxFor("v1/accounts")
        val captured = slot<Response>()
        every { ctx.abortWith(capture(captured)) } returns Unit

        filter.filter(ctx)

        assertThat(captured.captured.status).isEqualTo(503)
    }

    @Test
    fun `an unexpected exception from the registry aborts with 503`() {
        every { guard.requireAuthorized("tpp-1", "AISP") } throws RuntimeException("boom")
        val ctx = ctxFor("open-banking/accounts")
        val captured = slot<Response>()
        every { ctx.abortWith(capture(captured)) } returns Unit

        filter.filter(ctx)

        assertThat(captured.captured.status).isEqualTo(503)
    }
}
