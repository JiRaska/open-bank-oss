// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.libs.security

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class HttpExchangeDeadlineTest {
    private val client: HttpClient = mockk()
    private val handler = BoundedBodyHandlers.ofString()

    @Test
    fun `timeout cancels the original exchange future`() {
        val original = CompletableFuture<HttpResponse<String>>()
        every { client.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns original
        assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            assertThatThrownBy { HttpExchangeDeadline.send(client, request(Duration.ofMillis(20)), handler) }
                .isInstanceOf(HttpTimeoutException::class.java)
        }
        assertThat(original.isCancelled).isTrue()
    }

    @Test
    fun `transport maximum cannot extend a shorter request budget`() {
        val original = CompletableFuture<HttpResponse<String>>()
        every { client.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns original
        assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            assertThatThrownBy {
                HttpExchangeDeadline.send(client, request(Duration.ofMillis(20)), handler, Duration.ofSeconds(10))
            }.isInstanceOf(HttpTimeoutException::class.java)
        }
        assertThat(original.isCancelled).isTrue()
    }

    @Test
    fun `transport maximum also bounds a longer request budget`() {
        val original = CompletableFuture<HttpResponse<String>>()
        every { client.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns original
        assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            assertThatThrownBy {
                HttpExchangeDeadline.send(client, request(Duration.ofSeconds(10)), handler, Duration.ofMillis(20))
            }.isInstanceOf(HttpTimeoutException::class.java)
        }
        assertThat(original.isCancelled).isTrue()
    }

    @Test
    fun `original asynchronous failure is unwrapped`() {
        val original = IOException("synthetic")
        every { client.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns
            CompletableFuture.failedFuture(original)
        assertThatThrownBy { HttpExchangeDeadline.send(client, request(Duration.ofSeconds(1)), handler) }
            .isSameAs(original)
    }

    @Test
    fun `large positive duration still accepts a completed exchange`() {
        val response: HttpResponse<String> = mockk()
        every { client.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns
            CompletableFuture.completedFuture(response)
        assertThat(HttpExchangeDeadline.send(client, request(Duration.ofSeconds(Long.MAX_VALUE)), handler))
            .isSameAs(response)
    }

    @Test
    fun `interruption cancels the original future and preserves interrupt status`() {
        val original = CompletableFuture<HttpResponse<String>>()
        val started = CountDownLatch(1)
        every { client.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } answers {
            started.countDown()
            original
        }
        val failure = AtomicReference<Throwable>()
        val interrupted = AtomicBoolean()
        val caller = Thread.ofVirtual().start {
            try {
                HttpExchangeDeadline.send(client, request(Duration.ofSeconds(10)), handler)
            } catch (e: Throwable) {
                failure.set(e)
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        }
        try {
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue()
            caller.interrupt()
            caller.join(2000)
            assertThat(caller.isAlive).isFalse()
            assertThat(failure.get()).isInstanceOf(InterruptedException::class.java)
            assertThat(interrupted.get()).isTrue()
            assertThat(original.isCancelled).isTrue()
        } finally {
            caller.interrupt()
            caller.join(2000)
        }
    }

    private fun request(timeout: Duration): HttpRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1/"))
        .timeout(timeout).GET().build()
}
