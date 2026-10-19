// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.application.usecase.PaymentService
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.payment.FundingExceededException
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
import org.eclipse.microprofile.config.ConfigProvider
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * ADR-0344 D6 over real HTTP and Postgres. Two booked IRS pay an identical coupon tomorrow. The
 * outgoing coupon of deal A carries an approved override. Its payment cannot be released by the
 * deal's creator, by the override's proposer or approver, by an AI agent or by a non-releaser; a flow
 * already on a payment cannot be overridden; two releases that each fit the funding account but
 * together overdraw it serialise and exactly one passes; a released payment is never cancelled
 * (which would free its flows) and is resolved only by an independent human, keeping its links.
 */
@QuarkusTest
@QuarkusTestResource(CashFlowLedgerApiIT.NoDispatch::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestProfile(PaymentApiIT.OwnDatabase::class)
class PaymentApiIT {
    /** Its own application (and Postgres): the reference data it activates must not leak into other ITs. */
    class OwnDatabase : QuarkusTestProfile

    @Inject
    lateinit var ledger: FakeLedgerRead

    @Inject
    lateinit var payments: PaymentService

    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()

    private fun post(path: String, body: String? = null) = given()
        .contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
        .apply { if (body != null) body(body) }
        .`when`().post("/api/v1/treasury$path")

    private fun <T> onContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        Uni.createFrom().completionStage(CoroutineScope(Dispatchers.Unconfined).future { block() })
    }

    @Test
    @Order(1)
    @TestSecurity(user = "erik.editor", roles = ["ROLE_TREASURY_REFERENCE_EDITOR"])
    fun `1 - the IRS mandate and the counterparty's CZK settlement instruction are proposed`() {
        post(
            "/reference/product-type",
            """{"key":"IRS","validFrom":"$today","reason":"open IRS dealing",
               "payload":{"code":"IRS","permitted":true,"maxPrincipal":{"CZK":500000000.00},"maxTenorDays":3660}}""",
        ).then().statusCode(201)
        post(
            "/reference/ssi",
            """{"key":"SIMBK-A:CZK","validFrom":"$today","reason":"standard settlement instruction",
               "payload":{"counterpartyId":"SIMBK-A","currency":"CZK","rail":"CERTIS",
                          "beneficiaryAccount":"CZ6508000000192000145399"}}""",
        ).then().statusCode(201)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "sam.senior", roles = ["ROLE_TREASURY_SENIOR_APPROVER", "ROLE_TREASURY_APPROVER"])
    fun `2 - a different human activates both`() {
        post("/reference/product-type/IRS/versions/1/approve").then().statusCode(200)
        post("/reference/ssi/SIMBK-A:CZK/versions/1/approve").then().statusCode(200)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `3 - two identical IRS are booked and their first coupons fall due tomorrow`() {
        val start = today.plusDays(10)
        val end = start.plusYears(1)
        val legs = """
            [{"payReceive":"PAY","currency":"CZK","notional":10000000.00,"accrualStart":"$start","accrualEnd":"$end",
              "accrualFrequency":"QUARTERLY","paymentFrequency":"QUARTERLY","dayCount":"ACT/360","rate":{"type":"FIXED","rate":4.0}},
             {"payReceive":"RECEIVE","currency":"CZK","notional":10000000.00,"accrualStart":"$start","accrualEnd":"$end",
              "accrualFrequency":"QUARTERLY","paymentFrequency":"QUARTERLY","dayCount":"ACT/360","rate":{"type":"FIXED","rate":3.0}}]
        """.trimIndent()
        fun book(): String {
            val id: String = post(
                "/deals",
                """{"product":"IRS","counterpartyId":"SIMBK-A","bookId":"TRADING-BOOK","calendars":["CERTIS"],
                   "businessDayConvention":"MODIFIED_FOLLOWING","legs":$legs}""",
            ).then().statusCode(201).extract().path("dealId")
            post("/deals/$id/submit").then().statusCode(200)
            return id
        }
        dealA = book()
        dealB = book()
        valueDate = today.plusDays(1)
        jdbc { c ->
            c.prepareStatement(
                "update deals set state = 'BOOKED' where deal_id in ('$dealA', '$dealB')",
            ).executeUpdate()
            c.prepareStatement(
                "update deal_cash_flows set payment_date = ? where deal_id in ('$dealA', '$dealB') " +
                    "and period_index = 0 and kind = 'INTEREST'",
            ).use {
                it.setObject(1, valueDate)
                it.executeUpdate()
            }
        }
        outFlowA = outFlow(dealA)
        outFlowB = outFlow(dealB)
    }

    @Test
    @Order(4)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `4 - back office proposes an override on deal A's outgoing coupon`() {
        overrideId = post(
            "/deals/$dealA/cash-flows/$outFlowA/overrides",
            """{"paymentDate":"$valueDate","reason":"confirm the value date with the counterparty"}""",
        ).then().statusCode(201).extract().path("overrideId")
    }

    @Test
    @Order(5)
    @TestSecurity(user = "olga.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `5 - a different human approves it`() {
        post("/deals/$dealA/cash-flows/$outFlowA/overrides/$overrideId/approve").then().statusCode(200)
    }

    @Test
    @Order(6)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `6 - payments are proposed, addressed from the SSI, and a paid flow can no longer be overridden`() {
        post("/payments/propose?date=$today").then().statusCode(200)
        payA = paymentOf(outFlowA)
        payB = paymentOf(outFlowB)
        given().`when`().get("/api/v1/treasury/payments/$payA").then().statusCode(200)
            .body("status", equalTo("PROPOSED")).body("rail", equalTo("CERTIS"))
            .body("idempotencyKey", equalTo("treasury:$dealA:$outFlowA:payment"))
        post("/payments/propose?date=$today").then().statusCode(200)
        assertThat(count("select count(*) from treasury_payment_flows where flow_id = '$outFlowA'")).isEqualTo(1)
        post(
            "/deals/$dealB/cash-flows/$outFlowB/overrides",
            """{"paymentDate":"${valueDate.plusDays(1)}","reason":"late"}""",
        ).then().statusCode(409).body("error", equalTo("FLOW_ALREADY_IN_PAYMENT"))
    }

    @Test
    @Order(7)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_PAYMENT_RELEASER"])
    fun `7 - the deal's creator cannot release, even holding the releaser role`() {
        post("/payments/$payA/release").then().statusCode(422).body("error", equalTo("FOUR_EYES_VIOLATION"))
    }

    @Test
    @Order(8)
    @TestSecurity(user = "olga.approver", roles = ["ROLE_TREASURY_PAYMENT_RELEASER"])
    fun `8 - the approver of an override on a netted flow cannot release`() {
        post("/payments/$payA/release").then().statusCode(422).body("error", equalTo("FOUR_EYES_VIOLATION"))
    }

    @Test
    @Order(9)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_PAYMENT_RELEASER"])
    fun `9 - the proposer of that override cannot release`() {
        post("/payments/$payA/release").then().statusCode(422).body("error", equalTo("FOUR_EYES_VIOLATION"))
    }

    @Test
    @Order(10)
    @TestSecurity(user = "agent:treasury-drafter", roles = ["ROLE_TREASURY_PAYMENT_RELEASER"])
    fun `10 - an AI agent can never release`() {
        post("/payments/$payA/release").then().statusCode(403)
    }

    @Test
    @Order(11)
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `11 - an approver is not a payment releaser`() {
        post("/payments/$payA/release").then().statusCode(403)
    }

    @Test
    @Order(12)
    fun `12 - two releases that each fit but together overdraw serialise, and exactly one passes`() {
        val amount = jdbc { c ->
            c.prepareStatement("select amount from treasury_payments where payment_id = '$payA'").executeQuery()
                .use { rs ->
                    rs.next()
                    rs.getBigDecimal(1)
                }
        }
        ledger.reset()
        ledger.balances[Triple("1001", "CZK", valueDate)] = amount.multiply(BigDecimal("1.5"))
        val rita = Actor("rita.releaser", ActorType.HUMAN)
        val pool = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        val results = listOf(payA, payB).map { id ->
            CompletableFuture.supplyAsync({
                gate.await()
                runCatching { onContext { payments.release(UUID.fromString(id), rita) } }
            }, pool)
        }
        gate.countDown()
        val outcomes = results.map { it.get() }
        pool.shutdown()
        assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
        assertThat(
            outcomes.single {
                it.isFailure
            }.exceptionOrNull(),
        ).isInstanceOf(FundingExceededException::class.java)
        released = if (outcomes[0].isSuccess) payA else payB
        assertThat(count("select count(*) from treasury_payments where status = 'RAIL_NOT_SUPPORTED'")).isEqualTo(1)
    }

    @Test
    @Order(13)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `13 - a released payment is never cancelled, so its flows stay linked`() {
        post("/payments/$released/cancel", """{"reason":"changed my mind"}""").then().statusCode(409)
        assertThat(count("select count(*) from treasury_payment_flows where payment_id = '$released'")).isGreaterThan(0)
    }

    @Test
    @Order(14)
    @TestSecurity(user = "rita.releaser", roles = ["ROLE_TREASURY_APPROVER"])
    fun `14 - its releaser cannot resolve it`() {
        post("/payments/$released/resolve", """{"outcome":"MANUALLY_SETTLED","reason":"paid by hand"}""")
            .then().statusCode(422).body("error", equalTo("FOUR_EYES_VIOLATION"))
    }

    @Test
    @Order(15)
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `15 - an independent human resolves it MANUALLY_SETTLED - flows SETTLED, links kept, nothing proposed again`() {
        post(
            "/payments/$released/resolve",
            """{"outcome":"MANUALLY_SETTLED","reason":"instructed by hand, confirmed"}""",
        )
            .then().statusCode(200).body("status", equalTo("MANUALLY_SETTLED"))
            .body("statusReason", containsString("instructed by hand"))
        assertThat(count("select count(*) from treasury_payment_flows where payment_id = '$released'")).isGreaterThan(0)
        assertThat(
            count(
                "select count(*) from deal_cash_flows f join treasury_payment_flows l on l.flow_id = f.flow_id " +
                    "where l.payment_id = '$released' and f.state <> 'SETTLED'",
            ),
        ).isEqualTo(0)
        assertThat(
            count(
                "select count(*) from treasury_outbox where aggregate_id = '$released' " +
                    "and event_type = 'treasury.payment.status-changed.v1'",
            ),
        ).isEqualTo(4) // PROPOSED, RELEASED, RAIL_NOT_SUPPORTED, MANUALLY_SETTLED
    }

    private fun outFlow(deal: String): String = jdbc { c ->
        c.prepareStatement(
            "select flow_id from deal_cash_flows where deal_id = '$deal' and period_index = 0 and kind = 'INTEREST' " +
                "and direction = 'OUT'",
        ).executeQuery().use { rs ->
            rs.next()
            rs.getString(1)
        }
    }

    private fun paymentOf(flow: String): String = jdbc { c ->
        c.prepareStatement("select payment_id from treasury_payment_flows where flow_id = '$flow'").executeQuery()
            .use { rs ->
                rs.next()
                rs.getString(1)
            }
    }

    private fun count(sql: String): Int = jdbc { c ->
        c.prepareStatement(sql).executeQuery().use { rs ->
            rs.next()
            rs.getInt(1)
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
        lateinit var dealA: String
        lateinit var dealB: String
        lateinit var outFlowA: String
        lateinit var outFlowB: String
        lateinit var overrideId: String
        lateinit var payA: String
        lateinit var payB: String
        lateinit var released: String
        lateinit var valueDate: LocalDate
    }
}
