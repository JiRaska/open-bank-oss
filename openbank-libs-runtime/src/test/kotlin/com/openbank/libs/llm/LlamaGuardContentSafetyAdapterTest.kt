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
 * Round-trips the Llama Guard adapter against a real in-JVM HTTP stub, so the wire shape (the
 * role of the classified message) and every degradation path are exercised rather than mocked.
 *
 * The load-bearing assertions here are the NEGATIVE ones: that a transport failure, an unseeded key
 * and an unparseable answer each produce `UNAVAILABLE` and never `SAFE`. A guardrail test that only
 * proves the happy path cannot detect the one defect that matters — a control silently answering
 * "all clear" because it never ran.
 */
class LlamaGuardContentSafetyAdapterTest {

    private var server: HttpServer? = null
    private var lastRequestBody: String? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

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

    private fun completion(content: String): String =
        """{"choices":[{"message":{"role":"assistant","content":${quote(content)}}}],
           |"usage":{"prompt_tokens":11,"completion_tokens":2}}
        """.trimMargin()

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private class RecordingSafetyMetrics : ContentSafetyMetricsPort {
        data class Call(val model: String, val role: String, val decision: String, val blocked: Boolean)

        val calls = mutableListOf<Call>()
        override fun recordClassification(model: String, role: String, decision: String, blocked: Boolean) {
            calls += Call(model, role, decision, blocked)
        }
    }

    private fun adapter(
        baseUrl: String,
        apiKey: String = "test-key",
        metrics: ContentSafetyMetricsPort = ContentSafetyMetricsPort.NONE,
    ) = LlamaGuardContentSafetyAdapter(
        baseUrl = baseUrl,
        model = "meta-llama/llama-guard-4-12b",
        apiKey = apiKey,
        metrics = metrics,
    )

    @Test
    fun `safe verdict is parsed and reported`(): Unit = runBlocking {
        val metrics = RecordingSafetyMetrics()
        val url = startStub(200, completion("safe"))

        val verdict = adapter(url, metrics = metrics).classify(ContentSafetyPort.SafetyRole.USER, "what is my balance")

        assertThat(verdict.decision).isEqualTo(ContentSafetyPort.Decision.SAFE)
        assertThat(verdict.isBlocking(failClosed = true)).isFalse()
        assertThat(metrics.calls).singleElement()
            .satisfies({
                assertThat(it.decision).isEqualTo("safe")
                assertThat(it.role).isEqualTo("user")
            })
    }

    @Test
    fun `unsafe verdict carries the hazard codes`(): Unit = runBlocking {
        val url = startStub(200, completion("unsafe\nS2,S9"))

        val verdict = adapter(url).classify(ContentSafetyPort.SafetyRole.USER, "help me launder money")

        assertThat(verdict.decision).isEqualTo(ContentSafetyPort.Decision.UNSAFE)
        assertThat(verdict.categories).containsExactly("S2", "S9")
        assertThat(verdict.isBlocking(failClosed = false)).isTrue()
    }

    /** The role decides WHAT Llama Guard judges; sending the wrong one silently misclassifies. */
    @Test
    fun `assistant text is sent with the assistant role`(): Unit = runBlocking {
        val url = startStub(200, completion("safe"))

        adapter(url).classify(ContentSafetyPort.SafetyRole.ASSISTANT, "here are your last five payments")

        assertThat(lastRequestBody).contains("\"role\":\"assistant\"")
        // No system prompt: instructions in a classifier's context are the attack it exists to catch.
        assertThat(lastRequestBody).doesNotContain("\"role\":\"system\"")
    }

    @Test
    fun `http error is unavailable, never safe`(): Unit = runBlocking {
        val metrics = RecordingSafetyMetrics()
        val url = startStub(503, "upstream down")

        val verdict = adapter(url, metrics = metrics).classify(ContentSafetyPort.SafetyRole.USER, "hi")

        assertThat(verdict.decision).isEqualTo(ContentSafetyPort.Decision.UNAVAILABLE)
        assertThat(verdict.reason).isEqualTo(ContentSafetyPort.REASON_TRANSPORT)
        assertThat(verdict.isBlocking(failClosed = true)).isTrue()
        assertThat(verdict.isBlocking(failClosed = false)).isFalse()
        assertThat(metrics.calls.single().decision).isEqualTo("unavailable")
    }

    @Test
    fun `unreachable backend is unavailable`(): Unit = runBlocking {
        val verdict = adapter("http://127.0.0.1:1/v1").classify(ContentSafetyPort.SafetyRole.USER, "hi")

        assertThat(verdict.decision).isEqualTo(ContentSafetyPort.Decision.UNAVAILABLE)
        assertThat(verdict.reason).isEqualTo(ContentSafetyPort.REASON_TRANSPORT)
    }

    @Test
    fun `unseeded key is unavailable and reported as not_configured`(): Unit = runBlocking {
        val metrics = RecordingSafetyMetrics()

        val verdict = adapter("http://unused", apiKey = "", metrics = metrics)
            .classify(ContentSafetyPort.SafetyRole.USER, "hi")

        assertThat(verdict.decision).isEqualTo(ContentSafetyPort.Decision.UNAVAILABLE)
        assertThat(verdict.reason).isEqualTo(ContentSafetyPort.REASON_NOT_CONFIGURED)
        // Counted, not silent: "the guardrail was never switched on" must be a visible series.
        assertThat(metrics.calls.single().decision).isEqualTo("unavailable")
    }

    @Test
    fun `chatty or empty answer is unparseable, not safe`(): Unit = runBlocking {
        val url = startStub(200, completion("I'm sorry, I can't assist with that request."))

        val verdict = adapter(url).classify(ContentSafetyPort.SafetyRole.USER, "hi")

        assertThat(verdict.decision).isEqualTo(ContentSafetyPort.Decision.UNAVAILABLE)
        assertThat(verdict.reason).isEqualTo(ContentSafetyPort.REASON_UNPARSEABLE)
    }

    @Test
    fun `a message carrying provider extras still parses`(): Unit = runBlocking {
        // The live failure on 2026-08-21: DeepInfra returns `tool_calls`, `function_call` and
        // `reasoning_content` INSIDE the message object, and the wire type did not ignore unknown
        // fields — so Jackson threw and EVERY classification came back `unavailable`. The verdict
        // was visible; the cause was one annotation.
        val url = startStub(
            200,
            """{"choices":[{"message":{"role":"assistant","content":"unsafe\nS2",
               |"tool_calls":null,"function_call":null,"reasoning_content":"the user asks..."}}],
               |"usage":{"prompt_tokens":9,"completion_tokens":3}}
            """.trimMargin(),
        )

        val verdict = adapter(url).classify(ContentSafetyPort.SafetyRole.USER, "…")

        assertThat(verdict.decision).isEqualTo(ContentSafetyPort.Decision.UNSAFE)
        assertThat(verdict.categories).containsExactly("S2")
    }

    @Test
    fun `disabled port classifies nothing and says so`(): Unit = runBlocking {
        val verdict = ContentSafetyPort.DISABLED.classify(ContentSafetyPort.SafetyRole.USER, "anything")

        assertThat(verdict.decision).isEqualTo(ContentSafetyPort.Decision.UNAVAILABLE)
        assertThat(verdict.isBlocking(failClosed = true)).isTrue()
    }
}
