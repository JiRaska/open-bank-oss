// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.libs.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Bounds the original exchange through body completion without a timer or additional executor. */
internal object HttpExchangeDeadline {
    fun <T> send(
        client: HttpClient,
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
        maximum: Duration = request.timeout().orElseThrow {
            IllegalArgumentException("HTTP request timeout is required")
        },
    ): HttpResponse<T> {
        val duration = minOf(maximum, request.timeout().orElse(maximum))
        require(!duration.isNegative && !duration.isZero) { "HTTP request timeout must be positive" }
        val budget = try {
            duration.toNanos()
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
        val started = System.nanoTime()
        val exchange = client.sendAsync(request, handler)
        return try {
            val remaining = budget - (System.nanoTime() - started)
            if (remaining <= 0) deadlineExceeded(exchange, TimeoutException())
            exchange.get(remaining, TimeUnit.NANOSECONDS)
        } catch (e: TimeoutException) {
            deadlineExceeded(exchange, e)
        } catch (e: InterruptedException) {
            exchange.cancel(true)
            Thread.currentThread().interrupt()
            throw e
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    suspend fun <T> sendCancellable(
        client: HttpClient,
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> = runInterruptible(Dispatchers.IO) { send(client, request, handler) }

    private fun deadlineExceeded(exchange: CompletableFuture<*>, cause: TimeoutException): Nothing {
        exchange.cancel(true)
        throw HttpTimeoutException("HTTP request deadline exceeded").apply { initCause(cause) }
    }
}
