// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sdd.integration

import com.openbank.clients.transaction.TransactionCallOutcome
import com.openbank.clients.transaction.TransactionClient
import com.openbank.clients.transaction.TransactionCompletionUncertainException
import com.openbank.clients.transaction.TransactionHttpException
import com.openbank.clients.transaction.model.InitiateTransactionRequest
import com.openbank.clients.transaction.model.TransactionResponse
import com.openbank.sdd.infrastructure.client.TransactionServiceClient
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import io.vertx.mutiny.core.Vertx
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The outcome facade reads the response body LAZILY, and in a reactive service that read happens on
 * whichever thread completes the call - normally a Vert.x event loop. This drives the REAL Quarkus
 * reactive REST client (`TransactionServiceClient`) over a real socket from an event-loop context and
 * asserts that the body is read there without "booked, body unreadable" (which would be reported for
 * every call in production if the read failed on the IO thread).
 */
@QuarkusTest
@QuarkusTestResource(SddTransactionClientEventLoopIT.Stub::class)
@QuarkusTestResource(SddBootSmokeIT.InMemoryKafkaResource::class)
@QuarkusTestResource(com.openbank.sdd.it.PostgresTestResource::class)
class SddTransactionClientEventLoopIT {

    class Stub : QuarkusTestResourceLifecycleManager {
        private var server: HttpServer? = null

        override fun start(): Map<String, String> {
            val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            http.createContext("/api/v1/transactions") { exchange ->
                exchange.requestBody.readAllBytes()
                if (status < 0) {
                    // A connection dropped before any status line.
                    exchange.close()
                    return@createContext
                }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }
            http.start()
            server = http
            return mapOf("quarkus.rest-client.transaction-service.url" to "http://127.0.0.1:${http.address.port}")
        }

        override fun stop() {
            server?.stop(0)
            InMemoryConnector.clear()
        }

        companion object {
            @Volatile var status = 201

            @Volatile var body = "{}"
        }
    }

    @Inject
    lateinit var vertx: Vertx

    @Inject
    @RestClient
    lateinit var client: TransactionServiceClient

    private val request = InitiateTransactionRequest(
        idempotencyKey = "event-loop-it",
        type = "DEBIT",
        amount = BigDecimal("10.00"),
        currencyCode = "EUR",
        valueDate = LocalDate.parse("2026-01-01"),
        sourceAccountId = UUID.randomUUID(),
    )

    private class Seen(val outcome: TransactionCallOutcome<TransactionResponse>, val thread: String)

    /** Starts the call from a Vert.x event-loop context and reports the thread the classification ran on. */
    private fun callOnEventLoop(): Seen {
        val seen = CompletableFuture<Seen>()
        vertx.runOnContext {
            TransactionClient(client).initiate(request).subscribe().with(
                { seen.complete(Seen(it, Thread.currentThread().name)) },
                { seen.completeExceptionally(it) },
            )
        }
        return seen.get(30, TimeUnit.SECONDS)
    }

    @Test
    fun `a readable 201 body is read on the event loop - not reported as success without a body`() {
        val id = UUID.randomUUID()
        Stub.status = 201
        Stub.body = """{"id":"$id","status":"COMPLETED"}"""

        val seen = callOnEventLoop()
        // Recorded in the test report: which thread the classification (and so the body read) ran on.
        println("EVENTLOOP-IT classification thread = ${seen.thread}")

        val booked = seen.outcome as TransactionCallOutcome.HttpSuccess
        assertThat(booked.bodyError).describedAs("body read on thread %s", seen.thread).isNull()
        assertThat(booked.body?.id).isEqualTo(id)
    }

    @Test
    fun `an unreadable 201 body on initiate is completion-uncertain, never booked, over the real Quarkus client`() {
        Stub.status = 201
        Stub.body = """{"id":"not-a-uuid"}"""

        assertThatThrownBy { callOnEventLoop() }.satisfies({ failure ->
            assertThat(generateSequence(failure) { it.cause.takeIf { c -> c !== it } })
                .anyMatch { it is TransactionCompletionUncertainException }
        })
    }

    @Test
    fun `a 422 over the real Quarkus client is Rejected with the problem text, a 409 is Conflict`() {
        Stub.status = 422
        Stub.body = """{"code":"NOT_REVERSIBLE"}"""
        val rejected = callOnEventLoop().outcome as TransactionCallOutcome.Rejected
        assertThat(rejected.problem).contains("NOT_REVERSIBLE")

        Stub.status = 409
        Stub.body = "{}"
        assertThat(callOnEventLoop().outcome).isEqualTo(TransactionCallOutcome.Conflict)
    }

    @Test
    fun `a connection dropped before any status is thrown by the real Quarkus client, never an outcome`() {
        Stub.status = -1

        assertThatThrownBy { callOnEventLoop() }.satisfies({ failure ->
            // Whatever the Quarkus client raises for a dropped connection, it is an exception - not Booked,
            // not Rejected - and it is not a status verdict either, so the caller retries.
            // Measured with the real client: the cause is a plain java.io.IOException.
            assertThat(failure.cause).isInstanceOf(java.io.IOException::class.java)
            assertThat(failure.cause).isNotInstanceOf(TransactionHttpException::class.java)
        })
    }
}
