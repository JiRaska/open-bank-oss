// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.e2e

import com.openbank.libs.testing.evidence.TestInfrastructureEvidence
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stands in for the two ADR-0171 §4 name-lookup hops (account-service, party-service) with a JDK
 * `com.sun.net.httpserver` — no new test dependency, and the routes it serves are the LITERAL
 * wire contract pinned by `AccountIbanLookupPactConsumerTest` / `PartyNameLookupPactConsumerTest`,
 * not a reflection off the client (a mock that answers whatever the client asks proves nothing).
 *
 * The counter is load-bearing for the journey: a non-domestic IBAN must answer
 * `no_scheme_connectivity` WITHOUT any outbound hop, so the test asserts the counter did not move.
 */
class NameLookupHttpServerResource : QuarkusTestResourceLifecycleManager {

    private lateinit var server: HttpServer

    override fun start(): Map<String, String> {
        val holderByPartyId = ConcurrentHashMap<String, String>()
        holderByPartyId[PARTY_ID] = HOLDER_NAME
        val holderByIban = ConcurrentHashMap<String, String>()
        holderByIban[KNOWN_IBAN] = PARTY_ID

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/accounts/iban/") { exchange ->
                outboundCalls.incrementAndGet()
                val iban = exchange.requestURI.path.removePrefix("/api/v1/accounts/iban/")
                val partyId = holderByIban[iban]
                if (partyId == null) {
                    respond(exchange, 404, """{"error":"account not found"}""")
                } else {
                    respond(
                        exchange,
                        200,
                        """{"id":"$ACCOUNT_ID","partyId":"$partyId","status":"ACTIVE"}""",
                    )
                }
            }
            createContext("/api/v1/parties/") { exchange ->
                outboundCalls.incrementAndGet()
                val id = exchange.requestURI.path.removePrefix("/api/v1/parties/")
                val holder = holderByPartyId[id]
                if (holder == null) {
                    respond(exchange, 404, """{"error":"party not found"}""")
                } else {
                    respond(exchange, 200, """{"legalName":"$holder"}""")
                }
            }
            start()
        }
        TestInfrastructureEvidence.record("name-lookup-http", "jdk-httpserver", "started")
        val base = "http://127.0.0.1:${server.address.port}"
        return mapOf(
            "quarkus.rest-client.account-service.url" to base,
            "quarkus.rest-client.party-service.url" to base,
        )
    }

    override fun stop() {
        server.stop(0)
        TestInfrastructureEvidence.record("name-lookup-http", "jdk-httpserver", "stopped")
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, body.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(body.toByteArray()) }
    }

    companion object {
        const val KNOWN_IBAN = "CZ6508000000192000145399"
        const val UNKNOWN_IBAN = "CZ6508000000192000145981"
        const val ACCOUNT_ID = "11111111-2222-4333-8444-555555555555"
        const val PARTY_ID = "66666666-7777-4888-8999-aaaaaaaaaaaa"
        const val HOLDER_NAME = "Jiřina Nováčková"
        val outboundCalls = AtomicInteger()
    }
}
