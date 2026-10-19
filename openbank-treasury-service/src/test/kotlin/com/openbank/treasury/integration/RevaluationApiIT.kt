// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.application.port.out.BookValuation
import com.openbank.treasury.application.port.out.DealFairValue
import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * ADR-0344 D7/D8 over real HTTP and Postgres: a fair-value derivative the risk engine has not valued
 * is a DATA GAP — nothing posts, no row, the REVALUATION day step FAILED with the reason, never a
 * zero; once valued the change posts through the matrix REVALUATION event; a replay of the same
 * (deal, date) posts nothing new and returns the same record; two concurrent runs of a new date
 * write exactly one row and one journal key, and the next date posts only the CHANGE.
 */
@QuarkusTest
@QuarkusTestResource(CashFlowLedgerApiIT.NoDispatch::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestProfile(RevaluationApiIT.OwnDatabase::class)
class RevaluationApiIT {
    /** Its own application (and Postgres): the reference data and day steps must not leak into other ITs. */
    class OwnDatabase : QuarkusTestProfile

    @Inject
    lateinit var valuation: FakeValuation

    @Inject
    lateinit var ledger: FakeLedger

    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()

    private fun post(path: String, body: String? = null) = given()
        .contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
        .apply { if (body != null) body(body) }
        .`when`().post("/api/v1/treasury$path")

    @Test
    @Order(1)
    @TestSecurity(user = "erik.editor", roles = ["ROLE_TREASURY_REFERENCE_EDITOR", "ROLE_TREASURY_SENIOR_APPROVER"])
    fun `1 - the IRS mandate is proposed`() {
        if (count("select count(*) from reference_records where kind = 'product-type' and record_key = 'IRS'") == 0) {
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
    fun `2 - the senior activates it`() {
        val pending = jdbc { c ->
            c.prepareStatement(
                "select max(version) from reference_records where kind = 'product-type' and record_key = 'IRS' and status = 'PENDING'",
            ).executeQuery().use { rs ->
                rs.next()
                rs.getObject(1) as? Int
            }
        }
        pending?.let { post("/reference/product-type/IRS/versions/$it/approve").then().statusCode(200) }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `3 - an IRS is booked`() {
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
        // Approval and confirmation are not under test here: a CONFIRMED derivative is a live fair-value deal.
        jdbc { it.prepareStatement("update deals set state = 'CONFIRMED' where deal_id = '$irs'").executeUpdate() }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `4 - with no valuation the deal is a data gap, nothing posts and the day step FAILS with the reason`() {
        valuation.reset()
        val before = ledger.journals.size
        post("/revaluations/run?asOf=$today").then().statusCode(200)
            .body("journals", equalTo(0)).body("revalued", hasSize<Any>(0))
            .body("gaps.find { it.dealId == '$irs' }.reason", containsString("no TIED_OUT risk snapshot"))
        assertThat(ledger.journals.size).isEqualTo(before)
        assertThat(rows()).isZero()
        given().`when`().get("/api/v1/treasury/day-completion/$today").then().statusCode(200)
            .body("steps.find { it.step == 'REVALUATION' }.status", equalTo("FAILED"))
            .body("steps.find { it.step == 'REVALUATION' }.detail", containsString("no TIED_OUT risk snapshot"))

        // Valued, but unpriced by the engine: still a gap, never a zero.
        valuation.byDate[today] = book(today, DealFairValue(UUID.fromString(irs), null, null, "no PRIBOR_3M curve"))
        post("/revaluations/run?asOf=$today").then().statusCode(200).body("journals", equalTo(0))
            .body("gaps.find { it.dealId == '$irs' }.reason", containsString("unpriced"))
        assertThat(rows()).isZero()

        valuation.down = true
        post("/revaluations/run?asOf=$today").then().statusCode(200)
            .body("gaps.find { it.dealId == '$irs' }.reason", containsString("valuation unavailable"))
        assertThat(rows()).isZero()
    }

    @Test
    @Order(5)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `5 - once valued the change posts once, and a replay posts nothing new`() {
        valuation.reset()
        valuation.byDate[today] = book(today, fv("125000.00"))
        val first = post("/revaluations/run?asOf=$today").then().statusCode(200).body("journals", equalTo(1))
            .extract().path<String>("revalued.find { it.dealId == '$irs' }.journalId")
        val key = "treasury:$irs:revaluation:$today"
        assertThat(ledger.journals).containsKey(key)
        val calls = ledger.calls.count { it == key }

        val again = post("/revaluations/run?asOf=$today").then().statusCode(200).body("journals", equalTo(0))
            .extract().path<String>("revalued.find { it.dealId == '$irs' }.journalId")
        assertThat(again).isEqualTo(first)
        assertThat(ledger.calls.count { it == key }).isEqualTo(calls)
        assertThat(rows()).isEqualTo(1)
        assertThat(count("select count(*) from deal_journals where idempotency_key = '$key'")).isEqualTo(1)
        given().`when`().get("/api/v1/treasury/revaluations?asOf=$today").then().statusCode(200)
            .body("find { it.dealId == '$irs' }.change", equalTo(125000.0f))
        given().`when`().get("/api/v1/treasury/day-completion/$today").then().statusCode(200)
            .body("steps.find { it.step == 'REVALUATION' }.status", equalTo("DONE"))
    }

    @Test
    @Order(6)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `6 - two concurrent runs of a new date write one row, and post only the change`() {
        val next = today.plusDays(1)
        valuation.byDate[next] = book(next, fv("100000.00"))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) {
                pool.submit(
                    Callable {
                        val r = post("/revaluations/run?asOf=$next").then().extract()
                        check(r.statusCode() == 200) { "HTTP ${r.statusCode()}: ${r.asString()}" }
                        checkNotNull(r.path<Any>("revalued.find { it.dealId == '$irs' }.change")) { r.asString() }
                            .toString()
                    },
                )
            }.map { it.get() }
            assertThat(results).allSatisfy { assertThat(BigDecimal(it)).isEqualByComparingTo("-25000.00") }
        } finally {
            pool.shutdown()
        }
        assertThat(
            count("select count(*) from deal_revaluations where deal_id = '$irs' and as_of = '$next'"),
        ).isEqualTo(1)
        assertThat(
            count("select count(*) from deal_journals where idempotency_key = 'treasury:$irs:revaluation:$next'"),
        ).isEqualTo(1)
    }

    @Test
    @Order(7)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `7 - a date before the latest is refused 409 and the cumulative stays the latest fair value`() {
        val later = today.plusDays(3)
        val earlier = today.plusDays(2)
        valuation.byDate[later] = book(later, fv("90000.00"))
        valuation.byDate[earlier] = book(earlier, fv("50000.00"))
        post("/revaluations/run?asOf=$later").then().statusCode(200).body("journals", equalTo(1))
        val journalsBefore = ledger.journals.size
        post("/revaluations/run?asOf=$earlier&dealId=$irs").then().statusCode(409)
            .body("error", equalTo("REVALUATION_OUT_OF_ORDER"))
        post("/revaluations/run?asOf=$earlier").then().statusCode(200).body("failures", hasSize<Any>(1))
        assertThat(ledger.journals.size).isEqualTo(journalsBefore)
        assertThat(
            count("select count(*) from deal_revaluations where deal_id = '$irs' and as_of = '$earlier'"),
        ).isZero()
        val cumulative = jdbc { c ->
            c.prepareStatement(
                "select cumulative from deal_revaluations where deal_id = '$irs' order by as_of desc limit 1",
            ).executeQuery().use {
                it.next()
                it.getBigDecimal(1)
            }
        }
        assertThat(cumulative).isEqualByComparingTo("90000.00")
        // The replay of the latest date posts nothing new.
        post("/revaluations/run?asOf=$later").then().statusCode(200).body("journals", equalTo(0))
        assertThat(ledger.journals.size).isEqualTo(journalsBefore)
    }

    @Test
    @Order(8)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `8 - a claim the ledger never took blocks the next date until the run re-posts it`() {
        val d1 = today.plusDays(4)
        val d2 = today.plusDays(5)
        valuation.byDate[d1] = book(d1, fv("95000.00"))
        valuation.byDate[d2] = book(d2, fv("97000.00"))
        ledger.down = true
        try {
            post("/revaluations/run?asOf=$d1").then().statusCode(200).body("journals", equalTo(0))
            given().`when`().get("/api/v1/treasury/day-completion/$d1").then().statusCode(200)
                .body("steps.find { it.step == 'REVALUATION' }.status", equalTo("FAILED"))
            post("/revaluations/run?asOf=$d2&dealId=$irs").then().statusCode(409)
                .body("error", equalTo("PRIOR_REVALUATION_UNPOSTED"))
            assertThat(
                count("select count(*) from deal_revaluations where deal_id = '$irs' and as_of = '$d2'"),
            ).isZero()
        } finally {
            ledger.down = false
        }
        post("/revaluations/run?asOf=$d2").then().statusCode(200).body("reposted", equalTo(1))
        assertThat(ledger.journals).containsKey("treasury:$irs:revaluation:$d1")
        assertThat(ledger.journals).containsKey("treasury:$irs:revaluation:$d2")
        val change = jdbc { c ->
            c.prepareStatement("select change_amount from deal_revaluations where deal_id = '$irs' and as_of = '$d2'")
                .executeQuery().use {
                    it.next()
                    it.getBigDecimal(1)
                }
        }
        assertThat(change).isEqualByComparingTo("2000.00")
        assertThat(
            count("select count(*) from deal_revaluations where idempotency_key is not null and journal_id is null"),
        )
            .isZero()
        given().`when`().get("/api/v1/treasury/day-completion/$d2").then().statusCode(200)
            .body("steps.find { it.step == 'REVALUATION' }.status", equalTo("DONE"))
    }

    private fun fv(clean: String) = DealFairValue(UUID.fromString(irs), BigDecimal(clean), BigDecimal(clean), null)

    private fun book(asOf: LocalDate, vararg deals: DealFairValue) = BookValuation(
        UUID.nameUUIDFromBytes("snapshot-$asOf".toByteArray()),
        asOf,
        "CZK",
        "sha256:it",
        deals.associateBy { it.dealId },
        null,
    )

    private fun rows(): Int = count("select count(*) from deal_revaluations where deal_id = '$irs'")

    private fun count(sql: String): Int = jdbc { c ->
        c.prepareStatement(sql).executeQuery().use {
            it.next()
            it.getInt(1)
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
    }
}
