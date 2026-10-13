// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

package com.openbank.ledger.integration

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.util.UUID

/** Real HTTP/Postgres proof of frozen current-year flows and calendar-year isolation. */
@QuarkusTest
@QuarkusTestResource(
    value = com.openbank.ledger.it.PostgresTestResource::class,
    restrictToAnnotatedClass = true,
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FrozenYearToDateTrialBalanceIT {

    @Test
    @Order(1)
    @TestSecurity(user = "ytd-maker", roles = ["ROLE_OPERATOR"])
    fun `maker posts and drafts January income`() {
        post("2022-01-15", "1000")
        draft("2022-01-31")
    }

    @Test
    @Order(2)
    @TestSecurity(user = "ytd-checker", roles = ["ROLE_OPERATOR"])
    fun `checker freezes January`() = freeze("2022-01-31")

    @Test
    @Order(3)
    @TestSecurity(user = "ytd-maker", roles = ["ROLE_OPERATOR"])
    fun `maker posts and drafts February income`() {
        post("2022-02-15", "300")
        draft("2022-02-28")
    }

    @Test
    @Order(4)
    @TestSecurity(user = "ytd-checker", roles = ["ROLE_OPERATOR"])
    fun `checker freezes February`() = freeze("2022-02-28")

    @Test
    @Order(5)
    @TestSecurity(user = "ytd-viewer", roles = ["ROLE_VIEWER"])
    fun `February frozen F02 income includes January and February with both anchors`() {
        val monthly = get("2022-02-28", "frozen-trial-balance")
        val ytd = get("2022-02-28", "frozen-year-to-date-trial-balance")
        val live = get("2022-02-15", "year-to-date-trial-balance")

        assertThat(income(monthly)).isEqualByComparingTo("-300")
        assertThat(income(ytd)).isEqualByComparingTo("-1300")
        assertThat(income(live)).isEqualByComparingTo("-1300")
        assertThat(ytd.getString("from")).isEqualTo("2022-01-01")
        assertThat(ytd.getString("to")).isEqualTo("2022-02-28")
        assertThat(ytd.getList<String>("sourcePeriods")).containsExactly("MONTH:2022-01", "MONTH:2022-02")
        assertThat(ytd.getList<String>("sourceContentHashes")).hasSize(2)
        assertThat(live.getString("to")).isEqualTo("2022-02-15")
    }

    @Test
    @Order(6)
    @TestSecurity(user = "ytd-maker", roles = ["ROLE_OPERATOR"])
    fun `maker posts and drafts the next January`() {
        post("2023-01-15", "70")
        draft("2023-01-31")
    }

    @Test
    @Order(7)
    @TestSecurity(user = "ytd-checker", roles = ["ROLE_OPERATOR"])
    fun `checker freezes the next January`() = freeze("2023-01-31")

    @Test
    @Order(8)
    @TestSecurity(user = "ytd-viewer", roles = ["ROLE_VIEWER"])
    fun `next year does not inherit the previous year's income`() {
        val ytd = get("2023-01-31", "frozen-year-to-date-trial-balance")
        assertThat(income(ytd)).isEqualByComparingTo("-70")
        assertThat(ytd.getString("from")).isEqualTo("2023-01-01")
        assertThat(ytd.getList<String>("sourcePeriods")).containsExactly("MONTH:2023-01")
    }

    @Test
    @Order(9)
    @TestSecurity(user = "ytd-maker", roles = ["ROLE_OPERATOR"])
    fun `maker drafts February without a January close in an earlier year`() {
        post("2021-02-15", "50")
        draft("2021-02-28")
    }

    @Test
    @Order(10)
    @TestSecurity(user = "ytd-checker", roles = ["ROLE_OPERATOR"])
    fun `checker freezes the isolated February`() = freeze("2021-02-28")

    @Test
    @Order(11)
    @TestSecurity(user = "ytd-viewer", roles = ["ROLE_VIEWER"])
    fun `February without even a zero movement January close fails closed`() {
        given().accept("application/json").`when`()
            .get("/api/v1/ledger/periods/MONTH/2021-02-28/frozen-year-to-date-trial-balance")
            .then().statusCode(409)
    }

    @Test
    @Order(12)
    @TestSecurity(user = "ytd-viewer", roles = ["ROLE_VIEWER"])
    fun `frozen YTD rejects a midmonth date while live preview uses that exact date`() {
        given().accept("application/json").`when`()
            .get("/api/v1/ledger/periods/MONTH/2022-02-15/frozen-year-to-date-trial-balance")
            .then().statusCode(400)
        assertThat(get("2022-02-15", "year-to-date-trial-balance").getString("to"))
            .isEqualTo("2022-02-15")
    }

    private fun get(date: String, route: String): io.restassured.path.json.JsonPath =
        given().accept("application/json").`when`()
            .get("/api/v1/ledger/periods/MONTH/$date/$route")
            .then().statusCode(200).extract().jsonPath()

    private fun income(path: io.restassured.path.json.JsonPath): BigDecimal {
        val lines = path.getList<Map<String, Any>>("lines")
        return BigDecimal(lines.single { it["code"] == "4100" }.getValue("net").toString())
    }

    private fun draft(date: String) {
        given().contentType("application/json").`when`().post("/api/v1/ledger/periods/MONTH/$date")
            .then().statusCode(200)
    }

    private fun freeze(date: String) {
        given().contentType("application/json").`when`().post("/api/v1/ledger/periods/MONTH/$date/freeze")
            .then().statusCode(200)
    }

    private fun post(date: String, amount: String) {
        val journal = """
            {"idempotencyKey":"${UUID.randomUUID()}","transactionId":"${UUID.randomUUID()}",
             "entryDate":"$date","valueDate":"$date","description":"YTD flow IT",
             "createdBy":"00000000-0000-0000-0000-000000000703","lines":[
             {"glAccountId":"a0000000-0000-0000-0000-000000000001","side":"DEBIT","amount":"$amount","currencyCode":"CZK","baseAmount":"$amount","baseCurrencyCode":"CZK"},
             {"glAccountId":"a0000000-0000-0000-0000-000000004100","side":"CREDIT","amount":"$amount","currencyCode":"CZK","baseAmount":"$amount","baseCurrencyCode":"CZK"}]}
        """.trimIndent()
        given().contentType("application/json").body(journal).`when`().post("/api/v1/journals")
            .then().statusCode(201)
    }
}
