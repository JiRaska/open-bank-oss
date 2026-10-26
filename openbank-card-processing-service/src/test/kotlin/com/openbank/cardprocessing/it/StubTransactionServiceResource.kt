// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.it

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A REAL HTTP server standing in for transaction-service, so the generated client's actual wire
 * behaviour is exercised (status handling, the default response mapper, Jackson binding) - a mocked
 * client that throws a hand-built exception proves nothing about what the REST client really throws.
 *
 * The answer is chosen by the `description` of the posted transfer (the due event's
 * `remittanceInfo`), because the test class and this resource live in different class loaders and
 * cannot share state. `GET /__count?m=<marker>` reports how many POSTs carried that marker.
 */
class StubTransactionServiceResource : QuarkusTestResourceLifecycleManager {

    private var server: HttpServer? = null

    override fun start(): Map<String, String> {
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.createContext("/api/v1/transactions") { ex -> handlePost(ex, counts) }
        http.createContext("/__count") { ex ->
            val marker = ex.requestURI.query.substringAfter("m=")
            reply(ex, 200, (counts[marker]?.get() ?: 0).toString())
        }
        http.start()
        server = http
        return mapOf("quarkus.rest-client.transaction-api.url" to "http://127.0.0.1:${http.address.port}")
    }

    override fun stop() {
        server?.stop(0)
    }

    private fun handlePost(ex: HttpExchange, counts: ConcurrentHashMap<String, AtomicInteger>) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val marker = MARKERS.firstOrNull { body.contains("\"$it\"") } ?: "stub-ok"
        counts.computeIfAbsent(marker) { AtomicInteger() }.incrementAndGet()
        when (marker) {
            "stub-422" -> reply(
                ex,
                422,
                """{"code":"INSUFFICIENT_FUNDS","message":"synthetic-private-provider-body"}""",
            )

            "stub-503" -> reply(ex, 503, """{"message":"unavailable"}""")

            "stub-429" -> reply(ex, 429, """{"message":"slow down"}""")

            "stub-403" -> reply(ex, 403, """{"message":"forbidden"}""")

            "stub-409" -> reply(ex, 409, """{"message":"conflict"}""")

            // No status line at all: the connection is cut after the request was read.
            "stub-drop" -> ex.close()

            // 2xx whose body carries a field the generated type does not know and omits every other one.
            "stub-extra" -> reply(
                ex,
                201,
                """{"id":"00000000-0000-0000-0000-0000000000f1","status":"COMPLETED","aBrandNewField":{"x":[1,2]}}""",
            )

            // 2xx whose body cannot bind at all: `id` is a UUID in the contract.
            "stub-unbindable" -> reply(ex, 201, """{"id":"not-a-uuid","status":"COMPLETED"}""")

            else -> reply(ex, 201, """{"id":"00000000-0000-0000-0000-0000000000f2","status":"COMPLETED"}""")
        }
    }

    private fun reply(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private companion object {
        val MARKERS = listOf(
            "stub-422",
            "stub-503",
            "stub-429",
            "stub-403",
            "stub-409",
            "stub-drop",
            "stub-extra",
            "stub-unbindable",
        )
    }
}
