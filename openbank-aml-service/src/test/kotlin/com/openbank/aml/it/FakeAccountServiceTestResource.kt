// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * An account authority a test can take down and bring back, so the production rest-client and its
 * wrapper are exercised against a refused connection, a 5xx and a 404 rather than against a stub
 * of the port.
 *
 * Two listeners. The ACCOUNT one answers `GET /api/v1/accounts/{id}`: the one known account with
 * its owning party, every other id with 404. The CONTROL one stays up for the whole run; it issues
 * the client-credentials token the rest-client's filter asks for, and switches the account
 * listener between `up`, `unavailable` (503 to everything) and `refused` (listener closed, so the
 * connection itself is refused).
 *
 * Driven over HTTP, never through a static: a test resource and the test class are loaded by
 * different classloaders, so a companion field set by one is not the field the other reads.
 */
class FakeAccountServiceTestResource : QuarkusTestResourceLifecycleManager {
    private val mode = AtomicReference(UP)
    private var control: HttpServer? = null
    private var accounts: HttpServer? = null
    private var accountPort = 0

    override fun start(): Map<String, String> {
        val controlServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        controlServer.createContext("/token") {
            it.reply(OK, """{"access_token":"fake","token_type":"Bearer","expires_in":3600}""")
        }
        controlServer.createContext("/mode/") { exchange ->
            val wanted = exchange.requestURI.path.substringAfterLast('/')
            if (wanted !in setOf(UP, UNAVAILABLE, REFUSED)) {
                exchange.reply(NOT_FOUND, "")
            } else {
                switchTo(wanted)
                exchange.reply(OK, wanted)
            }
        }
        controlServer.start()
        control = controlServer
        accountPort = listen(0)
        val controlUrl = "http://127.0.0.1:${controlServer.address.port}"
        return mapOf(
            "quarkus.rest-client.account-service.url" to "http://127.0.0.1:$accountPort",
            "quarkus.oidc-client.auth-server-url" to controlUrl,
            "quarkus.oidc-client.discovery-enabled" to "false",
            "quarkus.oidc-client.token-path" to "/token",
            CONTROL_URL_KEY to controlUrl,
        )
    }

    @Synchronized
    private fun switchTo(wanted: String) {
        mode.set(wanted)
        if (wanted == REFUSED) {
            accounts?.stop(0)
            accounts = null
        } else if (accounts == null) {
            listen(accountPort)
        }
    }

    private fun listen(port: Int): Int {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        server.createContext("/api/v1/accounts/") { exchange ->
            val id = exchange.requestURI.path.substringAfterLast('/')
            when {
                mode.get() == UNAVAILABLE -> exchange.reply(SERVICE_UNAVAILABLE, "")
                id == KNOWN_ACCOUNT -> exchange.reply(OK, """{"id":"$KNOWN_ACCOUNT","partyId":"$OWNING_PARTY"}""")
                else -> exchange.reply(NOT_FOUND, "")
            }
        }
        server.start()
        accounts = server
        return server.address.port
    }

    private fun HttpExchange.reply(status: Int, body: String) {
        val bytes = body.toByteArray()
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
        close()
    }

    override fun stop() {
        accounts?.stop(0)
        control?.stop(0)
    }

    companion object {
        const val CONTROL_URL_KEY = "test.fake-account-service.control-url"
        const val KNOWN_ACCOUNT = "00000000-0000-4000-8000-0000000ac0b1"
        const val OWNING_PARTY = "00000000-0000-4000-8000-0000000bb0b1"
        const val UP = "up"
        const val UNAVAILABLE = "unavailable"
        const val REFUSED = "refused"
        private const val OK = 200
        private const val NOT_FOUND = 404
        private const val SERVICE_UNAVAILABLE = 503
    }
}
