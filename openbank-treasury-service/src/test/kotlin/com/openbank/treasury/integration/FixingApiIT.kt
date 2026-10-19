// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.domain.marketdata.IndexObservation
import com.openbank.treasury.domain.marketdata.ObservationSource
import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
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
 * ADR-0344 D5 over real HTTP and Postgres: a floating IRS coupon is DUE on its fixing date; with no
 * observation the run BLOCKS it (a case, nothing fixed); an import is four-eyes (the importer cannot
 * approve it even holding the approver role, an AI agent never); once approved the run fixes the
 * coupon FIXED / OBSERVED with the observation's SHA-256, and `treasury.cashflow.fixed.v1` is in the
 * outbox of the same transaction.
 */
@QuarkusTest
@QuarkusTestResource(CashFlowLedgerApiIT.NoDispatch::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestProfile(FixingApiIT.OwnDatabase::class)
class FixingApiIT {
    /** Its own application (and Postgres): the reference data it activates must not leak into other ITs. */
    class OwnDatabase : QuarkusTestProfile
    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()

    private fun post(path: String, body: String? = null, type: String = "application/json") = given()
        .contentType(type).header("Idempotency-Key", UUID.randomUUID().toString())
        .apply { if (body != null) body(body) }
        .`when`().post("/api/v1/treasury$path")

    @Test
    @Order(1)
    @TestSecurity(user = "erik.editor", roles = ["ROLE_TREASURY_REFERENCE_EDITOR", "ROLE_TREASURY_SENIOR_APPROVER"])
    fun `1 - the IRS mandate is active`() {
        val active = jdbc { c ->
            c.prepareStatement(
                "select count(*) from reference_records where kind = 'product-type' and record_key = 'IRS' and status = 'ACTIVE'",
            ).executeQuery().use {
                it.next()
                it.getInt(1)
            }
        }
        if (active == 0) {
            post(
                "/reference/product-type",
                """{"key":"IRS","validFrom":"$today","reason":"open IRS dealing",
                   "payload":{"code":"IRS","permitted":true,"maxPrincipal":{"CZK":500000000.00},"maxTenorDays":3660}}""",
            ).then().statusCode(201)
        }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "sam.senior", roles = ["ROLE_TREASURY_SENIOR_APPROVER"])
    fun `2 - the senior activates it if pending`() {
        val pending = jdbc { c ->
            c.prepareStatement(
                "select max(version) from reference_records where kind = 'product-type' and record_key = 'IRS' and status = 'PENDING'",
            ).executeQuery().use { rs ->
                rs.next()
                rs.getObject(1) as Int?
            }
        }
        pending?.let { post("/reference/product-type/IRS/versions/$it/approve").then().statusCode(200) }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `3 - a floating IRS is submitted and its first coupon has a fixing date`() {
        val start = today.plusDays(20)
        val end = start.plusYears(1)
        val legs = """
            [{"payReceive":"PAY","currency":"CZK","notional":10000000.00,"accrualStart":"$start","accrualEnd":"$end",
              "accrualFrequency":"QUARTERLY","paymentFrequency":"QUARTERLY","dayCount":"ACT/360","rate":{"type":"FIXED","rate":4.0}},
             {"payReceive":"RECEIVE","currency":"CZK","notional":10000000.00,"accrualStart":"$start","accrualEnd":"$end",
              "accrualFrequency":"QUARTERLY","paymentFrequency":"QUARTERLY","dayCount":"ACT/360",
              "rate":{"type":"FLOATING","index":"PRIBOR_3M","spread":0.10,"resetFrequency":"QUARTERLY"}}]
        """.trimIndent()
        irs = post(
            "/deals",
            """{"product":"IRS","counterpartyId":"SIMBK-A","bookId":"TRADING-BOOK","calendars":["CERTIS"],
               "businessDayConvention":"MODIFIED_FOLLOWING","legs":$legs}""",
        ).then().statusCode(201).extract().path("dealId")
        post("/deals/$irs/submit").then().statusCode(200)
        fixingDate = jdbc { c ->
            c.prepareStatement(
                "select min(fixing_date) from deal_cash_flows where deal_id = ? and state = 'PROJECTED'",
            ).use { ps ->
                ps.setObject(1, UUID.fromString(irs))
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1, LocalDate::class.java)
                }
            }
        }
        // D5: in advance = accrual start minus the lag, never the payment date.
        assertThat(fixingDate).isBefore(start.plusDays(1)).isAfter(start.minusDays(10))
    }

    @Test
    @Order(4)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `4 - with no observation the run blocks the index-date and fixes nothing`() {
        post("/fixings/run?date=$fixingDate").then().statusCode(200).body("fixedFlows", equalTo(0))
            .body("blocked.index", hasItem("PRIBOR_3M"))
        given().`when`().get("/api/v1/treasury/fixings?date=$fixingDate&status=BLOCKED").then().statusCode(200)
            .body("index", hasItem("PRIBOR_3M")).body("find { it.index == 'PRIBOR_3M' }.missing", equalTo(true))
        given().`when`().get("/api/v1/treasury/market-data/quality?date=$fixingDate").then().statusCode(200)
            .body("missing.index", hasItem("PRIBOR_3M"))
        assertThat(projected()).isGreaterThan(0)
        given().`when`().get("/api/v1/treasury/day-completion/$fixingDate").then().statusCode(200)
            .body("steps.find { it.step == 'FIXINGS' }.status", equalTo("FAILED"))
    }

    @Test
    @Order(5)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE", "ROLE_TREASURY_APPROVER"])
    fun `5 - an import is a PENDING batch its importer cannot approve, even holding the approver role`() {
        val csv = "index,date,value\nPRIBOR_3M,${fixingDate.minusDays(1)},3.50\nPRIBOR_3M,$fixingDate,3.52\n"
        batch = post("/market-data/import?reason=daily%20reference%20rates", csv, "text/csv").then().statusCode(201)
            .body("status", equalTo("PENDING")).body("rowCount", equalTo(2)).extract().path("batchId")
        post("/market-data/imports/$batch/approve").then().statusCode(422).body("error", equalTo("FOUR_EYES_VIOLATION"))
        post("/market-data/import?reason=bad", "PRIBOR_3M,not-a-date,1", "text/csv").then().statusCode(400)
            .body("message", containsString("row 1"))
    }

    @Test
    @Order(6)
    @TestSecurity(user = "agent:treasury-drafter", roles = ["ROLE_TREASURY_APPROVER"])
    fun `6 - an AI agent can never approve market data`() {
        post("/market-data/imports/$batch/approve").then().statusCode(403)
    }

    @Test
    @Order(7)
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `7 - a different human approves, and the observations are ACTIVE`() {
        post("/market-data/imports/$batch/approve").then().statusCode(200).body("status", equalTo("APPROVED"))
        given().`when`().get(
            "/api/v1/treasury/market-data/factors?from=${fixingDate.minusDays(5)}&to=$fixingDate&index=PRIBOR_3M",
        )
            .then().statusCode(200).body("$", hasSize<Any>(2))
        val csv = given().`when`().get(
            "/api/v1/treasury/market-data/export?from=${fixingDate.minusDays(5)}&to=$fixingDate",
        )
            .then().statusCode(200).extract().asString()
        assertThat(csv).startsWith("index,date,value,source,sha256").contains("\"PRIBOR_3M\"")
    }

    @Test
    @Order(8)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `8 - the run fixes the coupon from the approved value, with provenance, atomically with its event`() {
        val due = projected()
        post("/fixings/run?date=$fixingDate").then().statusCode(200).body("fixedIndices", hasItem("PRIBOR_3M"))
        val sha = IndexObservation.hash("PRIBOR_3M", fixingDate, BigDecimal("3.52"), ObservationSource.IMPORT)
        val fixed = jdbc { c ->
            c.prepareStatement(
                "select count(*) from deal_cash_flows where deal_id = ? and fixing_date = ? and state = 'FIXED' " +
                    "and rate_source = 'OBSERVED' and amount is not null and fixing_sha256 = ? and fixing_source = 'IMPORT'",
            ).use { ps ->
                ps.setObject(1, UUID.fromString(irs))
                ps.setObject(2, fixingDate)
                ps.setString(3, sha)
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
        assertThat(fixed).isGreaterThan(0)
        assertThat(projected()).isEqualTo(due - fixed)
        val events = jdbc { c ->
            c.prepareStatement(
                "select count(*) from treasury_outbox where aggregate_id = ? " +
                    "and event_type = 'treasury.cashflow.fixed.v1' and payload like ?",
            ).use { ps ->
                ps.setObject(1, UUID.fromString(irs))
                ps.setString(2, "%$sha%")
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
        assertThat(events).isEqualTo(1)
        given().`when`().get("/api/v1/treasury/fixings?date=$fixingDate&status=FIXED").then().statusCode(200)
            .body("index", hasItem("PRIBOR_3M"))
        given().`when`().get("/api/v1/treasury/fixings?date=$fixingDate&status=BLOCKED").then().statusCode(200)
            .body("index.findAll { it == 'PRIBOR_3M' }", hasSize<Any>(0))
        // A second run fixes nothing twice.
        post("/fixings/run?date=$fixingDate").then().statusCode(200).body("fixedFlows", equalTo(0))
    }

    @Test
    @Order(9)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `9 - a manual fixing is a pending proposal, and back office cannot approve it`() {
        manual =
            post(
                "/fixings/ESTR/$fixingDate/manual",
                """{"rate":1.93,"reason":"feed outage","source":"published page"}""",
            )
                .then().statusCode(201).body("kind", equalTo("MANUAL_FIXING")).extract().path("batchId")
        post("/fixings/ESTR/$fixingDate/manual/$manual/approve").then().statusCode(403)
    }

    private fun projected(): Int = jdbc { c ->
        c.prepareStatement(
            "select count(*) from deal_cash_flows where deal_id = ? and fixing_date = ? and state = 'PROJECTED'",
        ).use { ps ->
            ps.setObject(1, UUID.fromString(irs))
            ps.setObject(2, fixingDate)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun <T> jdbc(block: (Connection) -> T): T {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }

    private companion object {
        lateinit var irs: String
        lateinit var fixingDate: LocalDate
        lateinit var batch: String
        lateinit var manual: String
    }
}
