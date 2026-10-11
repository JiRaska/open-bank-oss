// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.integration

import com.openbank.pensionfund.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestSecurity(user = "reservation-operator", roles = ["ROLE_OPERATOR", "ROLE_API"])
class OutgoingReservationIT {
    @Test
    fun `concurrent outgoing orders reserve units under a database lock and replay once`() {
        val contract = UUID.randomUUID()
        val fund = createFund()
        val responses = concurrentReservations(contract, fund, listOf("reserve-1", "reserve-2"))
        assertThat(responses.map { it.statusCode }).containsExactlyInAnyOrder(202, 409)
        val winner = responses.indexOfFirst { it.statusCode == 202 } + 1
        val accepted = responses[winner - 1].jsonPath().getString("id")
        val replay = redeem(contract, fund, "reserve-$winner")
        assertThat(replay.statusCode).isEqualTo(202)
        assertThat(replay.jsonPath().getString("id")).isEqualTo(accepted)
        assertSingleReservation(contract)
    }

    @Test
    fun `simultaneous identical keys return the same reservation without consuming units twice`() {
        val contract = UUID.randomUUID()
        val fund = createFund()
        val responses = concurrentReservations(contract, fund, listOf("same-key", "same-key"))
        assertThat(responses.map { it.statusCode }).containsExactly(202, 202)
        val orderIds = responses.map { it.jsonPath().getString("id") }
        assertThat(orderIds[0]).isNotBlank()
        assertThat(orderIds[1]).isEqualTo(orderIds[0])
        assertSingleReservation(contract)
    }

    private fun concurrentReservations(contract: UUID, fund: UUID, keys: List<String>): List<Response> =
        connection().use { db ->
            seedAndLockHolding(db, contract, fund)
            runContenders(db, contract, fund, keys)
        }

    private fun runContenders(db: Connection, contract: UUID, fund: UUID, keys: List<String>): List<Response> {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val requests = keys.map { key -> executor.submit<Response> { redeem(contract, fund, key) } }
            // Both real HTTP requests must reach Postgres and wait behind the SAME holding lock.
            // Without the reservation transaction they return while this lock is still held.
            awaitBlockedReservations(db)
            assertThat(requests).allMatch { !it.isDone }
            db.commit()
            return requests.map { it.get(15, TimeUnit.SECONDS) }
        } finally {
            db.rollback()
            executor.shutdownNow()
        }
    }

    private fun seedAndLockHolding(db: Connection, contract: UUID, fund: UUID) {
        val holding = UUID.nameUUIDFromBytes("holding:$contract:$fund".toByteArray(StandardCharsets.UTF_8))
        db.prepareStatement("INSERT INTO unit_holdings (id, contract_id, fund_id, units) VALUES (?, ?, ?, 100)")
            .use { statement ->
                statement.setObject(1, holding)
                statement.setObject(2, contract)
                statement.setObject(3, fund)
                statement.executeUpdate()
            }
        db.autoCommit = false
        db.prepareStatement("SELECT id FROM unit_holdings WHERE id = ? FOR UPDATE").use { statement ->
            statement.setObject(1, holding)
            statement.executeQuery().use { assertThat(it.next()).isTrue() }
        }
    }

    private fun assertSingleReservation(contract: UUID) = connection().use { db ->
        db.prepareStatement("SELECT count(*), sum(units) FROM unit_orders WHERE contract_id = ?").use { statement ->
            statement.setObject(1, contract)
            statement.executeQuery().use { rows ->
                assertThat(rows.next()).isTrue()
                assertThat(rows.getInt(1)).isEqualTo(1)
                assertThat(rows.getBigDecimal(2)).isEqualByComparingTo("60")
            }
        }
    }

    private fun awaitBlockedReservations(db: Connection) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var waiting = 0
        while (System.nanoTime() < deadline) {
            db.createStatement().use { statement ->
                statement.executeQuery(
                    """SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'
                        AND query LIKE '%unit_holdings%' AND query LIKE '%for%update%'
                    """.trimIndent(),
                ).use { rows ->
                    rows.next()
                    waiting = rows.getInt(1)
                }
            }
            if (waiting >= 2) return
            // Refresh the statistics snapshot while retaining the holding's row lock.
            db.createStatement().use { it.execute("SELECT pg_stat_clear_snapshot()") }
            Thread.sleep(25)
        }
        assertThat(waiting).describedAs("two outgoing requests blocked on the holding row").isGreaterThanOrEqualTo(2)
    }

    private fun redeem(contract: UUID, fund: UUID, key: String): Response = given().contentType("application/json")
        .header("Idempotency-Key", key)
        .body("""{"fundId":"$fund","type":"REDEEM","units":60}""")
        .post("/api/v1/contracts/$contract/orders")

    private fun createFund(): UUID {
        val isin = "RS" + UUID.randomUUID().toString().replace("-", "").take(9).uppercase() + "0"
        val id = given().contentType("application/json")
            .body(
                """{"name":"Reservation test","isin":"$isin","lei":"315700ABCDEF12345678",
                    "depositaryReference":"TEST-DEP","custodyAccountReference":"TEST-CUST",
                    "currency":"CZK","riskClass":3,"mandatoryConservative":false,
                    "managementFeeRate":0,"launchNavPerUnit":1}
                """.trimIndent(),
            ).post("/api/v1/funds").then().statusCode(201).extract().path<String>("id")
        return UUID.fromString(id)
    }

    private fun connection(): Connection {
        val config = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }
}
