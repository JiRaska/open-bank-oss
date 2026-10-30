// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.annuity

import com.openbank.pension.application.annuity.PartnerPolicyState
import com.openbank.pension.domain.annuity.AnnuityProviderTerms
import com.openbank.pension.domain.annuity.AnnuityType
import com.openbank.pension.domain.annuity.ApprovedPartner
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ReferenceAnnuityResponseLifecycleTest {
    @Test
    fun `declared error body is rejected before body arrives`(): Unit = error(false)

    @Test
    fun `chunked error body is rejected before body arrives`(): Unit = error(true)

    private fun error(chunked: Boolean): Unit = runBlocking {
        withPeer(503, chunked) { provider, headers, closed ->
            val call = async { runCatching { adapter.status(provider, "APP-77") } }
            withTimeout(3000) { headers.await() }
            val result = withTimeout(1500) { call.await() }
            assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
                .hasMessage("partner ref-partner answered HTTP 503 on GET /annuity/v1/policies/APP-77")
            assertThat(closed.get(3, TimeUnit.SECONDS)).isEqualTo(-1)
        }
    }

    @Test
    fun `cancelling successful held response closes exchange`(): Unit = runBlocking {
        withPeer(200, true) { provider, headers, closed ->
            val call = async { adapter.status(provider, "APP-77") }
            withTimeout(3000) { headers.await() }
            call.cancelAndJoin()
            assertThat(call.isCancelled).isTrue()
            assertThat(closed.get(3, TimeUnit.SECONDS)).isEqualTo(-1)
        }
    }

    @Test
    fun `deadline cancellation closes held successful exchange`(): Unit = runBlocking {
        withPeer(200, true) { provider, headers, closed ->
            val call = async {
                runCatching { withTimeout(500) { adapter.status(provider, "APP-77") } }
            }
            withTimeout(3000) { headers.await() }
            assertThat(call.await().exceptionOrNull()).isInstanceOf(CancellationException::class.java)
            assertThat(closed.get(3, TimeUnit.SECONDS)).isEqualTo(-1)
        }
    }

    @Test
    fun `large successful declared and chunked responses remain accepted`(): Unit = runBlocking {
        for (chunked in listOf(false, true)) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val calls = AtomicInteger()
            server.createContext("/") { exchange ->
                calls.incrementAndGet()
                val body = ByteArray(8 * 1024 * 1024) { 32 } +
                    "{\"applicationRef\":\"APP-77\",\"state\":\"ACTIVE\"}".toByteArray()
                exchange.sendResponseHeaders(200, if (chunked) 0L else body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                val result = adapter.status(provider("http://127.0.0.1:${server.address.port}"), "APP-77")
                assertThat(result.state).isEqualTo(PartnerPolicyState.ACTIVE)
                assertThat(calls.get()).isEqualTo(1)
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun `concurrent error cannot turn truncated successful body into status failure`(): Unit = runBlocking {
        val successHeaders = CompletableDeferred<Unit>()
        val releaseSuccess = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newFixedThreadPool(2)
        server.executor = executor
        server.createContext("/success") { exchange ->
            exchange.sendResponseHeaders(200, 1024)
            exchange.responseBody.write(' '.code)
            exchange.responseBody.flush()
            successHeaders.complete(Unit)
            releaseSuccess.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        server.createContext("/error") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val successful = async { runCatching { adapter.status(provider("$base/success"), "APP-77") } }
            withTimeout(3000) { successHeaders.await() }
            val failed = runCatching { adapter.status(provider("$base/error"), "APP-77") }
            assertThat(failed.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
                .hasMessage("partner ref-partner answered HTTP 503 on GET /annuity/v1/policies/APP-77")
            releaseSuccess.countDown()
            assertThat(withTimeout(3000) { successful.await() }.exceptionOrNull()).isInstanceOf(IOException::class.java)
        } finally {
            releaseSuccess.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private suspend fun withPeer(
        status: Int,
        chunked: Boolean,
        block: suspend (ApprovedPartner, CompletableDeferred<Unit>, CompletableFuture<Int>) -> Unit,
    ) {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val headers = CompletableDeferred<Unit>()
            val closed = CompletableFuture<Int>()
            val worker = thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader()
                        val lines = mutableListOf<String>()
                        while (true) {
                            val line = input.readLine()
                            if (line.isNullOrEmpty()) break
                            lines += line
                        }
                        check(lines.first() == "GET /annuity/v1/policies/APP-77 HTTP/1.1")
                        check(lines.any { it.equals("Authorization: Bearer synthetic-token", true) })
                        val framing = if (chunked) "Transfer-Encoding: chunked" else "Content-Length: 16777216"
                        socket.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 $status Test\r\nContent-Type: application/json\r\n" +
                                        "$framing\r\nConnection: close\r\n\r\n"
                                    ).toByteArray(),
                            )
                            flush()
                        }
                        headers.complete(Unit)
                        closed.complete(input.read())
                    }
                } catch (failure: Exception) {
                    headers.completeExceptionally(failure)
                    closed.completeExceptionally(failure)
                }
            }
            try {
                block(provider("http://127.0.0.1:${server.localPort}"), headers, closed)
            } finally {
                worker.join(5500)
            }
        }
    }

    private val adapter = ReferenceRestAnnuityAdapter.create({ "synthetic-token" })
    private fun provider(endpoint: String) = ApprovedPartner(
        "ref-partner",
        1,
        AnnuityProviderTerms(
            legalName = "Reference Life", legalEntityPartyId = UUID.randomUUID(), licenceRef = "L",
            licenceAuthority = "CNB", jurisdictions = setOf("CZ"), supportedTypes = AnnuityType.entries.toSet(),
            currency = "CZK", minPremium = BigDecimal.ONE, maxPremium = BigDecimal("100000000"),
            coolingOffDays = 30, premiumIban = "CZ5508000000001234567899", adapter = "reference-rest",
            endpointUrl = endpoint, effectiveFrom = LocalDate.parse("2020-01-01"),
        ),
    )
}
