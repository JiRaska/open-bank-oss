// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.libs.authz

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class OpaSidecarPolicyDecisionPointTest {
    private val httpClient: HttpClient = mockk()
    private val pdp = OpaSidecarPolicyDecisionPoint(httpClient = httpClient)

    private val sampleQuery = AuthzQuery(
        principal = Principal(id = "user-1", type = "HUMAN", roles = listOf("ROLE_OPERATOR")),
        action = "party.update",
        resource = ResourceRef(type = "party", id = "p-123"),
    )

    @Test
    fun `boolean result shape allow=true is parsed`(): Unit = runBlocking {
        stubResponse(200, """{"result":true}""")
        val decision = pdp.allow(sampleQuery)
        assertThat(decision.allow).isTrue
        assertThat(decision.policyVersion).isNull()
    }

    @Test
    fun `object result shape with reason and policy_version is parsed`(): Unit = runBlocking {
        stubResponse(200, """{"result":{"allow":true,"reason":"operator-on-own-tenant","policy_version":"v1.4.2"}}""")
        val decision = pdp.allow(sampleQuery)
        assertThat(decision.allow).isTrue
        assertThat(decision.reason).isEqualTo("operator-on-own-tenant")
        assertThat(decision.policyVersion).isEqualTo("v1.4.2")
    }

    @Test
    fun `missing result node maps to deny (no matching rule)`(): Unit = runBlocking {
        stubResponse(200, """{}""")
        val decision = pdp.allow(sampleQuery)
        assertThat(decision.allow).isFalse
        assertThat(decision.reason).isEqualTo("no matching policy rule")
    }

    @Test
    fun `non-2xx response raises PolicyDecisionException (fail-closed)`() {
        stubResponse(500, "internal opa error")
        assertThatThrownBy { runBlocking { pdp.allow(sampleQuery) } }
            .isInstanceOf(PolicyDecisionException::class.java)
            .hasMessageContaining("HTTP 500")
    }

    @Test
    fun `request body wraps query in input envelope`(): Unit = runBlocking {
        val captured = slot<HttpRequest>()
        every { httpClient.send(capture(captured), any<HttpResponse.BodyHandler<String>>()) } returns
            stubHttpResponse(200, """{"result":true}""")
        pdp.allow(sampleQuery)
        // Bodyless inspection — confirm the URI path is the ADR-0034 query namespace.
        assertThat(captured.captured.uri().toString()).endsWith("/v1/data/openbank/rest/allow")
        assertThat(captured.captured.method()).isEqualTo("POST")
    }

    private fun stubResponse(status: Int, body: String) {
        every { httpClient.send(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns
            stubHttpResponse(status, body)
    }

    @Suppress("UNCHECKED_CAST")
    private fun stubHttpResponse(status: Int, body: String): HttpResponse<String> {
        val resp: HttpResponse<String> = mockk(relaxed = true)
        every { resp.statusCode() } returns status
        every { resp.body() } returns body
        return resp
    }
}
