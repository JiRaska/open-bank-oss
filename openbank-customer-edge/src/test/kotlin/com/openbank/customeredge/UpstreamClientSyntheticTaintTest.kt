// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge

import com.openbank.customeredge.infrastructure.rest.UpstreamClient
import com.openbank.libs.synthetic.SyntheticTaint
import com.openbank.libs.web.MDC_SYNTHETIC
import com.sun.net.httpserver.HttpServer
import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.context.Context
import org.assertj.core.api.Assertions.assertThat
import org.jboss.logging.MDC
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * The synthetic taint survives the edge (ADR-0252 phase 1, #4348).
 *
 * customer-edge reaches every upstream through [UpstreamClient], a plain Java HttpClient that
 * `SyntheticTaintClientFilter` never sees. These tests drive the real client against a local
 * server and read the headers that actually arrived, so "the header was added" means it was on
 * the wire, not that a builder method was called.
 */
class UpstreamClientSyntheticTaintTest {
    private lateinit var server: HttpServer
    private val received = ConcurrentHashMap<String, String>()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                received[exchange.requestURI.path] =
                    exchange.requestHeaders.getFirst(SyntheticTaint.KAFKA_HEADER) ?: ABSENT
                val body = if (exchange.requestURI.path.endsWith("/token")) {
                    """{"access_token":"t","expires_in":300}"""
                } else {
                    "{}"
                }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    @AfterEach
    fun stop() {
        MDC.remove(MDC_SYNTHETIC)
        server.stop(0)
    }

    private fun base() = "http://127.0.0.1:${server.address.port}"

    private fun client() = UpstreamClient().apply {
        tokenEndpointBase = "${base()}/realm"
        clientId = "edge"
        clientSecret = "s"
        tlsTrustCertificateFile = Optional.empty()
    }

    @Test
    fun `a real request carries no taint upstream`() {
        val c = client()
        c.get("${base()}/api/v1/holdings", "p")
        c.post("${base()}/api/v1/holdings", "p", "{}")

        assertThat(received["/api/v1/holdings"]).isEqualTo(ABSENT)
    }

    @Test
    fun `a request the edge accepted as synthetic carries the taint on every upstream call`() {
        val c = client()
        MDC.put(MDC_SYNTHETIC, "true")

        c.get("${base()}/a", "p")
        c.post("${base()}/b", "p", "{}")
        c.put("${base()}/c", "p", "{}")
        c.delete("${base()}/d", "p")
        c.patch("${base()}/e", "p", "{}")

        listOf("/a", "/b", "/c", "/d", "/e").forEach { path ->
            assertThat(received[path]).describedAs(path).isEqualTo("true")
        }
    }

    @Test
    fun `the baggage rail alone is enough, as it is for the client filter`() {
        val c = client()
        val scope = Baggage.builder().put(SyntheticTaint.BAGGAGE_KEY, SyntheticTaint.headerValue()).build()
            .storeInContext(Context.current()).makeCurrent()
        try {
            c.get("${base()}/f", "p")
        } finally {
            scope.close()
        }

        assertThat(received["/f"]).isEqualTo("true")
    }

    @Test
    fun `the token endpoint never receives the taint`() {
        MDC.put(MDC_SYNTHETIC, "true")

        client().get("${base()}/g", "p")

        assertThat(received["/g"]).isEqualTo("true")
        assertThat(received.keys).contains("/realm/protocol/openid-connect/token")
        assertThat(received["/realm/protocol/openid-connect/token"]).isEqualTo(ABSENT)
    }

    /**
     * Structural guard: a new upstream method written with `HttpRequest.newBuilder()` directly
     * would silently drop the taint, and every behavioural test above would stay green because
     * none of them calls a method that does not exist yet. Exactly two direct builders may exist:
     * the token request and `upstreamRequest()` itself.
     */
    @Test
    fun `every upstream request is built through upstreamRequest`() {
        val source = File("src/main/kotlin/com/openbank/customeredge/infrastructure/rest/UpstreamClient.kt").readText()

        assertThat(Regex("""HttpRequest\.newBuilder\(\)""").findAll(source).count()).isEqualTo(2)
        assertThat(source).contains("val request = HttpRequest.newBuilder() // token endpoint")
        assertThat(source).contains("private fun upstreamRequest(): HttpRequest.Builder = HttpRequest.newBuilder()")
    }

    private companion object {
        /** ConcurrentHashMap holds no nulls; an absent header is recorded as this sentinel. */
        const val ABSENT = "<absent>"
    }
}
