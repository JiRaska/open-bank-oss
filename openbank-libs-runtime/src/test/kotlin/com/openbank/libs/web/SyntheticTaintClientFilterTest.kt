// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.web

import com.openbank.libs.synthetic.SyntheticTaint
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.core.MultivaluedHashMap
import org.assertj.core.api.Assertions.assertThat
import org.jboss.logging.MDC
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SyntheticTaintClientFilterTest {

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

    private fun outbound(): Pair<ClientRequestContext, MultivaluedHashMap<String, Any>> {
        val headers = MultivaluedHashMap<String, Any>()
        val ctx = mockk<ClientRequestContext>(relaxed = true) {
            every { getHeaders() } returns headers
        }
        return ctx to headers
    }

    @Test
    fun `a tainted request forwards the header to the next hop`() {
        MDC.put(MDC_SYNTHETIC, "true")
        val (ctx, headers) = outbound()

        SyntheticTaintClientFilter().filter(ctx)

        assertThat(headers.getFirst(SyntheticTaint.KAFKA_HEADER)).isEqualTo("true")
    }

    @Test
    fun `an untainted request sends no header at all`() {
        // Not "sends false": an absent header is the platform's REAL, and adding a negative header
        // to every outbound call in the fleet would be noise on a path that carries real money.
        val (ctx, headers) = outbound()

        SyntheticTaintClientFilter().filter(ctx)

        assertThat(headers.containsKey(SyntheticTaint.KAFKA_HEADER)).isFalse()
    }

    @Test
    fun `an MDC value that is not an exact true does not propagate`() {
        for (value in listOf("false", "1", "yes", "TRUE!", "")) {
            MDC.put(MDC_SYNTHETIC, value)
            val (ctx, headers) = outbound()

            SyntheticTaintClientFilter().filter(ctx)

            assertThat(headers.containsKey(SyntheticTaint.KAFKA_HEADER))
                .withFailMessage("MDC %s must not propagate a taint", value)
                .isFalse()
        }
    }

    @Test
    fun `the filter never strips a header the caller already set`() {
        // A canary-owned service account may make an outbound call that legitimately carries its
        // own taint this hop knows nothing about. Removing it would silently un-taint a flow.
        val (ctx, headers) = outbound()
        headers.putSingle(SyntheticTaint.KAFKA_HEADER, "true")

        SyntheticTaintClientFilter().filter(ctx)

        assertThat(headers.getFirst(SyntheticTaint.KAFKA_HEADER)).isEqualTo("true")
        verify(exactly = 0) { ctx.headers.remove(SyntheticTaint.KAFKA_HEADER) }
    }

    @Test
    fun `trusted OTel baggage forwards the header when reactive context has no MDC`() {
        val (ctx, headers) = outbound()
        val scope = Baggage.builder()
            .put(SyntheticTaint.BAGGAGE_KEY, "true")
            .build()
            .storeInContext(Context.current())
            .makeCurrent()
        try {
            SyntheticTaintClientFilter().filter(ctx)
        } finally {
            scope.close()
        }

        assertThat(headers.getFirst(SyntheticTaint.KAFKA_HEADER)).isEqualTo("true")
    }
}
