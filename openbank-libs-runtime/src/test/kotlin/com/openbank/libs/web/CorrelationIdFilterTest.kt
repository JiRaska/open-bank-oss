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
}
