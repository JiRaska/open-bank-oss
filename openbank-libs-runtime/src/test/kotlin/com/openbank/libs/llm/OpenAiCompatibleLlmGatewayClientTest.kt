// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.libs.llm

import com.openbank.libs.llm.LlmCallMetricsPort
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * Round-trips the shared LLM gateway client against a real in-JVM HTTP stub (com.sun HttpServer),
 * not a mock — so the actual OpenAI-compatible request/response wire format and the degradation
 * paths are exercised end to end.
 */
class OpenAiCompatibleLlmGatewayClientTest {

    private var server: HttpServer? = null
    private var lastRequestBody: String? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    /** Start a stub that replies with [status] and [responseBody], capturing the request body. */
    private fun startStub(status: Int, responseBody: String): String {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/v1/chat/completions") { ex: HttpExchange ->
            lastRequestBody = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        srv.start()
        server = srv
        return "http://127.0.0.1:${srv.address.port}/v1"
    }

    private fun client(
        baseUrl: String,
        apiKey: String = "test-key",
        metrics: LlmCallMetricsPort = LlmCallMetricsPort.NONE,
    ) = OpenAiCompatibleLlmGatewayClient(
        baseUrl = baseUrl,
        model = "test-model",
        apiKey = apiKey,
        metrics = metrics,
    )

    /** Captures what the client reports, so the assertions can be about the recorded call. */
    private class RecordingMetrics : LlmCallMetricsPort {
        data class Call(
            val model: String,
            val outcome: String,
            val promptTokens: Int,
            val completionTokens: Int,
            val durationNanos: Long,
            val provider: String,
        )

        val calls = mutableListOf<Call>()

        override fun recordCall(
            model: String,
            outcome: String,
            promptTokens: Int,
            completionTokens: Int,
            durationNanos: Long,
            provider: String,
        ) {
            calls += Call(model, outcome, promptTokens, completionTokens, durationNanos, provider)
        }
    }

    @Test
    fun `a successful completion returns the assistant content and sends the OpenAI wire shape`() {
        val base = startStub(
            200,
            """{"choices":[{"message":{"role":"assistant","content":"root cause: chatty S3 sync"}}]}""",
        )

        val answer = runBlocking { client(base).chat("you are an SRE", "diagnose this") }

        assertThat(answer).isEqualTo("root cause: chatty S3 sync")
        // The request body must carry the model + both messages in the OpenAI schema.
        assertThat(lastRequestBody).contains("\"model\":\"test-model\"")
        assertThat(lastRequestBody).contains("\"role\":\"system\"")
        assertThat(lastRequestBody).contains("\"role\":\"user\"")
        assertThat(lastRequestBody).contains("\"max_tokens\"")
    }

    @Test
    fun `a blank api key degrades to null without any HTTP call`() {
        // No stub started — if the client tried to connect it would fail; a blank key must short out.
        val answer = runBlocking { client("http://127.0.0.1:1/v1", apiKey = "").chat("s", "u") }
        assertThat(answer).isNull()
    }

    @Test
    fun `a non-2xx response degrades to null`() {
        val base = startStub(500, "upstream boom")
        val answer = runBlocking { client(base).chat("s", "u") }
        assertThat(answer).isNull()
    }

    @Test
    fun `an empty choices array degrades to null`() {
        val base = startStub(200, """{"choices":[]}""")
        val answer = runBlocking { client(base).chat("s", "u") }
        assertThat(answer).isNull()
    }

    @Test
    fun `a blank completion content degrades to null`() {
        val base = startStub(200, """{"choices":[{"message":{"role":"assistant","content":"   "}}]}""")
        val answer = runBlocking { client(base).chat("s", "u") }
        assertThat(answer).isNull()
    }

    // ── Token accounting (ADR-0112 / ADR-0174) ───────────────────────────────
    //
    // The `usage` block was parsed away by @JsonIgnoreProperties before this, which is the whole
    // reason the fleet's token consumption existed only on the provider's invoice. These tests are
    // about that specific regression: it is silent, and a completion still comes back either way.

    @Test
    fun `usage is parsed and reported as prompt and completion tokens`() {
        val base = startStub(
            200,
            """{"choices":[{"message":{"role":"assistant","content":"ok"}}],""" +
                """"usage":{"prompt_tokens":321,"completion_tokens":64,"total_tokens":385}}""",
        )
        val metrics = RecordingMetrics()

        val answer = runBlocking { client(base, metrics = metrics).chat("s", "u") }

        assertThat(answer).isEqualTo("ok")
        assertThat(metrics.calls).hasSize(1)
        val call = metrics.calls.single()
        assertThat(call.outcome).isEqualTo(LlmCallMetricsPort.OUTCOME_SUCCESS)
        assertThat(call.model).isEqualTo("test-model")
        assertThat(call.promptTokens).isEqualTo(321)
        assertThat(call.completionTokens).isEqualTo(64)
        assertThat(call.durationNanos).isGreaterThan(0)
    }

    @Test
    fun `a provider that omits usage still yields the completion, with zero tokens`() {
        // Nullable on purpose: no provider PROMISES `usage`, and dropping the completion because
        // telemetry was missing would trade a working feature for a metric.
        val base = startStub(200, """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""")
        val metrics = RecordingMetrics()

        val answer = runBlocking { client(base, metrics = metrics).chat("s", "u") }

        assertThat(answer).isEqualTo("ok")
        assertThat(metrics.calls.single().promptTokens).isEqualTo(0)
        assertThat(metrics.calls.single().completionTokens).isEqualTo(0)
    }

    @Test
    fun `a blank api key reports not_configured rather than staying silent`() {
        val metrics = RecordingMetrics()

        val answer = runBlocking { client("http://127.0.0.1:1/v1", "", metrics).chat("s", "u") }

        assertThat(answer).isNull()
        // An agent that has never had a key looks identical to an idle one without this series,
        // and "the AI features were never switched on" is a state this repo keeps finding late.
        assertThat(metrics.calls.single().outcome)
            .isEqualTo(LlmCallMetricsPort.OUTCOME_NOT_CONFIGURED)
    }

    @Test
    fun `the recorded provider comes from the configured endpoint, not from a second knob`() {
        // No network: the blank-key path records before sending, so this asserts the derivation
        // against the literal endpoint string gitops sets (#5736) without a live gateway.
        val metrics = RecordingMetrics()

        runBlocking { client("http://litellm.ai-platform.svc:4000/v1", "", metrics).chat("s", "u") }

        assertThat(metrics.calls.single().provider).isEqualTo(LlmCallMetricsPort.PROVIDER_LITELLM)
    }

    @Test
    fun `a non-2xx response is reported as http_error and still timed`() {
        val base = startStub(503, """{"error":"upstream unavailable"}""")
        val metrics = RecordingMetrics()

        assertThat(runBlocking { client(base, metrics = metrics).chat("s", "u") }).isNull()
        val call = metrics.calls.single()
        assertThat(call.outcome).isEqualTo(LlmCallMetricsPort.OUTCOME_HTTP_ERROR)
        assertThat(call.durationNanos).isGreaterThan(0)
        // An unrecognised endpoint must not invent a series of its own (the stub is on 127.0.0.1).
        assertThat(call.provider).isEqualTo(LlmCallMetricsPort.PROVIDER_OTHER)
    }
}
