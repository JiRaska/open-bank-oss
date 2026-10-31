// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.Flow

class BoundedBodyHandlersTest {
    private lateinit var server: HttpServer
    private val http = HttpClient.newHttpClient()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/small") { ex ->
            val b = "héllo".toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        // Chunked (length 0) and endless-looking: the cap must hold without trusting a header.
        server.createContext("/big") { ex ->
            ex.sendResponseHeaders(200, 0)
            runCatching { ex.responseBody.use { out -> repeat(64) { out.write(ByteArray(1024)) } } }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun get(path: String) =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:${server.address.port}$path")).build()

    @Test
    fun `a body within the cap is returned as a string`() {
        assertThat(http.send(get("/small"), BoundedBodyHandlers.ofString(1024)).body()).isEqualTo("héllo")
    }

    @Test
    fun `a body over the cap fails the call instead of being buffered`() {
        assertThatThrownBy { http.send(get("/big"), BoundedBodyHandlers.ofString(8 * 1024)) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("exceeds 8192 bytes")
    }

    @Test
    fun `the same body passes when the cap allows it`() {
        assertThat(http.send(get("/big"), BoundedBodyHandlers.ofString(64 * 1024)).body()).hasSize(64 * 1024)
    }

    @Test
    fun `subscriber requests one item at a time and stops requesting after the cap`() {
        val info = object : HttpResponse.ResponseInfo {
            override fun statusCode() = 200
            override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap<String, List<String>>()) { _, _ -> true }
            override fun version() = HttpClient.Version.HTTP_1_1
        }
        val subscriber = BoundedBodyHandlers.ofString(5).apply(info)
        val requests = mutableListOf<Long>()
        var cancelled = false
        subscriber.onSubscribe(object : Flow.Subscription {
            override fun request(n: Long) {
                requests.add(n)
            }
            override fun cancel() {
                cancelled = true
            }
        })
        assertThat(requests).containsExactly(1L)

        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3))))
        assertThat(requests).containsExactly(1L, 1L)
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(4, 5, 6))))
        assertThat(cancelled).isTrue()
        assertThat(requests).containsExactly(1L, 1L)
    }

    @Test
    fun `an over-limit body fails even if cancellation completes synchronously`() {
        val info = object : HttpResponse.ResponseInfo {
            override fun statusCode() = 200
            override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap<String, List<String>>()) { _, _ -> true }
            override fun version() = HttpClient.Version.HTTP_1_1
        }
        val subscriber = BoundedBodyHandlers.ofString(5).apply(info)
        subscriber.onSubscribe(object : Flow.Subscription {
            override fun request(n: Long) = Unit
            override fun cancel() = subscriber.onComplete()
        })

        subscriber.onNext(listOf(ByteBuffer.wrap("oversized".toByteArray())))

        assertThatThrownBy { subscriber.getBody().toCompletableFuture().join() }
            .hasCauseInstanceOf(IOException::class.java)
            .hasMessageContaining("exceeds 5 bytes")
    }
}
