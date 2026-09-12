// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.wealth.integration

import com.openbank.wealth.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.UUID

/**
 * Drives the real HTTP endpoint against a real Postgres, then reads `wealth_outbox` with plain
 * JDBC. That combination is the ONLY way to prove the holding row and its event commit together:
 * a unit test with a mocked repository cannot tell which publisher a use case called, and a
 * reactive Panache repository cannot even be called from a bare `@QuarkusTest` thread — there is
 * no Vert.x context outside a real request, so `runBlocking { repo.save(...) }` throws
 * `No current Vertx context found`.
 *
 * It also answers a question no unit test can: whether the route is REGISTERED. A test that calls
 * the resource class directly passes just as happily against a `@Path` that bound to the wrong
 * declaration and a pod that 404s on every call (#3371).
 */
@QuarkusTest
@QuarkusTestResource(WealthHoldingApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class WealthHoldingApiIT {

    class InMemoryKafkaResource : QuarkusTestResourceLifecycleManager {
        override fun start(): Map<String, String> =
            InMemoryConnector.switchOutgoingChannelsToInMemory("wealth-events-out")

        override fun stop() = InMemoryConnector.clear()
    }

    private val party: UUID = UUID.randomUUID()

    private fun declareBody(reference: String?) = """
        {
          "holdingType": "COLLECTIBLE",
          "label": "Omega Speedmaster 1969",
          "valuation": {
            "amount": 250000.00,
            "currency": "CZK",
            "valuedAt": "2026-09-01",
            "source": "CUSTOMER_DECLARED"
          },
          "ownershipShare": 1,
          ${if (reference != null) "\"externalReference\": \"$reference\"," else ""}
          "documentIds": []
        }
    """.trimIndent()

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `declaring writes the holding row and its outbox event in one transaction`() {
        val holdingId = given()
            .contentType("application/json")
            .header("X-Customer-Party-Id", party.toString())
            .body(declareBody("SN-1969-042"))
            .`when`().post("/api/v1/holdings")
            .then().statusCode(201)
            .body("valuationSource", equalTo("CUSTOMER_DECLARED"))
            .body("status", equalTo("ACTIVE"))
            .extract().path<String>("holdingId")

        val rows = readOutbox(UUID.fromString(holdingId))
        assertThat(rows).hasSize(1)
        val (eventType, status) = rows.single()
        assertThat(eventType).isEqualTo("wealth.holding.declared.v1")
        assertThat(status).isIn("PENDING", "DISPATCHING", "SENT")
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `a replayed declare on the same natural key creates no second row`() {
        val reference = "VIN-${UUID.randomUUID()}"
        val first = declare(reference)
        val second = declare(reference)

        assertThat(second).isEqualTo(first)
        assertThat(countHoldings(UUID.fromString(first))).isEqualTo(1)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `an absent party header is a 400, not a 500`() {
        // The header is declared nullable and checked with requireNotNull for exactly this.
        // A non-nullable declaration makes JAX-RS inject null and the request 500s before the
        // body's guard ever runs — the case the guard exists for (#3104).
        given()
            .contentType("application/json")
            .body(declareBody(null))
            .`when`().post("/api/v1/holdings")
            .then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `an unknown holding reads as 404`() {
        given()
            .header("X-Customer-Party-Id", party.toString())
            .`when`().get("/api/v1/holdings/${UUID.randomUUID()}")
            .then().statusCode(404)
    }

    @Test
    @TestSecurity(user = "edge", roles = ["ROLE_API"])
    fun `withdrawing removes the holding from the party's list and emits the event`() {
        val id = declare("SN-${UUID.randomUUID()}")

        given()
            .header("X-Customer-Party-Id", party.toString())
            .`when`().delete("/api/v1/holdings/$id")
            .then().statusCode(200)
            .body("status", equalTo("WITHDRAWN"))

        val types = readOutbox(UUID.fromString(id)).map { it.first }
        assertThat(types).contains("wealth.holding.withdrawn.v1")
    }

    private fun declare(reference: String): String = given()
        .contentType("application/json")
        .header("X-Customer-Party-Id", party.toString())
        .body(declareBody(reference))
        .`when`().post("/api/v1/holdings")
        .then().statusCode(201)
        .extract().path("holdingId")

    private fun jdbcUrl() = ConfigProvider.getConfig().getValue("quarkus.datasource.jdbc.url", String::class.java)

    /** One place that opens a connection, so the two readers below stay flat. */
    private fun <T> query(sql: String, id: UUID, read: (ResultSet) -> T): T =
        DriverManager.getConnection(jdbcUrl(), "openbank", "openbank_secret").use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use(read)
            }
        }

    private fun readOutbox(aggregateId: UUID): List<Pair<String, String>> =
        query("select event_type, status from wealth_outbox where aggregate_id = ?", aggregateId) { rs ->
            generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }.toList()
        }

    private fun countHoldings(holdingId: UUID): Int =
        query("select count(*) from declared_holdings where holding_id = ?", holdingId) { rs ->
            if (rs.next()) rs.getInt(1) else 0
        }
}
