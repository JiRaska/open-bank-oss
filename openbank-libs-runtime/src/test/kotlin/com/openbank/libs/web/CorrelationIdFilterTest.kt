// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.web

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import org.assertj.core.api.Assertions.assertThat
import org.jboss.logging.MDC
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class CorrelationIdFilterTest {

    @AfterEach
    fun clearMdc() {
        MDC.remove(MDC_CORRELATION_ID)
        MDC.remove(MDC_REQUEST_ID)
    }

    private fun requestWith(correlationId: String?, requestId: String?): ContainerRequestContext =
        mockk(relaxed = true) {
            every { getHeaderString(HEADER_CORRELATION_ID) } returns correlationId
            every { getHeaderString(HEADER_REQUEST_ID) } returns requestId
        }

    @Test
    fun `propagates inbound correlation and request ids into properties and MDC`() {
        val req = requestWith(correlationId = "corr-123", requestId = "req-456")

        CorrelationIdRequestFilter().filter(req)

        verify { req.setProperty(ApiVersionResponseFilter.CORRELATION_ID_KEY, "corr-123") }
        verify { req.setProperty(MDC_REQUEST_ID, "req-456") }
        assertThat(MDC.get(MDC_CORRELATION_ID)).isEqualTo("corr-123")
        assertThat(MDC.get(MDC_REQUEST_ID)).isEqualTo("req-456")
    }

    @Test
    fun `generates fresh ids when the client sent none`() {
        val req = requestWith(correlationId = null, requestId = null)

        CorrelationIdRequestFilter().filter(req)

        val generatedCorrelationId = MDC.get(MDC_CORRELATION_ID) as String
        val generatedRequestId = MDC.get(MDC_REQUEST_ID) as String
        assertThat(generatedCorrelationId).isNotBlank()
        assertThat(generatedRequestId).isNotBlank()
        assertThat(generatedCorrelationId).isNotEqualTo(generatedRequestId)
    }

    @Test
    fun `response filter clears the MDC so it never leaks across requests`() {
        MDC.put(MDC_CORRELATION_ID, "leftover-corr")
        MDC.put(MDC_REQUEST_ID, "leftover-req")
        val req = mockk<ContainerRequestContext>(relaxed = true)
        val resp = mockk<ContainerResponseContext>(relaxed = true)

        CorrelationIdResponseFilter().filter(req, resp)

        assertThat(MDC.get(MDC_CORRELATION_ID)).isNull()
        assertThat(MDC.get(MDC_REQUEST_ID)).isNull()
    }

    @Test
    fun `an id of exactly the maximum length is kept and one character more is replaced`() {
        val atLimit = "a".repeat(MAX_INBOUND_ID_LENGTH)
        val overLimit = "a".repeat(MAX_INBOUND_ID_LENGTH + 1)
        assertThat(MAX_INBOUND_ID_LENGTH).isEqualTo(64)

        CorrelationIdRequestFilter().filter(requestWith(correlationId = atLimit, requestId = overLimit))

        assertThat(MDC.get(MDC_CORRELATION_ID)).isEqualTo(atLimit)
        assertThat(MDC.get(MDC_REQUEST_ID) as String).isNotEqualTo(overLimit).matches(UUID_SHAPE)
    }

    @ParameterizedTest
    @MethodSource("acceptedIds")
    fun `an id with the accepted shape is kept verbatim`(id: String) {
        val req = requestWith(correlationId = id, requestId = id)

        CorrelationIdRequestFilter().filter(req)

        assertThat(MDC.get(MDC_CORRELATION_ID)).isEqualTo(id)
        assertThat(MDC.get(MDC_REQUEST_ID)).isEqualTo(id)
        verify { req.setProperty(ApiVersionResponseFilter.CORRELATION_ID_KEY, id) }
    }

    @ParameterizedTest
    @MethodSource("rejectedIds")
    fun `an id outside the accepted shape is replaced by a fresh one and the request proceeds`(id: String) {
        val req = requestWith(correlationId = id, requestId = id)

        CorrelationIdRequestFilter().filter(req)

        val correlationId = MDC.get(MDC_CORRELATION_ID) as String
        val requestId = MDC.get(MDC_REQUEST_ID) as String
        assertThat(correlationId).isNotEqualTo(id).matches(UUID_SHAPE)
        assertThat(requestId).isNotEqualTo(id).matches(UUID_SHAPE)
        // The value other code echoes (error bodies, the response header) is the replaced one.
        verify { req.setProperty(ApiVersionResponseFilter.CORRELATION_ID_KEY, correlationId) }
        verify { req.setProperty(MDC_REQUEST_ID, requestId) }
        verify(exactly = 0) { req.abortWith(any()) }
    }

    companion object {
        private const val UUID_SHAPE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

        @JvmStatic
        fun acceptedIds(): List<String> = listOf(
            "a",
            "corr-123",
            "0af7651916cd43dd8448eb211c80319c",
            "3f2b8c1e-9d4a-4b6f-8a57-0c1d2e3f4a5b",
            "01J9ZQ4M8N.batch_7-retry",
        )

        @JvmStatic
        fun rejectedIds(): List<String> = listOf(
            "",
            " ",
            "has space",
            "quote\"inside",
            "back\\slash",
            "line\nbreak",
            "trailing-line-break\n",
            "carriage\rreturn",
            "tab\tseparated",
            "separator\u2028inside",
            "brace{inside}",
            "comma,separated",
            "colon:separated",
            "non-ascii-\u00e9",
            "nul\u0000byte",
        )
    }
}
