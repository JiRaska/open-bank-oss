// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.e2e

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * One loopback server standing in for every upstream of the anonymous onboarding surface:
 * party-service (the PENDING_ACTIVATION party mint) and document-service (published-terms list,
 * render, content), plus the OIDC token endpoint UpstreamClient's M2M client_credentials flow
 * fetches before any upstream call. Routes are keyed by METHOD + path, every request is recorded
 * with headers and body, so a journey can assert WHAT reached the upstream — which a status code
 * cannot: the caller's body verbatim, the M2M bearer token attached, and the exact fan-out
 * (template list once per TTL, render once per version, content on every read).
 */
class OnboardingE2EStubs : QuarkusTestResourceLifecycleManager {

    override fun start(): Map<String, String> {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 200, """{"access_token":"e2e-stub-token","expires_in":300}""")
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
                val (status, out, bytes) = handler(body)
                respond(ex, status, out, bytes)
            }
        }
        s.executor = null
        s.start()
        server = s
        val base = "http://127.0.0.1:${s.address.port}"
        return mapOf(
            "openbank.upstream.token-url" to base,
            "openbank.edge.party-service-url" to base,
            "openbank.edge.document-service-url" to base,
        )
    }

    override fun stop() {
        server?.stop(0)
        server = null
        reset()
    }

    private fun respond(ex: HttpExchange, status: Int, body: String, bytes: ByteArray? = null) {
        val payload = bytes ?: body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, payload.size.toLong())
        ex.responseBody.use { it.write(payload) }
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
        private val routes = ConcurrentHashMap<String, (String) -> Triple<Int, String, ByteArray?>>()
        private val requests = mutableListOf<Request>()

        fun stub(method: String, path: String, status: Int = 200, body: String, bytes: ByteArray? = null) {
            routes["$method $path"] = { Triple(status, body, bytes) }
        }

        fun requests(method: String, path: String): List<Request> =
            synchronized(requests) { requests.filter { it.method == method && it.path == path } }

        fun reset() {
            routes.clear()
            synchronized(requests) { requests.clear() }
        }
    }
}
