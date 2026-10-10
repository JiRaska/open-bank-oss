// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
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
 * A balance sheet is a STOCK, and a frozen month's evidence is that month's FLOW (#12499).
 *
 * Two months are posted and frozen through HTTP as two principals, then the endpoint
 * finrep-service reads for FINREP/COREP ([FINREP_FROZEN_BALANCE_PATH], the path pinned by its
 * `LedgerRestClient` and the committed pact) must answer the month-2 balance as opening +
 * movements. Before #12499 finrep read `frozen-trial-balance`, which answers 300 here instead of
 * 1300 — every balance-sheet cell understated by every earlier month.
 *
 * Dates are in 2001 so no other IT sharing this database posts before them: the closing balance
 * is checked against the journal's cumulative balance, which counts everything up to month end.
 */
@QuarkusTest
@QuarkusTestResource(com.openbank.ledger.it.PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ClosedPeriodClosingBalanceIT {

    private companion object {
        const val FINREP_FROZEN_BALANCE_PATH = "frozen-closing-balance"
        const val CASH = "a0000000-0000-0000-0000-000000000001"
        const val DEPOSITS = "a0000000-0000-0000-0000-000000000002"
        const val CASH_CODE = "1100"
        const val DEPOSITS_CODE = "2100"
        const val JANUARY = "2001-01-31"
        const val FEBRUARY = "2001-02-28"
        const val MARCH = "2001-03-31"
        const val APRIL = "2001-04-30"
    }

    @Test
    @Order(1)
    @TestSecurity(user = "closing-maker", roles = ["ROLE_OPERATOR"])
    fun `maker posts two months and drafts both closes`() {
        post("2001-01-15", "1000.00")
        post("2001-02-10", "300.00")
        draft(JANUARY)
        draft(FEBRUARY)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "closing-checker", roles = ["ROLE_OPERATOR"])
    fun `checker freezes both months`() {
        freeze(JANUARY)
        freeze(FEBRUARY)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `the frozen period trial balance is that month's movements only`() {
        val movements = read("frozen-trial-balance", FEBRUARY, 200)
        assertThat(net(movements, CASH_CODE)).isEqualByComparingTo("300.00")
    }

    @Test
    @Order(4)
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `the balance finrep reads for month 2 is opening plus movements`() {
        val balance = read(FINREP_FROZEN_BALANCE_PATH, FEBRUARY, 200)
        assertThat(net(balance, CASH_CODE)).isEqualByComparingTo("1300.00")
        assertThat(net(balance, DEPOSITS_CODE)).isEqualByComparingTo("-1300.00")
        assertThat(balance.getBoolean("balanced")).isTrue()
        // Month 1's own closing balance is unchanged by month 2.
        assertThat(net(read(FINREP_FROZEN_BALANCE_PATH, JANUARY, 200), CASH_CODE)).isEqualByComparingTo("1000.00")
        // The live preview gives the same stock.
        assertThat(net(read("closing-balance", FEBRUARY, 200), CASH_CODE)).isEqualByComparingTo("1300.00")
    }

    @Test
    @Order(5)
    @TestSecurity(user = "closing-maker", roles = ["ROLE_OPERATOR"])
    fun `maker posts March, which is never frozen, and drafts April`() {
        post("2001-03-05", "50.00")
        post("2001-04-05", "7.00")
        draft(APRIL)
    }

    @Test
    @Order(6)
    @TestSecurity(user = "closing-checker", roles = ["ROLE_OPERATOR"])
    fun `checker freezes April`() {
        freeze(APRIL)
    }

    @Test
    @Order(7)
    @TestSecurity(user = "viewer", roles = ["ROLE_VIEWER"])
    fun `a month with postings missing from the evidence chain fails closed`() {
        read(FINREP_FROZEN_BALANCE_PATH, APRIL, 409)
        // The month that is not frozen has no attested balance either.
        read(FINREP_FROZEN_BALANCE_PATH, MARCH, 409)
        // A closing balance is a MONTH concept.
        read(FINREP_FROZEN_BALANCE_PATH, FEBRUARY, 422, type = "QUARTER")
    }

    private fun post(entryDate: String, amount: String) {
        val journal = """
            {"idempotencyKey":"${UUID.randomUUID()}","transactionId":"${UUID.randomUUID()}",
             "entryDate":"$entryDate","valueDate":"$entryDate","description":"Closing balance IT",
             "createdBy":"00000000-0000-0000-0000-000000000703","lines":[
             {"glAccountId":"$CASH","side":"DEBIT","amount":"$amount","currencyCode":"CZK","baseAmount":"$amount","baseCurrencyCode":"CZK"},
             {"glAccountId":"$DEPOSITS","side":"CREDIT","amount":"$amount","currencyCode":"CZK","baseAmount":"$amount","baseCurrencyCode":"CZK"}]}
        """.trimIndent()
        given().contentType("application/json").body(journal).`when`().post("/api/v1/journals")
            .then().statusCode(201)
    }

    private fun draft(date: String) {
        given().contentType("application/json").`when`().post("/api/v1/ledger/periods/MONTH/$date")
            .then().statusCode(200)
    }

    private fun freeze(date: String) {
        given().contentType("application/json").`when`().post("/api/v1/ledger/periods/MONTH/$date/freeze")
            .then().statusCode(200)
    }

    private fun read(path: String, date: String, status: Int, type: String = "MONTH"): JsonPath =
        given().accept("application/json").`when`().get("/api/v1/ledger/periods/$type/$date/$path")
            .then().statusCode(status).extract().jsonPath()

    private fun net(body: JsonPath, code: String): BigDecimal {
        val nets = body.getList<Any>("lines.findAll { it.code == '$code' }.net").map { BigDecimal(it.toString()) }
        assertThat(nets).describedAs("lines for $code in %s", body.prettify()).hasSize(1)
        return nets.single()
    }
}
