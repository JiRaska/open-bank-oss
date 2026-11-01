// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0315 D4 over real Postgres: an IRS consumes its counterparty's credit line by notional x the
 * seeded conversion factor (V22), and the figure the pre-trade check measured is exactly what the
 * line query (the one the locked re-check runs) counts for it afterwards.
 */
@QuarkusTest
@QuarkusTestResource(CashFlowLedgerApiIT.NoDispatch::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestProfile(DerivativeCreditLineIT.OwnDatabase::class)
class DerivativeCreditLineIT {
    /** Its own application (and Postgres): the reference data it activates must not leak into other ITs. */
    class OwnDatabase : QuarkusTestProfile

    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()

    private fun post(path: String, body: String? = null) = given()
        .contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
        .apply { if (body != null) body(body) }
        .`when`().post("/api/v1/treasury$path")

    @Test
    @Order(1)
    @TestSecurity(user = "sam.senior", roles = ["ROLE_TREASURY_REFERENCE_EDITOR", "ROLE_TREASURY_SENIOR_APPROVER"])
    fun `1 - the IRS mandate is proposed`() {
        post(
            "/reference/product-type",
            """{"key":"IRS","validFrom":"$today","reason":"open IRS dealing",
               "payload":{"code":"IRS","permitted":true,"maxPrincipal":{"CZK":500000000.00},"maxTenorDays":3660}}""",
        ).then().statusCode(201)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "anna.senior2", roles = ["ROLE_TREASURY_SENIOR_APPROVER"])
    fun `2 - and activated by a different senior`() {
        post("/reference/product-type/IRS/versions/1/approve").then().statusCode(200)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `3 - an IRS consumes notional x factor, and the line query counts exactly that figure`() {
        val start = today.plusDays(30)
        val end = start.plusMonths(6)
        val legs = """
            [{"payReceive":"PAY","currency":"CZK","notional":10000000.00,"accrualStart":"$start","accrualEnd":"$end",
              "accrualFrequency":"QUARTERLY","paymentFrequency":"QUARTERLY","dayCount":"ACT/360","rate":{"type":"FIXED","rate":4.0}},
             {"payReceive":"RECEIVE","currency":"CZK","notional":10000000.00,"accrualStart":"$start","accrualEnd":"$end",
              "accrualFrequency":"QUARTERLY","paymentFrequency":"QUARTERLY","dayCount":"ACT/360",
              "rate":{"type":"FLOATING","index":"PRIBOR_3M","spread":0.10,"resetFrequency":"QUARTERLY"}}]
        """.trimIndent()
        val irs: String = post(
            "/deals",
            """{"product":"IRS","counterpartyId":"SIMBK-B","bookId":"TRADING-BOOK","calendars":["CERTIS"],
               "businessDayConvention":"MODIFIED_FOLLOWING","legs":$legs}""",
        ).then().statusCode(201).extract().path("dealId")
        val first = post("/deals/$irs/submit").then().statusCode(200).extract()
        val before = BigDecimal(first.path<Any>("limitCheck.exposureBefore").toString())
        val after = BigDecimal(first.path<Any>("limitCheck.exposureAfter").toString())
        // ~7 months residual -> the <=1y bucket, 0.5 % of 10 M.
        assertThat(after - before).isEqualByComparingTo("50000.00")
        assertThat(storedAmount(irs)).isEqualByComparingTo("50000.00")

        // The next deal on the same line is measured by the line query: it sees the IRS's figure.
        val placement: String = post(
            "/deals",
            """{"product":"MM_PLACEMENT","counterpartyId":"SIMBK-B","currency":"CZK","principal":1000000.00,
               "rate":4.25,"valueDate":"$today","maturityDate":"${today.plusDays(30)}","bookId":"BANKING-BOOK"}""",
        ).then().statusCode(201).extract().path("dealId")
        val second = post("/deals/$placement/submit").then().statusCode(200).extract()
        assertThat(BigDecimal(second.path<Any>("limitCheck.exposureBefore").toString()))
            .isEqualByComparingTo(after)
    }

    private fun storedAmount(dealId: String): BigDecimal = jdbc { c ->
        c.prepareStatement("select limit_deal_amount from deals where deal_id = ?").apply {
            setObject(1, UUID.fromString(dealId))
        }.executeQuery().use { rs ->
            rs.next()
            rs.getBigDecimal(1)
        }
    }

    private fun <T> jdbc(block: (Connection) -> T): T {
        val cfg = ConfigProvider.getConfig()
        val url = cfg.getValue("quarkus.datasource.jdbc.url", String::class.java)
        val user = cfg.getValue("quarkus.datasource.username", String::class.java)
        val pass = cfg.getValue("quarkus.datasource.password", String::class.java)
        return DriverManager.getConnection(url, user, pass).use(block)
    }
}
