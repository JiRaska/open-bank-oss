// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.Executors

/** Real HTTP and Postgres advisory lock: two pods cannot approve placements past one cash balance. */
@QuarkusTest
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TreasuryFundingIT {
    @Inject
    lateinit var ledger: FakeLedgerRead

    private val today = AccountingClock.bank(Clock.systemUTC()).today()
    private val exactDate: LocalDate = today.plusDays(180)
    private val concurrentDate: LocalDate = today.plusDays(181)

    private fun action(id: String, verb: String) = given()
        .contentType("application/json")
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .`when`().post("/api/v1/treasury/deals/$id/$verb")

    private fun submitted(currency: String, amount: String, date: LocalDate): String {
        val id: String = given().contentType("application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .body(
                """{"product":"MM_PLACEMENT","counterpartyId":"SIMBK-A","currency":"$currency",
                   "principal":$amount,"rate":4.00,"valueDate":"$date","maturityDate":"${date.plusDays(7)}"}""",
            )
            .`when`().post("/api/v1/treasury/deals").then().statusCode(201).extract().path("dealId")
        action(id, "submit").then().statusCode(200)
        return id
    }

    /** Other IT classes share this test application; fund their existing commitments as setup. */
    private fun existingReservations(currency: String, date: LocalDate): BigDecimal {
        val config = ConfigProvider.getConfig()
        DriverManager.getConnection(
            config.getValue("quarkus.datasource.jdbc.url", String::class.java),
            config.getValue("quarkus.datasource.username", String::class.java),
            config.getValue("quarkus.datasource.password", String::class.java),
        ).use { connection ->
            connection.prepareStatement(
                """select coalesce(sum(principal), 0) from deals
                   where currency = ? and value_date <= ?
                   and state in ('BOOKED', 'CONFIRMED')
                   and product in ('MM_PLACEMENT', 'CNB_DEPOSIT_FACILITY')""",
            ).use { query ->
                query.setString(1, currency)
                query.setObject(2, date)
                query.executeQuery().use { rows ->
                    check(rows.next())
                    return rows.getBigDecimal(1)
                }
            }
        }
    }

    @Test
    @Order(1)
    @TestSecurity(user = "funding.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun prepare() {
        ledger.reset()
        ledger.defaultBalance = BigDecimal("1000000000000.00")
        exactId = submitted("CZK", "100000.00", exactDate)
        oneCentId = submitted("CZK", "0.01", exactDate)
        concurrentIds = listOf(
            submitted("EUR", "10000.00", concurrentDate),
            submitted("EUR", "10000.00", concurrentDate),
        )
    }

    @Test
    @Order(2)
    @TestSecurity(user = "funding.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `exact balance passes and one minor unit over fails without a booked event`() {
        ledger.balances[Triple("1001", "CZK", exactDate)] =
            existingReservations("CZK", exactDate) + BigDecimal("100000.00")
        action(exactId, "approve").then().statusCode(200)
        action(oneCentId, "approve").then().statusCode(422)
            .body("error", org.hamcrest.Matchers.equalTo("TREASURY_FUNDING_EXCEEDED"))
        assertThat(
            given().`when`().get("/api/v1/treasury/deals/$oneCentId").then().statusCode(200)
                .extract().path<String>("state"),
        ).isEqualTo("PENDING_APPROVAL")
    }

    @Test
    @Order(3)
    @TestSecurity(user = "funding.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `concurrent placements cannot jointly overdraw the same account`() {
        ledger.balances[Triple("1002", "EUR", concurrentDate)] =
            existingReservations("EUR", concurrentDate) + BigDecimal("10000.00")
        Executors.newFixedThreadPool(2).use { pool ->
            val results = concurrentIds.map { id -> pool.submit<Int> { action(id, "approve").statusCode } }
                .map { it.get() }
            assertThat(results.count { it == 200 }).isEqualTo(1)
            assertThat(results.filter { it != 200 }).allSatisfy { assertThat(it).isIn(409, 422) }
            val booked = concurrentIds.count { id ->
                given().`when`().get("/api/v1/treasury/deals/$id").then().statusCode(200)
                    .extract().path<String>("state") == "BOOKED"
            }
            assertThat(booked).isEqualTo(1)
        }
    }

    private companion object {
        lateinit var exactId: String
        lateinit var oneCentId: String
        lateinit var concurrentIds: List<String>
    }
}
