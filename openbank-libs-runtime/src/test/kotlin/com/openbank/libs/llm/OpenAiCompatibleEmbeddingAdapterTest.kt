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
 * Against a real in-JVM HTTP stub. The interesting cases are the two silent-corruption ones —
 * out-of-order items and a wrong vector width — because neither produces an error anywhere: the
 * first mis-pairs every embedding with the wrong text and surfaces months later as "search returns
 * unrelated passages", and the second fails one INSERT at a time in a way that reads as a database
 * problem rather than a model swap.
 */
class OpenAiCompatibleEmbeddingAdapterTest {

    private var server: HttpServer? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    private fun startStub(status: Int, responseBody: String): String {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/v1/embeddings") { ex: HttpExchange ->
            ex.requestBody.readBytes()
            val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        srv.start()
        server = srv
        return "http://127.0.0.1:${srv.address.port}/v1"
    }

    private fun adapter(baseUrl: String, dimensions: Int = 3, apiKey: String = "k") = OpenAiCompatibleEmbeddingAdapter(
        baseUrl = baseUrl,
        model = "BAAI/bge-m3",
        dimensions = dimensions,
        apiKey = apiKey,
    )

    @Test
    fun `vectors are returned in request order`(): Unit = runBlocking {
        // Deliberately shuffled: the provider is documented to return an `index` per item and does
        // reorder under batching.
        val url = startStub(
            200,
            """{"data":[{"index":1,"embedding":[0.4,0.5,0.6]},{"index":0,"embedding":[0.1,0.2,0.3]}]}""",
        )

        val out = adapter(url).embed(listOf("first", "second"))

        assertThat(out).isNotNull
        assertThat(out!![0][0]).isEqualTo(0.1f)
        assertThat(out[1][0]).isEqualTo(0.4f)
    }

    @Test
    fun `a wrong vector width discards the whole batch`(): Unit = runBlocking {
        val url = startStub(200, """{"data":[{"index":0,"embedding":[0.1,0.2]}]}""")

        // Discarding the batch, not the one bad row: a partial list cannot tell the caller which
        // input was dropped, and storing the rest under the current model id would leave an index
        // that is quietly incomplete.
        assertThat(adapter(url, dimensions = 3).embed(listOf("x"))).isNull()
    }

    @Test
    fun `a short response discards the batch`(): Unit = runBlocking {
        val url = startStub(200, """{"data":[{"index":0,"embedding":[0.1,0.2,0.3]}]}""")

        assertThat(adapter(url).embed(listOf("a", "b"))).isNull()
    }

    @Test
    fun `http error returns null, not an empty list`(): Unit = runBlocking {
        val url = startStub(503, "nope")

        // null, never emptyList: an empty list is the correct answer to an empty request, so a
        // caller using isEmpty() to detect an outage would silently index nothing and report success.
        assertThat(adapter(url).embed(listOf("a"))).isNull()
    }

    @Test
    fun `an unseeded key returns null without a call`(): Unit = runBlocking {
        assertThat(adapter("http://127.0.0.1:1/v1", apiKey = "").embed(listOf("a"))).isNull()
    }

    @Test
    fun `an empty input is a valid empty result, not an outage`(): Unit = runBlocking {
        assertThat(adapter("http://127.0.0.1:1/v1").embed(emptyList())).isEmpty()
    }

    @Test
    fun `the disabled port produces nothing`(): Unit = runBlocking {
        assertThat(EmbeddingPort.DISABLED.embed(listOf("a"))).isNull()
    }
}
