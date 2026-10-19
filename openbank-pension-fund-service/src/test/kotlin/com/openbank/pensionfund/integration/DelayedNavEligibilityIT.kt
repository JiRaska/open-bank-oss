// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.integration

import com.openbank.pensionfund.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Publication dates, order status and transaction count through HTTP and real PostgreSQL. */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DelayedNavEligibilityIT {
    companion object {
        private val contract = UUID.randomUUID()
        private val oldDay = LocalDate.now(ZoneOffset.UTC).minusDays(2)
        private val laterDay = oldDay.plusDays(1)
        private lateinit var fund: String
        private lateinit var oldOrder: String
        private lateinit var laterOrder: String
        private lateinit var oldNav: String
        private lateinit var laterNav: String
    }

    @Test
    @Order(1)
    @TestSecurity(user = "nav-maker", roles = ["ROLE_OPERATOR"])
    fun `maker queues orders on two trade dates and calculates delayed prior-day NAV`() {
        val isin = "CZ" + UUID.randomUUID().toString().replace("-", "").take(9).uppercase() + "0"
        fund = given().contentType("application/json")
            .body(
                """{"name":"Delayed NAV test","isin":"$isin","lei":"315700ABCDEF12345678",
                    "depositaryReference":"TEST-DEP","custodyAccountReference":"TEST-CUST",
                    "currency":"CZK","riskClass":3,"mandatoryConservative":false,
                    "productRevisionId":"00000000-0000-0000-0000-000000000001","launchNavPerUnit":1}""",
            ).post("/api/v2/funds").then().statusCode(201).extract().path("id")
        oldOrder = subscribe("old-day", 100)
        laterOrder = subscribe("later-day", 200)
        // The requests above exercised the served order route. Backdate only the stored trade
        // instants to model a NAV published late without waiting two wall-clock days.
        connection().use { db ->
            db.prepareStatement("UPDATE unit_orders SET placed_at = ? WHERE id = ?").use { statement ->
                listOf(oldOrder to oldDay, laterOrder to laterDay).forEach { (id, day) ->
                    statement.setObject(1, day.atTime(12, 0).atOffset(ZoneOffset.UTC))
                    statement.setObject(2, UUID.fromString(id))
                    assertThat(statement.executeUpdate()).isEqualTo(1)
                }
            }
        }
        oldNav = calculate(oldDay, 0)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "nav-checker", roles = ["ROLE_OPERATOR"])
    fun `delayed NAV settles only the eligible order`() {
        given().post("/api/v2/navs/$oldNav/approve").then().statusCode(200)
            .body("settledOrders", org.hamcrest.Matchers.equalTo(1))
        assertRegister("SETTLED", "PENDING", 100, 1)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "nav-maker", roles = ["ROLE_OPERATOR"])
    fun `maker calculates the next eligible NAV`() {
        laterNav = calculate(laterDay, 100)
    }

    @Test
    @Order(4)
    @TestSecurity(user = "nav-checker", roles = ["ROLE_OPERATOR"])
    fun `later order settles exactly once at its eligible NAV`() {
        given().post("/api/v2/navs/$laterNav/approve").then().statusCode(200)
            .body("settledOrders", org.hamcrest.Matchers.equalTo(1))
        assertRegister("SETTLED", "SETTLED", 300, 2)
        given().post("/api/v2/navs/$laterNav/approve").then().statusCode(409)
        assertRegister("SETTLED", "SETTLED", 300, 2)
    }

    private fun subscribe(key: String, amount: Int): String = given().contentType("application/json")
        .header("Idempotency-Key", key)
        .body("""{"fundId":"$fund","type":"SUBSCRIBE","amount":$amount}""")
        .post("/api/v2/contracts/$contract/orders").then().statusCode(202).extract().path("id")

    private fun calculate(day: LocalDate, cash: Int): String = given().contentType("application/json")
        .body("""{"valuationDate":"$day","cash":$cash}""")
        .post("/api/v2/funds/$fund/navs").then().statusCode(201).extract().path("id")

    private fun assertRegister(oldStatus: String, laterStatus: String, units: Int, transactions: Int) {
        assertThat(orderStates()).containsEntry(oldOrder, oldStatus).containsEntry(laterOrder, laterStatus)
        assertThat(holdingUnits()).isEqualByComparingTo(units.toString())
        assertThat(transactionCount()).isEqualTo(transactions)
    }

    @Suppress("NestedBlockDepth") // JDBC connection, statement and result each need bounded use
    private fun orderStates(): Map<String, String> {
        connection().use { db ->
            db.prepareStatement("SELECT id, status FROM unit_orders WHERE id IN (?, ?)").use { statement ->
                statement.setObject(1, UUID.fromString(oldOrder))
                statement.setObject(2, UUID.fromString(laterOrder))
                statement.executeQuery().use { rows ->
                    val states = mutableMapOf<String, String>()
                    while (rows.next()) states[rows.getString(1)] = rows.getString(2)
                    return states
                }
            }
        }
    }

    private fun holdingUnits(): java.math.BigDecimal {
        connection().use { db ->
            db.prepareStatement("SELECT units FROM unit_holdings WHERE contract_id = ?").use { statement ->
                statement.setObject(1, contract)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    return rows.getBigDecimal(1)
                }
            }
        }
    }

    private fun transactionCount(): Int {
        connection().use { db ->
            db.prepareStatement("SELECT count(*) FROM unit_transactions WHERE contract_id = ?").use { statement ->
                statement.setObject(1, contract)
                statement.executeQuery().use { rows ->
                    assertThat(rows.next()).isTrue()
                    return rows.getInt(1)
                }
            }
        }
    }

    private fun connection() = ConfigProvider.getConfig().let { config ->
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        )
    }
}
