// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.application.port.out.CommandKey
import com.openbank.treasury.application.port.out.CorrectionCommit
import com.openbank.treasury.application.port.out.CorrectionRepository
import com.openbank.treasury.application.port.out.DealChangedException
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
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * ADR-0344 D7 / slice 4b versioned save: a correction approval commits only over the deal row it was
 * computed from. A copy loaded BEFORE another legitimate write to the deal (here the counterparty
 * confirmation, which moves the row version but neither the terms nor the schedule version) is
 * refused under the deal-row lock with DEAL_CHANGED, and nothing of the correction commits or posts.
 */
@QuarkusTest
@QuarkusTestResource(CashFlowLedgerApiIT.NoDispatch::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestProfile(CorrectionRowVersionIT.OwnDatabase::class)
class CorrectionRowVersionIT {
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

    private fun correction(notional: String) =
        """{"changes":{"legs":${legs(notional)}},"reason":"notional captured wrong","effectiveFrom":"$start"}"""

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
    fun `3 - a loan is booked`() {
        deal = book("100000000.00")
    }

    @Test
    @Order(4)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `4 - a correction is proposed`() {
        correction = post("/deals/$deal/corrections", correction("120000000.00")).then().statusCode(201)
            .extract().path("correctionId")
    }

    @Test
    @Order(5)
    fun `5 - the approver's copy of the deal is loaded`() {
        stale = onContext { deals.get(UUID.fromString(deal)) }.deal
        staleRowVersion = count("select cast(row_version as int) from deals where deal_id = '$deal'")
    }

    @Test
    @Order(6)
    @TestSecurity(user = "olga.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `6 - meanwhile the counterparty confirmation moves the row version`() {
        post("/deals/$deal/confirm", """{"reference":"CPTY-CONF-1"}""").then().statusCode(200)
            .body("state", equalTo("CONFIRMED"))
        assertThat(count("select cast(row_version as int) from deals where deal_id = '$deal'"))
            .isGreaterThan(staleRowVersion)
    }

    @Test
    @Order(7)
    fun `7 - an approval computed from the older copy is refused DEAL_CHANGED and nothing commits`() {
        val dealId = UUID.fromString(deal)
        val c = checkNotNull(onContext { correctionRepo.find(UUID.fromString(correction)) })
        val olga = Actor("olga.approver", ActorType.HUMAN)
        val fp = onContext { correctionRepo.fingerprint(dealId, stale.scheduleVersion) }
        assertThatThrownBy {
            onContext {
                correctionRepo.approve(
                    CorrectionCommit(
                        c.approve(olga, stale, emptySet(), Instant.now()),
                        stale.correct(c.terms, false, olga, c.reason, Instant.now()),
                        null,
                        fp,
                        emptySet(),
                        emptyList(),
                        CommandKey(UUID.randomUUID().toString(), "X:${c.correctionId}", dealId),
                        "[]",
                    ),
                )
            }
        }.isInstanceOf(DealChangedException::class.java)
        assertThat(
            count("select count(*) from deal_corrections where correction_id = '$correction' and status = 'PENDING'"),
        )
            .isEqualTo(1)
        assertThat(count("select deal_version from deals where deal_id = '$deal'")).isEqualTo(1)
        assertThat(
            count("select cast(principal as bigint) / 1000000 from deals where deal_id = '$deal'"),
        ).isEqualTo(100)
        assertThat(
            count(
                "select count(*) from deal_journals where deal_id = '$deal' and idempotency_key like '%:correction:%'",
            ),
        )
            .isZero()
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
        lateinit var correction: String
        lateinit var stale: com.openbank.treasury.domain.model.Deal
        var staleRowVersion: Int = 0
    }
}
