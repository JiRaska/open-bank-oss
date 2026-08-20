// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.copilot.infrastructure.model

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.copilot.domain.model.ChatMessage
import com.openbank.copilot.domain.model.ChatRole
import com.openbank.copilot.domain.model.ModelDescriptor
import com.openbank.copilot.domain.model.ModelRequest
import com.openbank.libs.llm.LlmCallMetricsPort
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.http.HttpClient

/**
 * `completeStream` recorded NO metric on any outcome (#5878), so the four cases below were each
 * completely invisible: `openbank_llm_requests_total` counted only the non-streaming path.
 *
 * Everything is driven through a stubbed [HttpClient] rather than a live call — every LLM call in
 * this environment currently fails on credentials (#5736), so a test that hoped for a real stream
 * could not assert a success outcome at all. The stub replaces the transport only: the request is
 * still built, the status still checked, and the real SSE read loop still parses the bytes.
 *
 * Each test asserts the metric that was RECORDED — outcome, provider and token semantics — not that
 * a call ran without throwing. Falsified by deleting each `recordStream` emission in turn and
 * confirming the matching test goes red.
 */
class OpenAiCompatibleModelProviderStreamMetricsTest {

    private data class Recorded(
        val model: String,
        val outcome: String,
        val promptTokens: Int,
        val completionTokens: Int,
        val durationNanos: Long,
        val provider: String,
    )

    private val recorded = mutableListOf<Recorded>()

    // The key is read live from MicroProfile Config on every call, so a system property is enough
    // and no Quarkus boot is needed. Cleared again so the not_configured case can remove it.
    @BeforeEach
    fun seedKey() {
        System.setProperty(KEY_PROPERTY, "test-key")
    }

    @AfterEach
    fun clearKey() {
        System.clearProperty(KEY_PROPERTY)
    }

    private val probe = object : LlmCallMetricsPort {
        override fun recordCall(
            model: String,
            outcome: String,
            promptTokens: Int,
            completionTokens: Int,
            durationNanos: Long,
            provider: String,
        ) {
            recorded += Recorded(model, outcome, promptTokens, completionTokens, durationNanos, provider)
        }
    }

    private fun provider(client: HttpClient?): OpenAiCompatibleModelProvider = OpenAiCompatibleModelProvider().also {
        it.objectMapper = ObjectMapper().registerKotlinModule()
        it.metrics = probe
        it.httpOverride = client
    }

    private val descriptor = ModelDescriptor(
        id = "deepseek-ai/DeepSeek-V3.2",
        provider = "openai-compat",
        endpoint = "http://litellm.ai-platform.svc:4000/v1",
    )

    private val request = ModelRequest(
        model = "deepseek-ai/DeepSeek-V3.2",
        messages = listOf(ChatMessage(role = ChatRole.USER, content = "jaký je můj zůstatek?")),
    )

    private fun sse(vararg lines: String): InputStream = ByteArrayInputStream(lines.joinToString("\n").toByteArray())

    private suspend fun stream(client: HttpClient): Result<Unit> =
        runCatching { provider(client).completeStream(descriptor, request) {} }.map { }

    @Test
    fun `a stream that completes records success with the accumulated token counts`(): Unit = runBlocking {
        val client = StubHttpClient(
            200,
            sse(
                """data: {"model":"deepseek-ai/DeepSeek-V3.2","choices":[{"delta":{"content":"Zůstatek"}}]}""",
                """data: {"choices":[{"delta":{"content":" je 1000 CZK."},"finish_reason":"stop"}],""" +
                    """"usage":{"prompt_tokens":31,"completion_tokens":7}}""",
                "data: [DONE]",
            ),
        )
        val chunks = mutableListOf<String>()
        val response = provider(client).completeStream(descriptor, request) { chunks += it }

        assertThat(chunks).containsExactly("Zůstatek", " je 1000 CZK.")
        assertThat(response.content).isEqualTo("Zůstatek je 1000 CZK.")
        assertThat(recorded).singleElement().satisfies({
            assertThat(it.outcome).isEqualTo(LlmCallMetricsPort.OUTCOME_SUCCESS)
            assertThat(it.model).isEqualTo("deepseek-ai/DeepSeek-V3.2")
            assertThat(it.provider).isEqualTo(LlmCallMetricsPort.PROVIDER_LITELLM)
            // Accumulated across chunks — the usage object arrives only in the last one.
            assertThat(it.promptTokens).isEqualTo(31)
            assertThat(it.completionTokens).isEqualTo(7)
            assertThat(it.durationNanos).isPositive()
        })
    }

    @Test
    fun `a stream that reports no usage records unknown tokens, never zero`(): Unit = runBlocking {
        // The common case: many OpenAI-compatible backends send no usage chunk when streaming.
        val client = StubHttpClient(
            200,
            sse("""data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "data: [DONE]"),
        )
        provider(client).completeStream(descriptor, request) {}

        assertThat(recorded).singleElement().satisfies({
            assertThat(it.outcome).isEqualTo(LlmCallMetricsPort.OUTCOME_SUCCESS)
            // The distinction the whole issue turns on: 0 would be indistinguishable from a real
            // zero and would silently understate openbank:llm_cost_usd_24h.
            assertThat(it.promptTokens).isEqualTo(LlmCallMetricsPort.TOKENS_UNKNOWN)
            assertThat(it.completionTokens).isEqualTo(LlmCallMetricsPort.TOKENS_UNKNOWN)
            assertThat(it.promptTokens).isNotZero()
        })
    }

    @Test
    fun `a stream that fails after the 200 records stream_aborted, not success or http_error`(): Unit = runBlocking {
        // A body that dies mid-flight: a real reset, idle timeout or truncated SSE surfaces exactly
        // this way, AFTER a 200 and after tokens have already reached the user.
        val body = object : InputStream() {
            private val head = """data: {"choices":[{"delta":{"content":"Zůst"}}]}""".toByteArray()
            private var i = 0
            override fun read(): Int {
                if (i >= head.size) throw IOException("connection reset by peer mid-stream")
                return head[i++].toInt()
            }
        }
        val delivered = mutableListOf<String>()
        val ex = runCatching {
            provider(StubHttpClient(200, body)).completeStream(descriptor, request) { delivered += it }
        }.exceptionOrNull()

        assertThat(ex).isInstanceOf(IOException::class.java)
        assertThat(recorded).singleElement().satisfies({
            assertThat(it.outcome).isEqualTo(LlmCallMetricsPort.OUTCOME_STREAM_ABORTED)
            assertThat(it.outcome).isNotEqualTo(LlmCallMetricsPort.OUTCOME_SUCCESS)
            assertThat(it.outcome).isNotEqualTo(LlmCallMetricsPort.OUTCOME_HTTP_ERROR)
            assertThat(it.provider).isEqualTo(LlmCallMetricsPort.PROVIDER_LITELLM)
            // A partial count of an unfinished generation is not the call's cost.
            assertThat(it.promptTokens).isEqualTo(LlmCallMetricsPort.TOKENS_UNKNOWN)
            assertThat(it.completionTokens).isEqualTo(LlmCallMetricsPort.TOKENS_UNKNOWN)
        })
    }

    @Test
    fun `a non-2xx before the stream opens records http_error`(): Unit = runBlocking {
        val result = stream(StubHttpClient(401, sse("""{"error":"invalid api key"}""")))

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(recorded).singleElement().satisfies({
            assertThat(it.outcome).isEqualTo(LlmCallMetricsPort.OUTCOME_HTTP_ERROR)
            assertThat(it.provider).isEqualTo(LlmCallMetricsPort.PROVIDER_LITELLM)
        })
    }

    @Test
    fun `a transport failure before any response records exception`(): Unit = runBlocking {
        val client = StubHttpClient(0, sse(), failWith = IOException("connect timed out"))
        val result = stream(client)

        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(recorded).singleElement().satisfies({
            assertThat(it.outcome).isEqualTo(LlmCallMetricsPort.OUTCOME_EXCEPTION)
        })
    }

    @Test
    fun `a blank api key records not_configured before the call leaves the process`(): Unit = runBlocking {
        // The real degraded path — and it must be counted, not silent: `complete()` counted it and
        // `completeStream()` did not.
        System.clearProperty(KEY_PROPERTY)
        val result = stream(StubHttpClient(200, sse("data: [DONE]")))

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(recorded).singleElement().satisfies({
            assertThat(it.outcome).isEqualTo(LlmCallMetricsPort.OUTCOME_NOT_CONFIGURED)
            assertThat(it.provider).isEqualTo(LlmCallMetricsPort.PROVIDER_LITELLM)
        })
    }

    private companion object {
        const val KEY_PROPERTY = "copilot.model.api-key"
    }
}
