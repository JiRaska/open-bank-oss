// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.analytics.contract

import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junitsupport.State
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTestProfile
import io.restassured.RestAssured
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class CreditProfilePactTestProfile : QuarkusTestProfile {
    override fun disableGlobalTestResources() = true

    override fun testResources() = listOf(QuarkusTestProfile.TestResourceEntry(CreditProfilePactResource::class.java))
}

/** Serves synthetic ClickHouse 26.8 JSONEachRow responses on loopback. Quarkus uses its real warehouse HTTP client. */
class CreditProfilePactResource : QuarkusTestResourceLifecycleManager {
    private var server: HttpServer? = null

    override fun start(): Map<String, String> {
        val warehouse = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = warehouse
        warehouse.createContext("/", ::respond)
        warehouse.start()
        return InMemoryConnector.switchIncomingChannelsToInMemory("analytics-events-in") + mapOf(
            "openbank.analytics.clickhouse.url" to "http://127.0.0.1:${warehouse.address.port}",
            "quarkus.kafka.devservices.enabled" to "false",
            "quarkus.scheduler.enabled" to "false",
        )
    }

    override fun stop() {
        server?.stop(0)
        InMemoryConnector.clear()
    }

    private fun respond(exchange: HttpExchange) = exchange.use {
        val rawQuery = exchange.requestURI.rawQuery.orEmpty().substringAfter("&query=", "")
        val sql = URLDecoder.decode(rawQuery, StandardCharsets.UTF_8)
        val party = sql.substringAfter("WHERE party_id = '", "").substringBefore("'")
        val fixture = ROWS[party]
        val expectedQuery = exchange.requestMethod == "POST" &&
            sql.contains("FROM openbank_analytics.gold_party_credit_profile") &&
            sql.endsWith("FORMAT JSONEachRow")
        val body = (fixture ?: "").toByteArray(StandardCharsets.UTF_8)
        if (expectedQuery && fixture != null) {
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
        } else {
            exchange.sendResponseHeaders(400, -1)
        }
    }

    companion object {
        const val OBSERVED_PARTY = "30000000-0000-0000-0000-000000000101"
        const val UNOBSERVED_PARTY = "30000000-0000-0000-0000-000000000102"
        private val ROWS = mapOf(
            OBSERVED_PARTY to
                """
                {"months_observed":4,"income_monthly":23456.78,"outflow_monthly":13456.78,
                 "net_monthly":10000,"volatility_ratio":0.125,"movements_observed":12}
                """.trimIndent().replace("\n", " "),
            UNOBSERVED_PARTY to "",
        )
    }
}

class CreditProfilePactStates {
    @State("a synthetic party has four observed months", "a synthetic party has no observed months")
    fun syntheticCreditProfiles() {
        // Both immutable fixtures are installed by the loopback warehouse resource before Quarkus starts.
    }
}

fun configureCreditProfilePact(context: PactVerificationContext) {
    context.target = HttpTestTarget("127.0.0.1", RestAssured.port)
    context.addStateChangeHandlers(CreditProfilePactStates())
}
