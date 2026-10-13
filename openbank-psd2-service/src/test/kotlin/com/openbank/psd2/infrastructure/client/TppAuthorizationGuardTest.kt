// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.psd2.infrastructure.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.Response
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TppAuthorizationGuardTest {

    private val client = mockk<TppRegistryRestClient>()
    private val objectMapper = jacksonObjectMapper()
    private val guard = TppAuthorizationGuard(client)

    @Test
    fun `registry 403 with matching denied result becomes an authorization denial`() {
        val denied = TppAuthorizationResponse(
            tppId = "synthetic-unknown-tpp",
            authorized = false,
            roles = emptySet(),
            reason = "TPP not found in registry",
        )
        val inboundResponse = mockk<Response>()
        every { inboundResponse.status } returns 403
        every { inboundResponse.statusInfo } returns Response.Status.FORBIDDEN
        every { inboundResponse.readEntity(JsonNode::class.java) } returns
            objectMapper.readTree(objectMapper.writeValueAsString(denied))
        every { inboundResponse.close() } just Runs
        every { client.checkAuthorization(denied.tppId, "AISP") } throws WebApplicationException(inboundResponse)

        val result = guard.requireAuthorized(denied.tppId, "AISP")

        assertThat(result).isEqualTo(denied)
        assertThat(result.authorized).isFalse()
        verify(exactly = 1) { inboundResponse.close() }
    }

    @Test
    fun `only a matching unauthorized 403 body is translated`() {
        val tppId = "synthetic-unknown-tpp"
        val invalidResponses = listOf(
            401 to TppAuthorizationResponse(tppId, false, emptySet(), "identity rejected"),
            404 to TppAuthorizationResponse(tppId, false, emptySet(), "not found"),
            500 to TppAuthorizationResponse(tppId, false, emptySet(), "upstream failure"),
            403 to TppAuthorizationResponse(tppId, true, setOf("AISP"), null),
            403 to TppAuthorizationResponse("different-tpp", false, emptySet(), "not found"),
        )

        invalidResponses.forEach { (status, body) ->
            val responseClient = mockk<TppRegistryRestClient>()
            val inboundResponse = mockk<Response>()
            every { inboundResponse.status } returns status
            every { inboundResponse.statusInfo } returns Response.Status.fromStatusCode(status)
            if (status == 403) {
                every { inboundResponse.readEntity(JsonNode::class.java) } returns
                    objectMapper.readTree(objectMapper.writeValueAsString(body))
                every { inboundResponse.close() } just Runs
            }
            every { responseClient.checkAuthorization(tppId, "AISP") } throws
                WebApplicationException(inboundResponse)

            assertThatThrownBy { TppAuthorizationGuard(responseClient).requireAuthorized(tppId, "AISP") }
                .isInstanceOf(WebApplicationException::class.java)
            if (status == 403) verify(exactly = 1) { inboundResponse.close() }
        }

        val malformedClient = mockk<TppRegistryRestClient>()
        val malformedResponse = mockk<Response>()
        every { malformedResponse.status } returns 403
        every { malformedResponse.statusInfo } returns Response.Status.FORBIDDEN
        every { malformedResponse.readEntity(JsonNode::class.java) } throws
            jakarta.ws.rs.ProcessingException("invalid body")
        every { malformedResponse.close() } just Runs
        every { malformedClient.checkAuthorization(tppId, "AISP") } throws
            WebApplicationException(malformedResponse)
        assertThatThrownBy { TppAuthorizationGuard(malformedClient).requireAuthorized(tppId, "AISP") }
            .isInstanceOf(WebApplicationException::class.java)
        verify(exactly = 1) { malformedResponse.close() }
    }

    @Test
    fun `403 body must carry a JSON boolean false and complete denial shape`() {
        val tppId = "synthetic-unknown-tpp"
        val malformedBodies = listOf(
            """{"tppId":"$tppId","roles":[],"reason":"not registered"}""",
            """{"tppId":"$tppId","authorized":null,"roles":[],"reason":"not registered"}""",
            """{"tppId":"$tppId","authorized":"false","roles":[],"reason":"not registered"}""",
        )

        malformedBodies.forEach { body ->
            val responseClient = mockk<TppRegistryRestClient>()
            val inboundResponse = mockk<Response>()
            every { inboundResponse.status } returns 403
            every { inboundResponse.statusInfo } returns Response.Status.FORBIDDEN
            every { inboundResponse.readEntity(JsonNode::class.java) } returns
                objectMapper.readTree(body)
            every { inboundResponse.close() } just Runs
            every { responseClient.checkAuthorization(tppId, "AISP") } throws
                WebApplicationException(inboundResponse)

            assertThatThrownBy {
                TppAuthorizationGuard(responseClient).requireAuthorized(tppId, "AISP")
            }.isInstanceOf(WebApplicationException::class.java)
            verify(exactly = 1) { inboundResponse.close() }
        }
    }

    @Test
    fun `response close failure does not replace malformed 403 exception`() {
        val tppId = "synthetic-unknown-tpp"
        val inboundResponse = mockk<Response>()
        val closeFailure = jakarta.ws.rs.ProcessingException("synthetic close failure")
        val responseClient = mockk<TppRegistryRestClient>()

        every { inboundResponse.status } returns 403
        every { inboundResponse.statusInfo } returns Response.Status.FORBIDDEN
        every { inboundResponse.readEntity(JsonNode::class.java) } throws
            jakarta.ws.rs.ProcessingException("synthetic malformed body")
        every { inboundResponse.close() } throws closeFailure
        val original = WebApplicationException(inboundResponse)
        every { responseClient.checkAuthorization(tppId, "AISP") } throws original

        val thrown = org.assertj.core.api.Assertions.catchThrowable {
            TppAuthorizationGuard(responseClient).requireAuthorized(tppId, "AISP")
        }

        assertThat(thrown).isSameAs(original)
        assertThat(original.suppressed).containsExactly(closeFailure)
        verify(exactly = 1) { inboundResponse.close() }
    }
}
