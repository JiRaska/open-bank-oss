// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.customeredge.contract

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * A loopback JDK [HttpServer] standing in for every upstream `UpstreamClient` calls (account-,
 * balance-, transaction-, fx-, card-issuance-, statement- and standing-order-service) PLUS the
 * Keycloak token endpoint, for the provider-verification test in this package (issue #2322).
 *
 * customer-edge is a BFF: it fronts seven upstream services via one generic [UpstreamClient], not
 * seven typed MP REST Client interfaces, so there is no single upstream to boot a Testcontainer
 * for. Config overrides below point every `openbank.edge.*-service-url` property AND
 * `openbank.upstream.token-url` at this one loopback server; `@State` handlers register the fixed
 * response each path should answer with before their interaction runs. The token endpoint is
 * wired unconditionally — it fires on every request via [UpstreamClient.serviceToken] regardless
 * of which upstream is being called.
 *
 * Same JDK `HttpServer` approach [com.openbank.customeredge.UpstreamClientTest] already uses for a
 * single-call unit test; this is the same idea kept alive for the whole test class instead of one
 * `withServer { }` block per test, because a `@TestTemplate`-driven Pact verification needs the
 * config override in place before Quarkus boots, not per-test.
 */
class StubUpstreamResource : QuarkusTestResourceLifecycleManager {

    companion object {
        private var server: HttpServer? = null

        /** path (no query string) -> fixed (status, contentType, body) the stub answers with. */
        private val routes = ConcurrentHashMap<String, Fixture>()
        private val requests = mutableListOf<Request>()

        data class Fixture(val status: Int, val contentType: String, val body: String)
        data class Request(val path: String, val headers: Map<String, List<String>>, val body: String)

        /** Registers (or replaces) the fixed response for [path]. Call from a `@State` handler. */
        fun stub(path: String, status: Int = 200, contentType: String = "application/json", body: String) {
            routes[path] = Fixture(status, contentType, body)
        }

        /** Clears every registered route — call at the start of each `@State` handler. */
        fun reset() {
            routes.clear()
            synchronized(requests) { requests.clear() }
        }

        fun requests(path: String): List<Request> = synchronized(requests) { requests.filter { it.path == path } }
    }

    override fun start(): Map<String, String> {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/protocol/openid-connect/token") { exchange ->
            respond(exchange, 200, "application/json", """{"access_token":"stub-token","expires_in":300}""")
        }
        s.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val requestBody = exchange.requestBody.bufferedReader().use { it.readText() }
            synchronized(requests) {
                requests += Request(path, exchange.requestHeaders.mapValues { it.value.toList() }, requestBody)
            }
            val fixture = routes[path]
            if (fixture != null) {
                respond(exchange, fixture.status, fixture.contentType, fixture.body)
            } else {
                respond(exchange, 404, "application/json", """{"error":"no stub registered for $path"}""")
            }
        }
        s.executor = null
        s.start()
        server = s
        val base = "http://127.0.0.1:${s.address.port}"
        return mapOf(
            "openbank.upstream.token-url" to base,
            "openbank.edge.account-service-url" to base,
            "openbank.edge.balance-service-url" to base,
            "openbank.edge.transaction-service-url" to base,
            "openbank.edge.statement-service-url" to base,
            "openbank.edge.standing-order-service-url" to base,
            "openbank.edge.fx-service-url" to base,
            "openbank.edge.card-issuance-service-url" to base,
            "openbank.edge.party-service-url" to base,
            "openbank.edge.product-catalog-url" to base,
            "openbank.edge.campaign-service-url" to base,
            "openbank.edge.incentive-service-url" to base,
        )
    }

    override fun stop() {
        server?.stop(0)
        server = null
        routes.clear()
        synchronized(requests) { requests.clear() }
    }

    private fun respond(exchange: HttpExchange, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
