// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.statement.e2e

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * One JDK `HttpServer` stubbing the four services statement-service reads a period close from
 * (account-api, party-api, balance-api, transaction-api) plus document-api's template preview —
 * no new dependency, the same approach the module's pact tests take with their mock servers.
 *
 * The stub is deterministic per account id: [wireAccount] decides which accounts reconcile
 * (opening 1000.00, booked +100.00 CRDT / −25.00 DBIT, closing 1075.00) and which account is the
 * broken one (the same entries but a balance that reports 1000.00 — the fail-closed
 * reconciliation conflict the journey asserts as 409). [applyRestatementCorrection] then lands a
 * booked-data correction on the reconciling account only (+10.00 CRDT, closing 1085.00), which is
 * what the journey's restate step needs to mint a genuinely different — superseding — close.
 *
 * Everything is served from one server because the journey overrides every rest-client URL to it;
 * the routes are disjoint by path, so no port juggling is needed.
 */
class UpstreamServicesStubResource : QuarkusTestResourceLifecycleManager {

    private lateinit var server: HttpServer

    override fun start(): Map<String, String> {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            try {
                val (status, body) = route(exchange.requestURI.path, exchange.requestURI.query)
                respond(exchange, status, body)
            } catch (t: Throwable) {
                respond(exchange, 500, """{"error":"stub: ${t.message}"}""")
            } finally {
                exchange.close()
            }
        }
        server.start()
        val base = "http://localhost:${server.address.port}"
        return mapOf(
            "quarkus.rest-client.account-api.url" to base,
            "quarkus.rest-client.party-api.url" to base,
            "quarkus.rest-client.balance-api.url" to base,
            "quarkus.rest-client.transaction-api.url" to base,
            "quarkus.rest-client.document-api.url" to base,
        )
    }

    override fun stop() {
        server.stop(0)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun route(path: String, query: String?): Pair<Int, String> = when {
        path.matches(Regex("/api/v1/accounts/[^/]+/pockets")) ->
            200 to """{"pockets":[{"currencyCode":"CZK","status":"ACTIVE"}]}"""

        path.matches(Regex("/api/v1/accounts/[^/]+")) -> {
            val id = path.substringAfter("/api/v1/accounts/")
            200 to """{"id":"$id","accountNumber":"$IBAN","partyId":"$PARTY_ID","currencies":["CZK"]}"""
        }

        path.matches(Regex("/api/v1/parties/[^/]+")) ->
            200 to """{"id":"$PARTY_ID","legalName":"$HOLDER_NAME"}"""

        path.matches(Regex("/api/v1/balances/[^/]+/[^/]+")) -> balanceRoute(path, query)

        path == "/api/v1/transactions/search" -> transactionsRoute(query)

        path == "/api/v1/documents/templates" ->
            200 to TEMPLATE_LIST_JSON

        path == "/api/v1/documents/templates/preview" ->
            200 to """{"renderedHtml":"<html><body><h1>$HOLDER_NAME</h1><p>statement</p></body></html>"}"""

        else -> 404 to """{"error":"stub has no route for $path"}"""
    }

    private fun balanceRoute(path: String, query: String?): Pair<Int, String> {
        val account = path.substringAfter("/api/v1/balances/").substringBefore("/")
        val asOf = query?.split("&")?.firstOrNull { it.startsWith("asOf=") }?.substringAfter("asOf=")
        val booked = closingBalanceFor(account, asOf)
        val body =
            """{"accountId":"$account","currency":"CZK","bookedAmount":$booked,"availableAmount":$booked}"""
        return 200 to body
    }

    private fun closingBalanceFor(account: String, asOf: String?): String = when {
        account != reconcilingAccount.toString() || asOf != "2026-01-31" -> "1000.00"
        restateCorrectionApplied -> "1085.00"
        else -> "1075.00"
    }

    private fun transactionsRoute(query: String?): Pair<Int, String> {
        val account = queryParam(query, "accountId")
        val corrected = restateCorrectionApplied && account == reconcilingAccount.toString()
        val extra = if (corrected) ",${lateCorrectionJson(account)}" else ""
        val count = if (corrected) 3 else 2
        return 200 to """
            {
              "data": [${entriesJson(account)}$extra],
              "count": $count,
              "limit": 200,
              "offset": 0
            }
        """.trimIndent()
    }

    private fun entriesJson(account: String): String = """
        {
          "referenceNumber": "TX-E2E-1",
          "amount": 100.00,
          "currencyCode": "CZK",
          "type": "TRANSFER",
          "sourceAccountId": "${UUID.randomUUID()}",
          "targetAccountId": "$account",
          "bookingDate": "2026-01-15",
          "valueDate": "2026-01-15",
          "description": "E2E salary",
          "status": "COMPLETED"
        },
        {
          "referenceNumber": "TX-E2E-2",
          "amount": 25.00,
          "currencyCode": "CZK",
          "type": "FEE",
          "sourceAccountId": "$account",
          "targetAccountId": "${UUID.randomUUID()}",
          "bookingDate": "2026-01-20",
          "valueDate": "2026-01-20",
          "description": "E2E fee",
          "status": "COMPLETED"
        }
    """.trimIndent()

    private fun lateCorrectionJson(account: String): String = """
        {
          "referenceNumber": "TX-E2E-3",
          "amount": 10.00,
          "currencyCode": "CZK",
          "type": "TRANSFER",
          "sourceAccountId": "${UUID.randomUUID()}",
          "targetAccountId": "$account",
          "bookingDate": "2026-01-25",
          "valueDate": "2026-01-25",
          "description": "E2E late correction",
          "status": "COMPLETED"
        }
    """.trimIndent()

    private fun queryParam(query: String?, name: String): String =
        query?.split("&")?.firstOrNull { it.startsWith("$name=") }?.substringAfter("$name=")?.ifBlank { null }
            ?: reconcilingAccount.toString()

    companion object {
        const val IBAN = "CZ6508000000192000145399"
        const val HOLDER_NAME = "E2E Statement Holder"
        private const val TEMPLATE_LIST_JSON =
            """[{"code":"MESICNI_VYPIS_CS","version":"1","bodyHtml":"<h1>{{party.name}}</h1>","locale":"cs","status":"PUBLISHED"}]"""

        val PARTY_ID: UUID = UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa")

        /** Account whose balances reconcile with the stubbed entries. */
        var reconcilingAccount: UUID = UUID.randomUUID()
            private set

        /**
         * When true, the reconciling account's booked data carries one more credit (+10.00) and
         * the 2026-01-31 closing balance reports 1085.00 — the correction the restate journey
         * travels. Volatile: written from the test thread, read from the stub's HTTP threads.
         */
        @Volatile
        private var restateCorrectionApplied: Boolean = false

        val outboundCalls = AtomicInteger()

        /** Wire the reconciling account for the current journey run. */
        fun wireAccount(accountId: UUID) {
            reconcilingAccount = accountId
        }

        /** Land the booked-data correction that makes a restate mint a genuinely new close. */
        fun applyRestatementCorrection() {
            restateCorrectionApplied = true
        }
    }
}
