// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.application.port.out.CommandKey
import com.openbank.treasury.application.port.out.CorrectionCommit
import com.openbank.treasury.application.port.out.CorrectionLimitBreachedException
import com.openbank.treasury.application.port.out.CorrectionRepository
import com.openbank.treasury.application.port.out.ExposureGuard
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Uni
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0344 D7 security: a correction is vetted exactly as a booking. A LOAN to the 500 M CZK limit
 * counterparty: raising its notional tenfold is refused at proposal; past the product mandate it is
 * never proposable; a raise within the limit at proposal but over it at approval (another deal
 * booked meanwhile) is refused at approval, and the guard under the deal lock refuses it by itself;
 * a breach passes only under a senior's override, never approved by that senior.
 */
@QuarkusTest
@QuarkusTestResource(CashFlowLedgerApiIT.NoDispatch::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestProfile(CorrectionLimitIT.OwnDatabase::class)
class CorrectionLimitIT {
    class OwnDatabase : QuarkusTestProfile

    @Inject
    lateinit var correctionRepo: CorrectionRepository

    @Inject
    lateinit var deals: com.openbank.treasury.application.port.`in`.TreasuryDealUseCase

    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()
    private val start: LocalDate = today.plusDays(10)

    private fun post(path: String, body: String? = null) = given()
        .contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
        .apply { if (body != null) body(body) }
        .`when`().post("/api/v1/treasury$path")

    private fun <T> onContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        Uni.createFrom().completionStage(CoroutineScope(Dispatchers.Unconfined).future { block() })
    }

    private fun legs(notional: String) = """
        [{"payReceive":"RECEIVE","currency":"CZK","notional":$notional,"amortisation":{"type":"BULLET"},
          "accrualStart":"$start","accrualEnd":"${start.plusYears(1)}","accrualFrequency":"QUARTERLY",
          "paymentFrequency":"QUARTERLY","dayCount":"ACT/360","rate":{"type":"FIXED","rate":5.0},
          "initialNotionalExchange":true,"finalNotionalExchange":true}]
    """.trimIndent()

    private fun correction(notional: String, override: Boolean = false) =
        """{"changes":{"legs":${legs(notional)}},"reason":"notional captured wrong","effectiveFrom":"$start"""" +
            (if (override) ""","requestLimitOverride":true}""" else "}")

    private fun book(notional: String): String {
        val id: String = post(
            "/deals",
            """{"product":"LOAN","counterpartyId":"SIMBK-C","bookId":"TRADING-BOOK","calendars":["CERTIS"],
               "businessDayConvention":"MODIFIED_FOLLOWING","legs":${legs(notional)}}""",
        ).then().statusCode(201).extract().path("dealId")
        post("/deals/$id/submit").then().statusCode(200)
        sql("update deals set state = 'BOOKED' where deal_id = '$id'")
        return id
    }

    @Test
    @Order(1)
    @TestSecurity(user = "erik.editor", roles = ["ROLE_TREASURY_REFERENCE_EDITOR"])
    fun `1 - the LOAN mandate is proposed`() {
        post(
            "/reference/product-type",
            """{"key":"LOAN","validFrom":"$today","reason":"open lending",
               "payload":{"code":"LOAN","permitted":true,"maxPrincipal":{"CZK":5000000000.00},"maxTenorDays":3660}}""",
        ).then().statusCode(201)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "sam.senior", roles = ["ROLE_TREASURY_SENIOR_APPROVER", "ROLE_TREASURY_APPROVER"])
    fun `2 - and activated`() {
        post("/reference/product-type/LOAN/versions/1/approve").then().statusCode(200)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `3 - a 100 M loan is booked with the 500 M counterparty`() {
        deal = book("100000000.00")
    }

    @Test
    @Order(4)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `4 - a tenfold raise is refused at proposal, and one past the mandate is never proposable`() {
        post("/deals/$deal/corrections", correction("1000000000.00")).then().statusCode(422)
            .body("error", equalTo("LIMIT_BREACHED"))
        post("/deals/$deal/corrections", correction("6000000000.00", override = true)).then().statusCode(422)
            .body("error", equalTo("PRODUCT_LIMIT_BREACHED"))
        assertThat(count("select count(*) from deal_corrections where deal_id = '$deal'")).isZero()
        within = post("/deals/$deal/corrections", correction("450000000.00")).then().statusCode(201)
            .extract().path("correctionId")
    }

    @Test
    @Order(5)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `5 - another 100 M loan with the same counterparty is booked meanwhile`() {
        other = book("100000000.00")
    }

    @Test
    @Order(6)
    @TestSecurity(user = "olga.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `6 - the raise that fitted at proposal is refused at approval, nothing changes`() {
        post("/deals/$deal/corrections/$within/approve").then().statusCode(422).body("error", equalTo("LIMIT_BREACHED"))
        assertThat(count("select deal_version from deals where deal_id = '$deal'")).isEqualTo(1)
    }

    @Test
    @Order(7)
    fun `7 - the guard under the deal lock refuses a commit whose headroom is gone, by itself`() {
        val dealId = UUID.fromString(deal)
        val c = checkNotNull(onContext { correctionRepo.find(UUID.fromString(within)) })
        val d = onContext { deals.get(dealId) }.deal
        val olga = Actor("olga.approver", ActorType.HUMAN)
        val fp = onContext { correctionRepo.fingerprint(dealId, d.scheduleVersion) }
        assertThatThrownBy {
            onContext {
                correctionRepo.approve(
                    CorrectionCommit(
                        c.approve(olga, d, emptySet(), Instant.now()),
                        d.correct(c.terms, false, olga, c.reason, Instant.now()),
                        null,
                        fp,
                        emptySet(),
                        emptyList(),
                        CommandKey(UUID.randomUUID().toString(), "X:${c.correctionId}", dealId),
                        "[]",
                        // 500 M limit - 450 M corrected = 50 M left for the others; they hold 100 M.
                        ExposureGuard("SIMBK-C", "CZK", BigDecimal("50000000.00")),
                    ),
                )
            }
        }.isInstanceOf(CorrectionLimitBreachedException::class.java)
        assertThat(
            count("select count(*) from deal_corrections where correction_id = '$within' and status = 'PENDING'"),
        )
            .isEqualTo(1)
    }

    @Test
    @Order(8)
    @TestSecurity(user = "olga.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `8 - it is rejected`() {
        post("/deals/$deal/corrections/$within/reject", """{"reason":"over the limit"}""").then().statusCode(200)
    }

    @Test
    @Order(9)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `9 - a breach proposed as a request for a senior override`() {
        breach = post("/deals/$deal/corrections", correction("1000000000.00", override = true)).then().statusCode(201)
            .body("limitOverrideRequested", equalTo(true)).extract().path("correctionId")
    }

    @Test
    @Order(10)
    @TestSecurity(user = "olga.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `10 - without the override the approval is refused`() {
        post("/deals/$deal/corrections/$breach/approve").then().statusCode(422).body("error", equalTo("LIMIT_BREACHED"))
    }

    @Test
    @Order(11)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_SENIOR_APPROVER"])
    fun `11 - the proposer, even holding the senior role, cannot override`() {
        post("/deals/$deal/corrections/$breach/override-limit", """{"reason":"strategic"}""").then().statusCode(422)
            .body("error", equalTo("FOUR_EYES_VIOLATION"))
    }

    @Test
    @Order(12)
    @TestSecurity(user = "sam.senior", roles = ["ROLE_TREASURY_SENIOR_APPROVER", "ROLE_TREASURY_APPROVER"])
    fun `12 - a senior overrides, and may not then approve it too`() {
        post("/deals/$deal/corrections/$breach/override-limit", """{"reason":"strategic counterparty"}""")
            .then().statusCode(200).body("limitOverrideBy", equalTo("sam.senior"))
        post(
            "/deals/$deal/corrections/$breach/approve",
        ).then().statusCode(422).body("error", equalTo("FOUR_EYES_VIOLATION"))
    }

    @Test
    @Order(13)
    @TestSecurity(user = "olga.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `13 - a second approver approves under the override`() {
        post("/deals/$deal/corrections/$breach/approve").then().statusCode(200).body("status", equalTo("POSTED"))
        assertThat(count("select deal_version from deals where deal_id = '$deal'")).isEqualTo(2)
        assertThat(
            count("select cast(principal as bigint) / 1000000 from deals where deal_id = '$deal'"),
        ).isEqualTo(1000)
    }

    private fun sql(s: String) = jdbc { it.prepareStatement(s).executeUpdate() }

    private fun count(s: String): Int = jdbc { c ->
        c.prepareStatement(s).executeQuery().use { rs ->
            rs.next()
            rs.getInt(1)
        }
    }

    private fun <T> jdbc(block: (java.sql.Connection) -> T): T {
        val cfg = ConfigProvider.getConfig()
        return DriverManager.getConnection(
            cfg.getValue("quarkus.datasource.jdbc.url", String::class.java),
            cfg.getValue("quarkus.datasource.username", String::class.java),
            cfg.getValue("quarkus.datasource.password", String::class.java),
        ).use(block)
    }

    private companion object {
        lateinit var deal: String
        lateinit var other: String
        lateinit var within: String
        lateinit var breach: String
    }
}
