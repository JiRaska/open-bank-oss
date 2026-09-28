// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.resilience

import com.sun.net.httpserver.HttpServer
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.client.Client
import jakarta.ws.rs.client.Entity
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.faulttolerance.Retry
import org.jboss.resteasy.reactive.client.impl.ClientBuilderImpl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADR-0321 D2 end to end on a REAL JAX-RS client (the resteasy-reactive engine under Quarkus's
 * REST client) against a stub HTTP server: a money-sync call is retried only when it carried an
 * `Idempotency-Key`, on 5xx, connect-refused and timeout alike, and never on a 4xx.
 *
 * The retry loop is [retryPer], which applies the `@Retry` annotation on [moneySyncAdapter] —
 * built from the [ResilienceProfiles.MoneySync] constants — with MicroProfile FT's spec rule
 * (retry iff the exception is assignable to `retryOn` and not to `abortOn`, at most `maxRetries`
 * times). The SmallRye interceptor itself needs a CDI container this plain library module has no
 * way to boot; what this test owns is which exception type each failure becomes.
 */
class KeyedCallFilterClientTest {

    private lateinit var server: HttpServer
    private lateinit var client: Client
    private val hits = AtomicInteger()

    @Retry(
        maxRetries = ResilienceProfiles.MoneySync.MAX_RETRIES,
        delay = ResilienceProfiles.MoneySync.DELAY_MS,
        jitter = ResilienceProfiles.MoneySync.JITTER_MS,
        retryOn = [RetryableKeyedCallException::class],
        abortOn = [UpstreamCallException::class],
    )
    @ResilienceProfile(ResilienceProfiles.MONEY_SYNC)
    @Suppress("UnusedPrivateMember")
    private fun moneySyncAdapter() = Unit

    private fun <T> retryPer(block: () -> T): T {
        val retry = javaClass.getDeclaredMethod("moneySyncAdapter").getAnnotation(Retry::class.java)
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Throwable) {
                val retryable = retry.retryOn.any { it.java.isInstance(e) } &&
                    retry.abortOn.none { it.java.isInstance(e) }
                if (!retryable || attempt >= retry.maxRetries) throw e
                attempt++
            }
        }
    }

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // The default executor is one thread: a retry would queue behind the still-sleeping
        // first attempt and time out without ever reaching the handler.
        server.executor = Executors.newCachedThreadPool()
        client = ClientBuilderImpl()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .register(KeyedCallFilter())
            .build()
    }

    @AfterEach
    fun stop() {
        client.close()
        server.stop(0)
    }

    private fun answer(status: Int, delayMs: Long = 0) {
        server.createContext("/postings") { ex ->
            hits.incrementAndGet()
            if (delayMs > 0) Thread.sleep(delayMs)
            ex.sendResponseHeaders(status, -1)
            ex.close()
        }
        server.start()
    }

    private fun post(port: Int, key: String?) {
        KeyedCall.invoke(keyed = key != null) {
            val req = client.target("http://127.0.0.1:$port/postings").request()
            if (key != null) req.header(KeyedCallFilter.IDEMPOTENCY_KEY_HEADER, key)
            req.post(Entity.json("{}"), String::class.java)
        }
    }

    private fun port() = server.address.port

    @Test
    fun `a keyed call is retried once on 5xx`() {
        answer(503)
        assertThatThrownBy { retryPer { post(port(), "k-1") } }
            .isInstanceOf(RetryableKeyedCallException::class.java)
        assertThat(hits.get()).isEqualTo(1 + ResilienceProfiles.MoneySync.MAX_RETRIES)
    }

    @Test
    fun `an unkeyed call is NOT retried on 5xx`() {
        answer(503)
        assertThatThrownBy { retryPer { post(port(), null) } }
            .isInstanceOf(UpstreamCallException::class.java)
        assertThat(hits.get()).isEqualTo(1)
    }

    @Test
    fun `a blank key counts as unkeyed`() {
        answer(500)
        assertThatThrownBy { retryPer { post(port(), " ") } }
            .isInstanceOf(UpstreamCallException::class.java)
        assertThat(hits.get()).isEqualTo(1)
    }

    @Test
    fun `a 4xx is never retried even when keyed`() {
        answer(409)
        assertThatThrownBy { retryPer { post(port(), "k-1") } }
            .isInstanceOf(WebApplicationException::class.java)
        assertThat(hits.get()).isEqualTo(1)
    }

    @Test
    fun `a keyed timeout is retried and an unkeyed one is not`() {
        answer(200, delayMs = READ_TIMEOUT_MS * 4)
        assertThatThrownBy { retryPer { post(port(), "k-1") } }
            .isInstanceOf(RetryableKeyedCallException::class.java)
        assertThat(hits.get()).isEqualTo(1 + ResilienceProfiles.MoneySync.MAX_RETRIES)

        hits.set(0)
        assertThatThrownBy { retryPer { post(port(), null) } }
            .isInstanceOf(UpstreamCallException::class.java)
        assertThat(hits.get()).isEqualTo(1)
    }

    @Test
    fun `connect refused is retryable only when keyed`() {
        val closed = ServerSocket(0).use { it.localPort }
        var attempts = 0
        assertThatThrownBy {
            retryPer {
                attempts++
                post(closed, "k-1")
            }
        }
            .isInstanceOf(RetryableKeyedCallException::class.java)
        assertThat(attempts).isEqualTo(1 + ResilienceProfiles.MoneySync.MAX_RETRIES)

        attempts = 0
        assertThatThrownBy {
            retryPer {
                attempts++
                post(closed, null)
            }
        }
            .isInstanceOf(UpstreamCallException::class.java)
        assertThat(attempts).isEqualTo(1)
    }

    @Test
    fun `a 2xx passes through untouched`() {
        answer(200)
        post(port(), null)
        assertThat(hits.get()).isEqualTo(1)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 500L
        const val READ_TIMEOUT_MS = 300L
    }
}
