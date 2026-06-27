// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.flags

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class FlagdProviderTest {

    private fun providerReturning(status: Int, body: String): FlagdProvider {
        val http = mockk<HttpClient>()
        val response = mockk<HttpResponse<String>>()
        every { response.statusCode() } returns status
        every { response.body() } returns body
        every { http.send(any(), any<HttpResponse.BodyHandler<String>>()) } returns response
        return FlagdProvider(httpClient = http)
    }

    // --- parse() : pure, fail-static -------------------------------------------------

    @Test
    fun `parse reads a targeting-match boolean`() {
        val p = FlagdProvider()
        val eval = p.parse("f", false, """{"value":true,"variant":"on","reason":"TARGETING_MATCH"}""") {
            it.asBoolean()
        }
        assertThat(eval.value).isTrue()
        assertThat(eval.variant).isEqualTo("on")
        assertThat(eval.reason).isEqualTo(EvaluationReason.TARGETING_MATCH)
    }

    @Test
    fun `parse maps a SPLIT reason for A-B variants`() {
        val p = FlagdProvider()
        val eval = p.parse("f", "control", """{"value":"treatment","variant":"b","reason":"SPLIT"}""") { it.asText() }
        assertThat(eval.value).isEqualTo("treatment")
        assertThat(eval.reason).isEqualTo(EvaluationReason.SPLIT)
    }

    @Test
    fun `parse treats FLAG_NOT_FOUND as DEFAULT not error`() {
        val p = FlagdProvider()
        val eval = p.parse("f", true, """{"errorCode":"FLAG_NOT_FOUND"}""") { it.asBoolean() }
        assertThat(eval.value).isTrue()
        assertThat(eval.reason).isEqualTo(EvaluationReason.DEFAULT)
        assertThat(eval.errorCode).isNull()
    }

    @Test
    fun `parse surfaces a real error code while still returning the default`() {
        val p = FlagdProvider()
        val eval = p.parse("f", false, """{"errorCode":"PARSE_ERROR"}""") { it.asBoolean() }
        assertThat(eval.value).isFalse()
        assertThat(eval.reason).isEqualTo(EvaluationReason.ERROR)
        assertThat(eval.errorCode).isEqualTo("PARSE_ERROR")
    }

    @Test
    fun `parse falls back to default on a null value`() {
        val p = FlagdProvider()
        assertThat(p.parse("f", 42L, """{"value":null}""") { it.asLong() }.reason)
            .isEqualTo(EvaluationReason.DEFAULT)
    }

    @Test
    fun `parse is fail-static on malformed json`() {
        val p = FlagdProvider()
        val eval = p.parse("f", "d", "not json{") { it.asText() }
        assertThat(eval.value).isEqualTo("d")
        assertThat(eval.reason).isEqualTo(EvaluationReason.ERROR)
        assertThat(eval.errorCode).isEqualTo("MALFORMED_RESPONSE")
    }

    // --- evaluate() : HTTP round-trip via mock client --------------------------------

    @Test
    fun `evaluate returns the resolved value on 200`() {
        val p = providerReturning(200, """{"value":true,"variant":"on","reason":"STATIC"}""")
        assertThat(p.boolean("flag", default = false).value).isTrue()
    }

    @Test
    fun `evaluate maps 404 to DEFAULT`() {
        val p = providerReturning(404, "")
        val eval = p.string("flag", default = "fallback")
        assertThat(eval.value).isEqualTo("fallback")
        assertThat(eval.reason).isEqualTo(EvaluationReason.DEFAULT)
    }

    @Test
    fun `evaluate maps a 500 to ERROR with the status in the code`() {
        val p = providerReturning(500, "boom")
        val eval = p.integer("flag", default = 1)
        assertThat(eval.value).isEqualTo(1L)
        assertThat(eval.reason).isEqualTo(EvaluationReason.ERROR)
        assertThat(eval.errorCode).isEqualTo("HTTP_500")
    }

    @Test
    fun `evaluate is fail-static when the sidecar is unreachable`() {
        val http = mockk<HttpClient>()
        every { http.send(any(), any<HttpResponse.BodyHandler<String>>()) } throws IOException("connection refused")
        val p = FlagdProvider(httpClient = http)

        val eval = p.double("flag", default = 0.5)
        assertThat(eval.value).isEqualTo(0.5)
        assertThat(eval.reason).isEqualTo(EvaluationReason.ERROR)
        assertThat(eval.errorCode).isEqualTo("PROVIDER_UNREACHABLE")
    }

    @Test
    fun `evaluate addresses the flag key in the OFREP path and carries the context body`() {
        val http = mockk<HttpClient>()
        val response = mockk<HttpResponse<String>>()
        every { response.statusCode() } returns 200
        every { response.body() } returns """{"value":true,"reason":"TARGETING_MATCH"}"""
        val req = slot<HttpRequest>()
        every { http.send(capture(req), any<HttpResponse.BodyHandler<String>>()) } returns response

        // Spy the mapper to capture the serialized OFREP request body structure.
        val mapper = spyk(ObjectMapper())
        val bodyArg = slot<Any>()
        every { mapper.writeValueAsString(capture(bodyArg)) } answers { callOriginal() }

        FlagdProvider(mapper = mapper, httpClient = http)
            .boolean("flag", false, EvalContext("party-123", mapOf("channel" to "web")))

        assertThat(req.captured.uri().path).endsWith("/flag")
        @Suppress("UNCHECKED_CAST")
        val context = (bodyArg.captured as Map<String, Any?>)["context"] as Map<String, Any?>
        assertThat(context["targetingKey"]).isEqualTo("party-123")
        assertThat(context["channel"]).isEqualTo("web")
    }

    @Test
    fun `default base URL targets the flagd OFREP HTTP port 8016 not gRPC 8013`() {
        // Guards the silent-failure trap: OFREP is HTTP on 8016; 8013 is gRPC. A default
        // pointing at 8013 makes every eval fail-static. Verified via the request authority.
        val http = mockk<HttpClient>()
        val response = mockk<HttpResponse<String>>()
        every { response.statusCode() } returns 200
        every { response.body() } returns """{"value":true,"reason":"STATIC"}"""
        val req = slot<HttpRequest>()
        every { http.send(capture(req), any<HttpResponse.BodyHandler<String>>()) } returns response

        // No baseUrl arg → DEFAULT_BASE_URL.
        FlagdProvider(httpClient = http).boolean("flag", false)

        assertThat(req.captured.uri().authority).isEqualTo("localhost:8016")
    }
}
