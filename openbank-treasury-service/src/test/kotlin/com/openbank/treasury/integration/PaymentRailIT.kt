// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.application.usecase.InstitutionTransferStatus
import com.openbank.treasury.application.usecase.PaymentService
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.payment.FundingExceededException
import com.openbank.treasury.domain.payment.RailStatus
import com.openbank.treasury.domain.payment.RailStatusConflictException
import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.quarkus.vertx.VertxContextSupport
import io.restassured.RestAssured.given
import io.smallrye.mutiny.Uni
import io.smallrye.reactive.messaging.memory.InMemoryConnector
import jakarta.enterprise.inject.Any
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
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Contract 9 over real HTTP, Postgres and the real incoming channel. The release writes RELEASED and
 * `treasury.payment.released.v1` in ONE transaction (and a refused release writes neither); the
 * rail's status events move the payment RELEASED -> SENT -> SETTLED, idempotent on (paymentId,
 * status) under replay and under two concurrent deliveries; a REJECTED payment is FAILED and frees
 * its funding reservation; a status carrying another payment's key is refused (DLQ), never applied.
 */
@QuarkusTest
@QuarkusTestResource(CashFlowLedgerApiIT.NoDispatch::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestProfile(PaymentRailIT.OwnDatabase::class)
class PaymentRailIT {
    class OwnDatabase : QuarkusTestProfile

    @Inject
    lateinit var ledger: FakeLedgerRead

    @Inject
    lateinit var payments: PaymentService

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var postings: FakeLedger

    @Inject
    lateinit var paymentRepository: com.openbank.treasury.application.port.out.PaymentRepository

    @Inject
    @Any
    lateinit var connector: InMemoryConnector

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
    fun `1 - IRS mandate and a CZK SSI with the creditor agent's BIC`() {
        post(
            "/reference/product-type",
            """{"key":"IRS","validFrom":"$today","reason":"open IRS dealing",
               "payload":{"code":"IRS","permitted":true,"maxPrincipal":{"CZK":500000000.00},"maxTenorDays":3660}}""",
        ).then().statusCode(201)
        post(
            "/reference/ssi",
            """{"key":"SIMBK-A:CZK","validFrom":"$today","reason":"standard settlement instruction",
               "payload":{"counterpartyId":"SIMBK-A","currency":"CZK","rail":"CERTIS","bic":"SIMBCZPP",
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
    fun `3 - two IRS are booked, coupons due tomorrow, and proposed`() {
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
    }

    @Test
    @Order(4)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `4 - payments are proposed`() {
        post("/payments/propose?date=$today").then().statusCode(200)
        payA = paymentOf(outFlow(dealA))
        payB = paymentOf(outFlow(dealB))
    }

    @Test
    @Order(5)
    fun `5 - a release refused by funding writes neither the status nor the rail instruction`() {
        ledger.reset()
        ledger.balances[Triple("1001", "CZK", valueDate)] = BigDecimal("1.00")
        assertThatThrownBy { onContext { payments.release(UUID.fromString(payA), RITA) } }
            .isInstanceOf(FundingExceededException::class.java)
        assertThat(status(payA)).isEqualTo("PROPOSED")
        assertThat(outbox(payA, "treasury.payment.released.v1")).isEqualTo(0)
    }

    @Test
    @Order(6)
    @TestSecurity(user = "rita.releaser", roles = ["ROLE_TREASURY_PAYMENT_RELEASER"])
    fun `6 - a release writes RELEASED and treasury_payment_released_v1 together, rail CERTIS from the SSI`() {
        ledger.balances[Triple("1001", "CZK", valueDate)] = BigDecimal("1000000000.00")
        post("/payments/$payA/release").then().statusCode(200).body("status", equalTo("RELEASED"))
        post("/payments/$payB/release").then().statusCode(200).body("status", equalTo("RELEASED"))
        assertThat(
            count("select count(*) from treasury_payments where payment_id = '$payA' and transfer_rail = 'CERTIS'"),
        )
            .isEqualTo(1)
        assertThat(outbox(payA, "treasury.payment.released.v1")).isEqualTo(1)
        val payload = objectMapper.readTree(
            jdbc { c ->
                c.prepareStatement(
                    "select payload from treasury_outbox where aggregate_id = '$payA' " +
                        "and event_type = 'treasury.payment.released.v1'",
                ).executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            },
        )
        assertThat(payload["rail"].asText()).isEqualTo("CERTIS")
        assertThat(payload["idempotencyKey"].asText()).isEqualTo(key(payA))
        assertThat(payload["creditor"]["bic"].asText()).isEqualTo("SIMBCZPP")
        assertThat(payload["debtor"]["glAccountingClass"].asText()).isEqualTo("NOSTRO_ACCOUNT")
        assertThat(payload["amount"].asText()).matches("\\d+\\.\\d{2}")
        assertThat(payload["releasedBy"].asText()).isEqualTo("rita.releaser")
    }

    @Test
    @Order(7)
    fun `7 - ACCEPTED through the real channel records the rail reference and stays RELEASED`() {
        connector.source<String>(
            "institution-transfer-status-in",
        ).runOnVertxContext(true).send(statusJson(payA, "ACCEPTED", "SCHEME-REF-1"))
        val deadline = System.nanoTime() + POLL_NANOS
        while (System.nanoTime() < deadline &&
            count(
                "select count(*) from treasury_payments where payment_id = '$payA' and rail_reference = 'SCHEME-REF-1'",
            ) ==
            0
        ) {
            Thread.sleep(POLL_STEP_MS)
        }
        assertThat(
            count(
                "select count(*) from treasury_payments where payment_id = '$payA' and rail_reference = 'SCHEME-REF-1'",
            ),
        )
            .isEqualTo(1)
        assertThat(status(payA)).isEqualTo("RELEASED")
    }

    @Test
    @Order(8)
    fun `8 - SENT twice is applied once, and SENT posts the outflow journal so funding stops counting it`() {
        // The deal has settled (its value date came): the matrix posts its flows' PAYMENT journals.
        jdbc { c -> c.prepareStatement("update deals set state = 'SETTLED' where deal_id = '$dealA'").executeUpdate() }
        postings.reset()
        val amountA = onContext { payments.find(UUID.fromString(payA)) }.amount
        val pendingBefore = onContext { paymentRepository.pendingOut("CZK", valueDate) }
        val sent = onContext { payments.onRailStatus(event(payA, RailStatus.SENT)) }
        assertThat(sent?.status?.name).isEqualTo("SENT")
        val outKey = "treasury:$dealA:payment:${outFlow(dealA)}"
        assertThat(postings.journals.keys).contains(outKey)
        assertThat(
            postings.journals.getValue(outKey).second.lines.single { it.accountingClass == "NOSTRO_ACCOUNT" }.side.name,
        ).isEqualTo("CREDIT")
        assertThat(count("select count(*) from deal_journals where idempotency_key = '$outKey'")).isEqualTo(1)
        // The ledger balance now carries payA's outflow; the funding pending must not count it again.
        assertThat(onContext { paymentRepository.pendingOut("CZK", valueDate) })
            .isEqualByComparingTo(pendingBefore - amountA)
        assertThat(onContext { payments.onRailStatus(event(payA, RailStatus.SENT)) }).isNull()
        assertThat(onContext { payments.onRailStatus(event(payA, RailStatus.ACCEPTED)) }).isNull()
        assertThat(statusEvents(payA, "SENT")).isEqualTo(1)
        // A replayed SENT re-offers the same keys: the ledger answers its original journal, nothing new.
        assertThat(postings.journals.keys.count { it.endsWith(":payment:${outFlow(dealA)}") }).isEqualTo(1)
    }

    @Test
    @Order(9)
    fun `9 - two SETTLED delivered concurrently - one applies, the other is a replay, flows SETTLED once`() {
        val pool = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        val results = (1..2).map {
            CompletableFuture.supplyAsync({
                gate.await()
                runCatching { onContext { payments.onRailStatus(event(payA, RailStatus.SETTLED)) } }
            }, pool)
        }
        gate.countDown()
        val outcomes = results.map { it.get() }
        pool.shutdown()
        assertThat(outcomes).allMatch { it.isSuccess }
        assertThat(outcomes.count { it.getOrNull() != null }).isEqualTo(1)
        assertThat(status(payA)).isEqualTo("SETTLED")
        assertThat(statusEvents(payA, "SETTLED")).isEqualTo(1)
        assertThat(
            count(
                "select count(*) from deal_cash_flows f join treasury_payment_flows l on l.flow_id = f.flow_id " +
                    "where l.payment_id = '$payA' and f.state <> 'SETTLED'",
            ),
        ).isEqualTo(0)
        // A late REJECTED contradicts the settlement: refused, never applied.
        assertThatThrownBy { onContext { payments.onRailStatus(event(payA, RailStatus.REJECTED)) } }
            .isInstanceOf(RailStatusConflictException::class.java)
        assertThat(status(payA)).isEqualTo("SETTLED")
    }

    @Test
    @Order(10)
    fun `10 - a status carrying another payment's key is refused`() {
        assertThatThrownBy {
            onContext { payments.onRailStatus(event(payB, RailStatus.SENT).copy(idempotencyKey = key(payA))) }
        }.isInstanceOf(RailStatusConflictException::class.java)
        assertThat(status(payB)).isEqualTo("RELEASED")
    }

    @Test
    @Order(11)
    fun `11 - REJECTED fails the payment, keeps its flows linked and frees its funding reservation`() {
        val before = onContext { payments.find(UUID.fromString(payB)) }
        val pendingBefore = pendingOut()
        onContext {
            payments.onRailStatus(event(payB, RailStatus.REJECTED).copy(reasonCode = "AC04", reason = "closed"))
        }
        assertThat(status(payB)).isEqualTo("FAILED")
        assertThat(count("select count(*) from treasury_payment_flows where payment_id = '$payB'")).isGreaterThan(0)
        assertThat(pendingOut()).isEqualByComparingTo(pendingBefore - before.amount)
        assertThat(onContext { payments.onRailStatus(event(payB, RailStatus.REJECTED)) }).isNull()
    }

    private fun pendingOut(): BigDecimal = jdbc { c ->
        c.prepareStatement(
            "select coalesce(sum(amount), 0) from treasury_payments where direction = 'OUT' and status in " +
                "('RELEASED', 'SENT', 'RAIL_NOT_SUPPORTED')",
        ).executeQuery().use { rs ->
            rs.next()
            rs.getBigDecimal(1)
        }
    }

    private fun event(pay: String, s: RailStatus) = InstitutionTransferStatus(
        paymentId = UUID.fromString(pay),
        idempotencyKey = key(pay),
        status = s,
        messageType = "pacs.009",
        schemeReference = null,
        reasonCode = null,
        reason = null,
        at = null,
        synthetic = true,
    )

    private fun statusJson(pay: String, s: String, ref: String) =
        """{"paymentId":"$pay","idempotencyKey":"${key(pay)}","status":"$s","messageType":"pacs.009",
           "schemeReference":"$ref","reasonCode":null,"reason":null,"at":"2026-10-19T10:00:00Z","synthetic":true}"""

    private fun key(pay: String): String = jdbc { c ->
        c.prepareStatement("select idempotency_key from treasury_payments where payment_id = '$pay'").executeQuery()
            .use { rs ->
                rs.next()
                rs.getString(1)
            }
    }

    private fun status(pay: String): String = jdbc { c ->
        c.prepareStatement("select status from treasury_payments where payment_id = '$pay'").executeQuery().use { rs ->
            rs.next()
            rs.getString(1)
        }
    }

    private fun outbox(pay: String, type: String) =
        count("select count(*) from treasury_outbox where aggregate_id = '$pay' and event_type = '$type'")

    private fun statusEvents(pay: String, s: String) = count(
        "select count(*) from treasury_outbox where aggregate_id = '$pay' " +
            "and event_type = 'treasury.payment.status-changed.v1' and payload like '%\"status\":\"$s\"%'",
    )

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
        const val POLL_NANOS = 10_000_000_000L
        const val POLL_STEP_MS = 100L
        val RITA = Actor("rita.releaser", ActorType.HUMAN)
        lateinit var dealA: String
        lateinit var dealB: String
        lateinit var payA: String
        lateinit var payB: String
        lateinit var valueDate: LocalDate
    }
}
