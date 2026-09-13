// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.web

import com.openbank.libs.synthetic.SyntheticTaint
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import jakarta.ws.rs.core.SecurityContext
import org.assertj.core.api.Assertions.assertThat
import org.jboss.logging.MDC
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.Principal
import java.util.Optional

/**
 * ADR-0252 phase 1. The assertions that matter here are the REFUSALS: the taint excludes activity
 * from regulatory aggregates and the AML baseline, so a header any caller could set would be a
 * self-service evasion primitive.
 */
class SyntheticTaintFilterTest {

    private lateinit var testContextScope: Scope

    @BeforeEach
    fun isolateOtelContext() {
        testContextScope = Context.root().makeCurrent()
    }

    @AfterEach
    fun clearMdc() {
        MDC.remove(MDC_SYNTHETIC)
        testContextScope.close()
    }

    private fun request(header: String?, principal: String?): ContainerRequestContext {
        val security = mockk<SecurityContext>(relaxed = true) {
            every { userPrincipal } returns principal?.let { name ->
                mockk<Principal> { every { getName() } returns name }
            }
        }
        return mockk(relaxed = true) {
            every { getHeaderString(SyntheticTaint.KAFKA_HEADER) } returns header
            every { securityContext } returns security
        }
    }

    private fun filterWith(trusted: String?) = SyntheticTaintRequestFilter().apply {
        trustedPrincipals = Optional.ofNullable(trusted)
    }

    @Test
    fun `a trusted principal asserting the header taints the request`() {
        val req = request(header = "true", principal = "service-account-openbank-canary")

        filterWith("service-account-openbank-canary").filter(req)

        verify { req.setProperty(SYNTHETIC_TAINT_PROPERTY, true) }
        assertThat(MDC.get(MDC_SYNTHETIC)).isEqualTo("true")
    }

    @Test
    fun `a trusted assertion binds baggage for this request and the response closes it`() {
        val scope = slot<Scope>()
        val req = request(header = "true", principal = "service-account-openbank-canary")
        every { req.setProperty("openbank.synthetic.baggage-scope", capture(scope)) } answers { }

        filterWith("service-account-openbank-canary").filter(req)

        assertThat(Baggage.current().getEntryValue(SyntheticTaint.BAGGAGE_KEY)).isEqualTo("true")
        every { req.getProperty("openbank.synthetic.baggage-scope") } returns scope.captured
        SyntheticTaintResponseFilter().filter(req, mockk<ContainerResponseContext>(relaxed = true))
        assertThat(Baggage.current().getEntryValue(SyntheticTaint.BAGGAGE_KEY)).isNull()
    }

    @Test
    fun `an UNTRUSTED principal cannot taint, however it asks`() {
        // The evasion case. Believing this header would let a customer drop their own payments out
        // of AML scoring and the regulatory returns, using the mechanism built to make the
        // platform more honest.
        val req = request(header = "true", principal = "some-real-customer")

        filterWith("service-account-openbank-canary").filter(req)

        verify { req.setProperty(SYNTHETIC_TAINT_PROPERTY, false) }
        verify(exactly = 0) { req.setProperty("openbank.synthetic.baggage-scope", any()) }
        assertThat(MDC.get(MDC_SYNTHETIC)).isNull()
    }

    @Test
    fun `an anonymous caller cannot taint`() {
        val req = request(header = "true", principal = null)

        filterWith("service-account-openbank-canary").filter(req)

        verify { req.setProperty(SYNTHETIC_TAINT_PROPERTY, false) }
        verify(exactly = 0) { req.setProperty("openbank.synthetic.baggage-scope", any()) }
    }

    @Test
    fun `the default configuration trusts nobody, so shipping this filter changes nothing`() {
        // Not a formality: an empty-means-everyone default would turn a monitoring feature into a
        // fleet-wide hole the moment it merged, on every service that depends on libs-runtime.
        val req = request(header = "true", principal = "service-account-openbank-canary")

        SyntheticTaintRequestFilter().filter(req)

        verify { req.setProperty(SYNTHETIC_TAINT_PROPERTY, false) }
    }

    @Test
    fun `a blank or whitespace-only trusted list trusts nobody either`() {
        val req = request(header = "true", principal = "service-account-openbank-canary")

        filterWith("  , ,  ").filter(req)

        verify { req.setProperty(SYNTHETIC_TAINT_PROPERTY, false) }
    }

    @Test
    fun `the trusted list is a list, and whitespace around a name does not break it`() {
        val req = request(header = "true", principal = "canary-b")

        filterWith("canary-a, canary-b , canary-c").filter(req)

        verify { req.setProperty(SYNTHETIC_TAINT_PROPERTY, true) }
    }

    @Test
    fun `no header means real, and does not consult the principal at all`() {
        val req = request(header = null, principal = "service-account-openbank-canary")

        filterWith("service-account-openbank-canary").filter(req)

        verify { req.setProperty(SYNTHETIC_TAINT_PROPERTY, false) }
        assertThat(MDC.get(MDC_SYNTHETIC)).isNull()
    }

    @Test
    fun `a value that is not an exact true is real even from a trusted principal`() {
        for (value in listOf("1", "yes", "TRUE!", "false", "")) {
            val req = request(header = value, principal = "canary")
            filterWith("canary").filter(req)
            verify { req.setProperty(SYNTHETIC_TAINT_PROPERTY, false) }
        }
    }

    @Test
    fun `the response filter clears the MDC so the next request on this thread is not marked`() {
        // Worker threads are pooled. A leaked MDC entry would put a real customer's log lines on
        // the wrong side of the taint, and nothing downstream would ever question it.
        MDC.put(MDC_SYNTHETIC, "true")

        SyntheticTaintResponseFilter().filter(
            mockk<ContainerRequestContext>(relaxed = true),
            mockk<ContainerResponseContext>(relaxed = true),
        )

        assertThat(MDC.get(MDC_SYNTHETIC)).isNull()
    }
}
