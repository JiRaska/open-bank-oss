// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * One loopback server standing in for every upstream of the business-approval flow: party (mandate
 * and names), account, sca, delegation-service's signing API, and the four payment rails. Routes
 * are keyed by METHOD + path, and every request is recorded with its headers and body, so a test
 * can assert what reached the rail — which a status code cannot.
 *
 * Also switches `openbank.edge.business-approvals.enforce` on for the booted app: the hold is off
 * by default until delegation-service serves the API, and this is the environment where it does.
 */
class BusinessApprovalStubs : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 200, """{"access_token":"stub-token","expires_in":300}""")
        }
        s.createContext("/") { ex ->
            val key = "${ex.requestMethod} ${ex.requestURI.path}"
            val body = ex.requestBody.bufferedReader().use { it.readText() }
            synchronized(requests) {
                requests += Request(
                    ex.requestMethod,
                    ex.requestURI.path,
                    ex.requestURI.rawQuery,
                    ex.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.toList() },
                    body,
                )
            }
            val handler = routes[key]
            if (handler == null) {
                respond(ex, 404, """{"error":"no stub registered for $key"}""")
            } else {
                val (status, out) = handler(body)
                respond(ex, status, out)
            }
        }
        s.executor = null
        s.start()
        server = s
        val base = "http://127.0.0.1:${s.address.port}"
        return mapOf(
            "openbank.upstream.token-url" to base,
            "openbank.edge.account-service-url" to base,
            "openbank.edge.party-service-url" to base,
            "openbank.edge.sca-service-url" to base,
            "openbank.edge.delegation-service-url" to base,
            "openbank.edge.domestic-payment-service-url" to base,
            "openbank.edge.sepa-payment-service-url" to base,
            "openbank.edge.sepa-instant-service-url" to base,
            "openbank.edge.swift-service-url" to base,
            "openbank.edge.business-approvals.enforce" to "true",
        )
    }

    override fun stop() {
        server?.stop(0)
        server = null
        reset()
    }

    private fun respond(ex: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    data class Request(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
    }

    companion object {
        private var server: HttpServer? = null
        private val routes = ConcurrentHashMap<String, (String) -> Pair<Int, String>>()
        private val requests = mutableListOf<Request>()

        fun stub(method: String, path: String, status: Int = 200, body: String) {
            routes["$method $path"] = { status to body }
        }

        fun requests(method: String, path: String): List<Request> =
            synchronized(requests) { requests.filter { it.method == method && it.path == path } }

        fun reset() {
            routes.clear()
            synchronized(requests) { requests.clear() }
        }
    }
}
