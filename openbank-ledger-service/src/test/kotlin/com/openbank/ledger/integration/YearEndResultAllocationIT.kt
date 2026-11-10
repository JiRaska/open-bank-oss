// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.integration

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.path.json.JsonPath
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.util.UUID

/**
 * Real HTTP/Postgres proof of the year-end result allocation (ADR-0096 D3): the closing journal
 * zeroes the year's income and expense into retained earnings, a re-run posts nothing, a late
 * posting is allocated as a residual only, and the allocated year can then be drafted and attested.
 */
@QuarkusTest
@QuarkusTestResource(value = PostgresTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(YearEndResultAllocationIT.IsolatedPersistenceProfile::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class YearEndResultAllocationIT {

    class IsolatedPersistenceProfile : QuarkusTestProfile

    @Test
    @Order(1)
    @TestSecurity(user = "rea-maker", roles = ["ROLE_OPERATOR"])
    fun `maker books a year of income and expense`() {
        post(INCOME, credit = true, amount = "1000")
        post(EXPENSE, credit = false, amount = "300")
        val preview = preview()
        assertThat(preview.getBoolean("posted")).isFalse()
        assertThat(BigDecimal(preview.getString("netResult"))).isEqualByComparingTo("-700")
    }

    @Test
    @Order(2)
    @TestSecurity(user = "rea-maker", roles = ["ROLE_OPERATOR"])
    fun `allocation posts the closing journal and zeroes the P and L`() {
        val result = allocate()
        assertThat(result.getBoolean("posted")).isTrue()
        assertThat(result.getString("journalId")).isNotBlank()
        val journal = given().accept("application/json").`when`()
            .get("/api/v1/journals/${result.getString("journalId")}")
            .then().statusCode(200).extract().jsonPath()
        assertThat(journal.getString("entryDate")).isEqualTo("$YEAR-12-31")

        val tb = trialBalance()
        assertThat(net(tb, "4100")).isEqualByComparingTo("0")
        assertThat(net(tb, "5100")).isEqualByComparingTo("0")
        assertThat(net(tb, "6020")).isEqualByComparingTo("-700")
        assertThat(preview().getList<Any>("legs")).isEmpty()
    }

    @Test
    @Order(3)
    @TestSecurity(user = "rea-maker", roles = ["ROLE_OPERATOR"])
    fun `a re-run over an unchanged ledger posts nothing`() {
        val again = allocate()
        assertThat(again.getBoolean("posted")).isFalse()
        assertThat(net(trialBalance(), "6020")).isEqualByComparingTo("-700")
    }

    @Test
    @Order(4)
    @TestSecurity(user = "rea-maker", roles = ["ROLE_OPERATOR"])
    fun `a late posting is allocated as a residual, never the whole result twice`() {
        post(INCOME, credit = true, amount = "40")
        assertThat(allocate().getBoolean("posted")).isTrue()
        val tb = trialBalance()
        assertThat(net(tb, "4100")).isEqualByComparingTo("0")
        assertThat(net(tb, "6020")).isEqualByComparingTo("-740")
        given().contentType("application/json").`when`().post("/api/v1/ledger/close/$YEAR")
            .then().statusCode(200)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "rea-checker", roles = ["ROLE_OPERATOR"])
    fun `the allocated year attests, after which allocation is refused`() {
        given().contentType("application/json").`when`().post("/api/v1/ledger/close/$YEAR/attest")
            .then().statusCode(200)
        given().contentType("application/json").`when`().post("/api/v1/ledger/close/$YEAR/result-allocation")
            .then().statusCode(409)
    }

    @Test
    @Order(6)
    @TestSecurity(user = "rea-maker", roles = ["ROLE_OPERATOR"])
    fun `the current, unfinished year is refused`() {
        val thisYear = java.time.LocalDate.now().year
        given().contentType("application/json").`when`().post("/api/v1/ledger/close/$thisYear/result-allocation")
            .then().statusCode(409)
    }

    @Test
    @Order(7)
    @TestSecurity(user = "rea-viewer", roles = ["ROLE_VIEWER"])
    fun `a viewer can preview but cannot allocate`() {
        preview()
        given().contentType("application/json").`when`().post("/api/v1/ledger/close/$YEAR/result-allocation")
            .then().statusCode(403)
    }

    private fun preview(): JsonPath = given().accept("application/json").`when`()
        .get("/api/v1/ledger/close/$YEAR/result-allocation")
        .then().statusCode(200).extract().jsonPath()

    private fun allocate(): JsonPath = given().contentType("application/json").`when`()
        .post("/api/v1/ledger/close/$YEAR/result-allocation")
        .then().statusCode(200).extract().jsonPath()

    private fun trialBalance(): JsonPath = given().accept("application/json").`when`()
        .get("/api/v1/ledger/close/trial-balance?fiscalYear=$YEAR")
        .then().statusCode(200).extract().jsonPath()

    private fun net(tb: JsonPath, code: String): BigDecimal {
        val lines = tb.getList<Map<String, Any>>("sections.lines").flatten()
        val matching = lines.filter { it["code"] == code }
        return matching.fold(BigDecimal.ZERO) { acc, l ->
            acc.add(BigDecimal(l.getValue("totalDebit").toString())).subtract(BigDecimal(l.getValue("totalCredit").toString()))
        }
    }

    private fun post(account: String, credit: Boolean, amount: String) {
        val (cashSide, pnlSide) = if (credit) "DEBIT" to "CREDIT" else "CREDIT" to "DEBIT"
        val date = "$YEAR-06-15"
        val journal = """
            {"idempotencyKey":"${UUID.randomUUID()}","transactionId":"${UUID.randomUUID()}",
             "entryDate":"$date","valueDate":"$date","description":"Result allocation IT",
             "createdBy":"00000000-0000-0000-0000-000000000704","lines":[
             {"glAccountId":"$CASH","side":"$cashSide","amount":"$amount","currencyCode":"CZK","baseAmount":"$amount","baseCurrencyCode":"CZK"},
             {"glAccountId":"$account","side":"$pnlSide","amount":"$amount","currencyCode":"CZK","baseAmount":"$amount","baseCurrencyCode":"CZK"}]}
        """.trimIndent()
        given().contentType("application/json").body(journal).`when`().post("/api/v1/journals")
            .then().statusCode(201)
    }

    private companion object {
        const val YEAR = 2019
        const val CASH = "a0000000-0000-0000-0000-000000000001"
        const val INCOME = "a0000000-0000-0000-0000-000000004100"
        const val EXPENSE = "a0000000-0000-0000-0000-000000005100"
    }
}
