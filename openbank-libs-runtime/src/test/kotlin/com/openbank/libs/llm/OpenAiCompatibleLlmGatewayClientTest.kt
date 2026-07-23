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

    private fun client(baseUrl: String, apiKey: String = "test-key") =
        OpenAiCompatibleLlmGatewayClient(baseUrl = baseUrl, model = "test-model", apiKey = apiKey)

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
}
