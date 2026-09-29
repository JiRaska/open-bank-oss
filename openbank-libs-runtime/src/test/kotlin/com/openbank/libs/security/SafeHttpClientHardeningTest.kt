// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread

/**
 * Adversarial wire cases from the ADR-0320 P1 security review. Each test was run against the
 * pre-fix client and failed there.
 */
class SafeHttpClientHardeningTest {
    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val publicAddr: InetAddress = InetAddress.getByName("93.184.216.34")
    private val servers = mutableListOf<ServerSocket>()

    @AfterEach
    fun close() = servers.forEach { it.close() }

    /** Accepts connections, drains the request head, then runs [respond] on the socket. */
    private fun server(respond: (Socket) -> Unit): Int {
        val server = ServerSocket(0, 50, loopback)
        servers += server
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s =
                    try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                thread(isDaemon = true) {
                    try {
                        s.use {
                            val r = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            while (r.readLine()?.isNotEmpty() == true) {
                                // drain request head
                            }
                            respond(it)
                        }
                    } catch (_: IOException) {
                        // client gave up — expected in the deadline cases
                    }
                }
            }
        }
        return server.localPort
    }

    private fun canned(raw: String): Int = server {
        it.getOutputStream().write(raw.toByteArray(Charsets.ISO_8859_1))
        it.getOutputStream().flush()
    }

    private fun client(port: Int, maxBytes: Int = SafeHttpClient.DEFAULT_MAX_RESPONSE_BYTES) = SafeHttpClient(
        EgressPolicy.fromConfig(listOf("stub.test:$port;http;private")),
        resolver = { listOf(loopback) },
        readTimeout = Duration.ofSeconds(2),
        maxResponseBytes = maxBytes,
    )

    private fun get(port: Int, method: String = "GET", maxBytes: Int = SafeHttpClient.DEFAULT_MAX_RESPONSE_BYTES) =
        client(port, maxBytes).send(EgressRequest(method, "http://stub.test:$port/"))

    private fun assertRejected(raw: String, method: String = "GET") {
        val port = canned(raw)
        assertThatThrownBy { get(port, method) }.`as`(raw).isInstanceOf(IOException::class.java)
            .isNotInstanceOf(EgressDeniedException::class.java)
    }

    // 1 -------------------------------------------------------------------------------------------
    @Test
    fun `chunk size cannot overflow the body cap`() {
        val port = server {
            val out = it.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n7fffffff\r\n".toByteArray())
            out.write(ByteArray(64 * 1024))
            out.flush()
        }
        assertThatThrownBy { get(port, maxBytes = 1024) }
            .isInstanceOf(IOException::class.java)
            .hasMessageContaining("exceeds")
    }

    @Test
    fun `an over-long chunk-size line is rejected`() {
        assertRejected("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n000000001\r\na\r\n0\r\n\r\n")
    }

    @Test
    fun `chunk extensions and trailers are tolerated`() {
        val port = canned(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3;ext=\"x\"\r\nabc\r\n0\r\nX-T: 1\r\n\r\n",
        )
        assertThat(get(port).bodyAsString()).isEqualTo("abc")
    }

    // 2 -------------------------------------------------------------------------------------------
    @Test
    fun `signed Content-Length and chunk sizes are IOExceptions`() {
        assertRejected("HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n")
        assertRejected("HTTP/1.1 200 OK\r\nContent-Length: +2\r\n\r\nok")
        assertRejected("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n-1\r\n\r\n")
        assertRejected("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n+2\r\nok\r\n0\r\n\r\n")
    }

    // 3 -------------------------------------------------------------------------------------------
    @Test
    fun `the raw socket is closed when post-connect setup throws`() {
        // (A failed TLS handshake already closes the raw socket via autoClose; the leak was on the
        // setup steps around it — soTimeout, socket-factory wrap — which ran outside any try.)
        val port = canned("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
        val raws = CopyOnWriteArrayList<Socket>()
        val client = SafeHttpClient(
            EgressPolicy.fromConfig(listOf("stub.test:$port;private")),
            resolver = { listOf(loopback) },
            connector = { addr, t ->
                val s = object : Socket() {
                    override fun setSoTimeout(timeout: Int): Unit = throw java.net.SocketException("boom")
                }
                raws += s
                s.apply { connect(addr, t.toMillis().toInt()) }
            },
        )
        assertThatThrownBy { client.send(EgressRequest("GET", "https://stub.test:$port/")) }
            .isInstanceOf(IOException::class.java)
        assertThat(raws).hasSize(1)
        assertThat(raws.single().isClosed).isTrue()
    }

    // 4 -------------------------------------------------------------------------------------------
    @Test
    fun `a slowloris server is cut off by the call deadline`() {
        val port = server {
            val out = it.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nX-Slow: ".toByteArray())
            while (true) {
                out.write('a'.code)
                out.flush()
                Thread.sleep(TRICKLE_MS)
            }
        }
        val client = SafeHttpClient(
            EgressPolicy.fromConfig(listOf("stub.test:$port;http;private")),
            resolver = { listOf(loopback) },
            callTimeout = Duration.ofSeconds(2),
        )
        assertTimeoutPreemptively(Duration.ofSeconds(8)) {
            assertThatThrownBy { client.send(EgressRequest("GET", "http://stub.test:$port/")) }
                .isInstanceOf(IOException::class.java)
        }
    }

    @Test
    fun `DNS resolution is bounded`() {
        val client = SafeHttpClient(
            EgressPolicy.fromConfig(listOf("stub.test")),
            resolver = {
                Thread.sleep(10_000)
                listOf(publicAddr)
            },
            dnsTimeout = Duration.ofMillis(300),
            connector = { _, _ -> throw IOException("must not connect") },
        )
        assertTimeoutPreemptively(Duration.ofSeconds(3)) {
            assertThatThrownBy { client.send(EgressRequest("GET", "https://stub.test/")) }
                .isInstanceOfSatisfying(EgressDeniedException::class.java) {
                    assertThat(it.decision.reason).isEqualTo(EgressDenialReason.UNRESOLVABLE)
                }
        }
    }

    // 5 -------------------------------------------------------------------------------------------
    @Test
    fun `HEAD, 204 and 304 carry no body even when Content-Length says otherwise`() {
        val head = canned("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n")
        assertThat(get(head, "HEAD").body).isEmpty()
        val noContent = canned("HTTP/1.1 204 No Content\r\nContent-Length: 5\r\n\r\n")
        assertThat(get(noContent).status).isEqualTo(204)
        val notModified = canned("HTTP/1.1 304 Not Modified\r\nContent-Length: 5\r\n\r\n")
        assertThat(get(notModified).status).isEqualTo(304)
    }

    @Test
    fun `1xx interim responses are skipped`() {
        val port = canned("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
        val resp = get(port)
        assertThat(resp.status).isEqualTo(200)
        assertThat(resp.bodyAsString()).isEqualTo("ok")
    }

    // 6 -------------------------------------------------------------------------------------------
    @Test
    fun `ambiguous framing is rejected`() {
        assertRejected("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 5\r\n\r\nok")
        assertRejected("HTTP/1.1 200 OK\r\nContent-Length: 2, 5\r\n\r\nok")
        assertRejected("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 2\r\n\r\n2\r\nok\r\n0\r\n\r\n")
    }

    // 7 -------------------------------------------------------------------------------------------
    @Test
    fun `malformed status lines, bare LF, obs-fold and header floods are rejected`() {
        assertRejected("HTTP/1.1 2000 OK\r\nContent-Length: 0\r\n\r\n")
        assertRejected("HTTP/1.1 099 X\r\nContent-Length: 0\r\n\r\n")
        assertRejected("HTTP/1.1 200 OK\nContent-Length: 0\n\n")
        assertRejected("HTTP/1.1 200 OK\r\nX-A: 1\r\n X-B: 2\r\nContent-Length: 0\r\n\r\n")
        assertRejected("HTTP/1.1 200 OK\r\n" + "A: b\r\n".repeat(1000) + "Content-Length: 0\r\n\r\n")
    }

    // 8 -------------------------------------------------------------------------------------------
    @Test
    fun `request method, header names and values are strictly validated before any connect`() {
        val connects = AtomicInteger()
        val client = SafeHttpClient(
            EgressPolicy.fromConfig(listOf("api.example.com")),
            resolver = { listOf(publicAddr) },
            connector = { _, _ -> connects.incrementAndGet().let { throw IOException("must not connect") } },
        )
        val bad = listOf(
            EgressRequest("GÉT", "https://api.example.com/"),
            EgressRequest("GET", "https://api.example.com/", mapOf("Host " to "evil")),
            EgressRequest("GET", "https://api.example.com/", mapOf("Transfer-Encoding " to "chunked")),
            EgressRequest("GET", "https://api.example.com/", mapOf("X A" to "v")),
            EgressRequest("GET", "https://api.example.com/", mapOf("X-A" to "v\u0000")),
            EgressRequest("GET", "https://api.example.com/", mapOf("X-A" to "€")),
        )
        bad.forEach { r ->
            assertThatThrownBy { client.send(r) }.`as`(r.toString()).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(connects.get()).isZero()
    }

    // 10 ------------------------------------------------------------------------------------------
    @Test
    fun `the resolver gets the absolute FQDN while Host keeps the bare name`() {
        val asked = CopyOnWriteArrayList<String>()
        val heads = CopyOnWriteArrayList<String>()
        val server = ServerSocket(0, 50, loopback).also { servers += it }
        thread(isDaemon = true) {
            server.accept().use { s ->
                val r = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                heads += generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.joinToString("\n")
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
            }
        }
        val port = server.localPort
        val client = SafeHttpClient(
            EgressPolicy.fromConfig(listOf("stub.test:$port;http;private")),
            resolver = {
                asked += it
                listOf(loopback)
            },
        )
        client.send(EgressRequest("GET", "http://stub.test:$port/"))
        assertThat(asked).containsExactly("stub.test.")
        assertThat(heads.single()).contains("Host: stub.test:$port")
    }

    // 12 ------------------------------------------------------------------------------------------
    @Test
    fun `no public constructor accepts an SSLContext`() {
        val publicCtors = SafeHttpClient::class.java.constructors
        assertThat(publicCtors).isNotEmpty()
        publicCtors.forEach { c -> assertThat(c.parameterTypes).doesNotContain(SSLContext::class.java) }
    }

    companion object {
        private const val TRICKLE_MS = 100L
    }
}
