// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Asserts what actually goes ON THE WIRE for trace correlation (ADR-0265 tail, #5671).
 *
 * Against a real in-JVM HTTP stub rather than a mocked client, because the property under test is a
 * serialization one: `metadata.trace_id` present with the caller's id, and the `metadata` key ABSENT
 * — not null, not a placeholder — whenever there is no valid trace. A mock of the client cannot
 * distinguish an omitted JSON key from a null one, and that distinction is the whole point.
 */
class LlmTraceCorrelationTest {

    private var server: HttpServer? = null
    private var lastRequestBody: String? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private fun startStub(): String {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/v1/chat/completions") { ex: HttpExchange ->
            lastRequestBody = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val bytes = OK_BODY.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(HTTP_OK, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        srv.start()
        server = srv
        return "http://127.0.0.1:${srv.address.port}/v1"
    }

    private fun callWith(traceIds: TraceIdProvider): String {
        val url = startStub()
        val client = OpenAiCompatibleLlmGatewayClient(
            baseUrl = url,
            model = "test-model",
            apiKey = "test-key",
            traceIds = traceIds,
        )
        runBlocking { client.chat("sys", "user") }
        return requireNotNull(lastRequestBody) { "the stub captured no request body" }
    }

    @Test
    fun `a valid caller trace id is sent to the gateway as metadata trace_id`() {
        val body = callWith { VALID_TRACE_ID }

        assertThat(body).contains("\"metadata\"")
        assertThat(body).contains("\"trace_id\":\"$VALID_TRACE_ID\"")
    }

    @Test
    fun `no trace context sends no metadata key at all`() {
        val body = callWith(TraceIdProvider.NONE)

        assertThat(body).doesNotContain("metadata")
        assertThat(body).doesNotContain("trace_id")
        // Still a well-formed request: correlation is additive, never a precondition for the call.
        assertThat(body).contains("\"model\":\"test-model\"")
    }

    @Test
    fun `the all-zero OpenTelemetry invalid trace id is never sent`() {
        // The case that motivates isValidTraceId: OTel returns this from an INVALID span context,
        // it is a well-formed 32-hex string, and sending it would fuse every untraced call in the
        // fleet onto one shared Langfuse trace — a correlation that exists, is wrong, and looks right.
        val body = callWith { "0".repeat(TRACE_ID_LENGTH) }

        assertThat(body).doesNotContain("metadata")
        assertThat(body).doesNotContain("0000000000")
    }

    @Test
    fun `a malformed trace id is rejected rather than forwarded`() {
        assertThat(callWith { "not-a-trace-id" }).doesNotContain("metadata")
        assertThat(callWith { VALID_TRACE_ID.uppercase() }).doesNotContain("metadata")
        assertThat(callWith { VALID_TRACE_ID.dropLast(1) }).doesNotContain("metadata")
    }

    @Test
    fun `a provider that throws cannot break the model call`() {
        // Correlation is best-effort. OtelTraceIdProvider already swallows everything, but a caller
        // may supply its own provider, and a failure to identify a trace must never cost a completion.
        val url = startStub()
        val client = OpenAiCompatibleLlmGatewayClient(
            baseUrl = url,
            model = "test-model",
            apiKey = "test-key",
            traceIds = { error("no trace backend") },
        )

        val answer = runBlocking { client.chat("sys", "user") }

        // The completion still comes back, uncorrelated. Written first and RED: the trace read sits
        // outside the request try/catch, so before the runCatching in chat() this propagated an
        // IllegalStateException out of a call that would otherwise have succeeded.
        assertThat(answer).isEqualTo("hi")
        assertThat(lastRequestBody).doesNotContain("metadata")
    }

    @Test
    fun `the OTel provider answers null when OpenTelemetry is not on the classpath`() {
        // libs-runtime does not depend on io.opentelemetry, so this test IS the absent-OTel case:
        // it proves the reflective read degrades to null instead of throwing NoClassDefFoundError
        // on the LLM path in the services that do not carry the extension.
        assertThat(OtelTraceIdProvider.currentTraceId()).isNull()
    }

    @Test
    fun `isValidTraceId accepts only a 32-hex non-zero lowercase id`() {
        assertThat(isValidTraceId(VALID_TRACE_ID)).isTrue()
        assertThat(isValidTraceId(null)).isFalse()
        assertThat(isValidTraceId("")).isFalse()
        assertThat(isValidTraceId("0".repeat(TRACE_ID_LENGTH))).isFalse()
        assertThat(isValidTraceId(VALID_TRACE_ID.uppercase())).isFalse()
        assertThat(isValidTraceId(VALID_TRACE_ID + "a")).isFalse()
        assertThat(isValidTraceId("g".repeat(TRACE_ID_LENGTH))).isFalse()
    }

    private companion object {
        const val TRACE_ID_LENGTH = 32
        const val VALID_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736"
        const val HTTP_OK = 200
        const val OK_BODY = """{"choices":[{"message":{"role":"assistant","content":"hi"}}]}"""
    }
}
