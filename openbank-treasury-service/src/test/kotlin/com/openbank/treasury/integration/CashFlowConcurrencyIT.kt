// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.application.port.out.CashFlowConflictException
import com.openbank.treasury.application.port.out.CashFlowRepository
import com.openbank.treasury.application.port.out.DealEvent
import com.openbank.treasury.application.port.out.FixingFlowRepository
import com.openbank.treasury.application.port.out.PaymentRepository
import com.openbank.treasury.domain.cashflow.CashFlow
import com.openbank.treasury.domain.cashflow.CashFlowOverride
import com.openbank.treasury.domain.cashflow.CashFlowState
import com.openbank.treasury.domain.cashflow.FlowDirection
import com.openbank.treasury.domain.cashflow.RateSource
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.payment.Payment
import com.openbank.treasury.domain.payment.PaymentStatus
import com.openbank.treasury.domain.payment.SsiSnapshot
import com.openbank.treasury.domain.reference.SettlementRail
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
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Real Postgres: the payment link and the fixing provenance of a stored flow survive every racing
 * writer. Two concurrent proposals for one flow make exactly one payment; a regenerated schedule
 * row for an already-paid slot is neither proposable nor linkable; a fixing racing an override keeps
 * its provenance (the stale override is refused, never written over it).
 */
@QuarkusTest
@QuarkusTestResource(CashFlowLedgerApiIT.NoDispatch::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestProfile(CashFlowConcurrencyIT.OwnDatabase::class)
class CashFlowConcurrencyIT {
    /** Its own application (and Postgres): the reference data it activates must not leak into other ITs. */
    class OwnDatabase : QuarkusTestProfile

    @Inject
    lateinit var payments: PaymentRepository

    @Inject
    lateinit var fixing: FixingFlowRepository

    @Inject
    lateinit var cashFlows: CashFlowRepository

    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()

    private fun <T> onContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        Uni.createFrom().completionStage(CoroutineScope(Dispatchers.Unconfined).future { block() })
    }

    private fun post(path: String, body: String? = null) = given()
        .contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
        .apply { if (body != null) body(body) }
        .`when`().post("/api/v1/treasury$path")

    private fun payment(flow: CashFlow) = Payment(
        paymentId = UUID.randomUUID(),
        status = PaymentStatus.PROPOSED,
        direction = FlowDirection.OUT,
        counterpartyId = "SIMBK-A",
        currency = flow.currency,
        amount = checkNotNull(flow.amount),
        valueDate = flow.paymentDate,
        ssi = SsiSnapshot(SettlementRail.CERTIS, "CZ6508000000192000145399", null, 1),
        nettedFlowIds = listOf(flow.flowId),
        dealIds = listOf(flow.dealId),
        termAuthors = setOf("dana.dealer"),
        idempotencyKey = "treasury:${flow.dealId}:${flow.flowId}:payment:${UUID.randomUUID()}",
        createdAt = Instant.now(),
    )

    @Test
    @Order(1)
    @TestSecurity(user = "sam.senior", roles = ["ROLE_TREASURY_REFERENCE_EDITOR", "ROLE_TREASURY_SENIOR_APPROVER"])
    fun `1 - the IRS mandate is active`() {
        val active =
            count(
                "select count(*) from reference_records where kind = 'product-type' and record_key = 'IRS' and status = 'ACTIVE'",
            )
        if (active == 0) {
            val pending =
                count(
                    "select count(*) from reference_records where kind = 'product-type' and record_key = 'IRS' and status = 'PENDING'",
                )
            if (pending == 0) {
                post(
                    "/reference/product-type",
                    """{"key":"IRS","validFrom":"$today","reason":"open IRS dealing",
                       "payload":{"code":"IRS","permitted":true,"maxPrincipal":{"CZK":500000000.00},"maxTenorDays":3660}}""",
                ).then().statusCode(201)
            }
        }
    }

    @Test
    @Order(2)
    @TestSecurity(user = "anna.senior2", roles = ["ROLE_TREASURY_SENIOR_APPROVER"])
    fun `2 - activated by a different senior`() {
        val v = jdbc { c ->
            c.prepareStatement(
                "select max(version) from reference_records where kind = 'product-type' and record_key = 'IRS' and status = 'PENDING'",
            ).executeQuery().use { rs ->
                rs.next()
                rs.getObject(1) as? Int
            }
        }
        v?.let { post("/reference/product-type/IRS/versions/$it/approve").then().statusCode(200) }
    }

    @Test
    @Order(3)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_DEALER"])
    fun `3 - an IRS with fixed and floating coupons is booked`() {
        val start = today.plusDays(30)
        val end = start.plusYears(1)
        val legs = """
            [{"payReceive":"PAY","currency":"CZK","notional":10000000.00,"accrualStart":"$start","accrualEnd":"$end",
              "accrualFrequency":"QUARTERLY","paymentFrequency":"QUARTERLY","dayCount":"ACT/360","rate":{"type":"FIXED","rate":4.0}},
             {"payReceive":"RECEIVE","currency":"CZK","notional":10000000.00,"accrualStart":"$start","accrualEnd":"$end",
              "accrualFrequency":"QUARTERLY","paymentFrequency":"QUARTERLY","dayCount":"ACT/360",
              "rate":{"type":"FLOATING","index":"PRIBOR_3M","spread":0.10,"resetFrequency":"QUARTERLY"}}]
        """.trimIndent()
        deal = post(
            "/deals",
            """{"product":"IRS","counterpartyId":"SIMBK-A","bookId":"TRADING-BOOK","calendars":["CERTIS"],
               "businessDayConvention":"MODIFIED_FOLLOWING","legs":$legs}""",
        ).then().statusCode(201).extract().path("dealId")
        post("/deals/$deal/submit").then().statusCode(200)
        jdbc { c -> c.prepareStatement("update deals set state = 'BOOKED' where deal_id = '$deal'").executeUpdate() }
    }

    @Test
    @Order(4)
    fun `4 - two concurrent proposals for the same flow make exactly one payment`() {
        val flow = onContext { cashFlows.flows(UUID.fromString(deal), null) }
            .first { it.state == CashFlowState.FIXED && it.direction == FlowDirection.OUT }
        fixedFlow = flow
        val pool = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        val results = (1..2).map {
            CompletableFuture.supplyAsync({
                gate.await()
                runCatching { onContext { payments.save(payment(flow), null, null) } }
            }, pool)
        }
        gate.countDown()
        val outcomes = results.map { it.get() }
        pool.shutdown()
        assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
        assertThat(count("select count(*) from treasury_payment_flows where flow_id = '${flow.flowId}'")).isEqualTo(1)
        assertThat(
            count("select count(*) from deal_cash_flows where flow_id = '${flow.flowId}' and payment_id is not null"),
        ).isEqualTo(1)
        // A sequential third attempt is refused too.
        assertThatThrownBy { onContext { payments.save(payment(flow), null, null) } }.isNotNull()
    }

    @Test
    @Order(5)
    fun `5 - a regenerated row for the paid slot is neither proposable nor linkable, and the link survives`() {
        val f = fixedFlow
        val newId = UUID.randomUUID()
        jdbc { c ->
            c.prepareStatement(
                "insert into deal_cash_flows (id, $COLS, row_version) " +
                    "select nextval('deal_cash_flows_seq'), " + COLS.replace("flow_id", "'$newId'")
                        .replace("schedule_version", "schedule_version + 1") + ", 0 " +
                    "from deal_cash_flows where flow_id = '${f.flowId}'",
            ).executeUpdate()
            c.prepareStatement(
                "update deals set schedule_version = schedule_version + 1 where deal_id = '$deal'",
            ).executeUpdate()
        }
        val proposable = onContext { payments.unpaidFixedFlows(f.paymentDate, f.paymentDate) }
        assertThat(proposable.map { it.flowId }).doesNotContain(newId, f.flowId)
        val regenerated = f.copy(flowId = newId, scheduleVersion = f.scheduleVersion + 1)
        assertThatThrownBy { onContext { payments.save(payment(regenerated), null, null) } }.isNotNull()
        assertThat(
            count("select count(*) from deal_cash_flows where flow_id = '${f.flowId}' and payment_id is not null"),
        ).isEqualTo(1)
    }

    @Test
    @Order(6)
    fun `6 - a fixing racing an override keeps its provenance, and a second fixing is refused`() {
        val stale = onContext { cashFlows.flows(UUID.fromString(deal), 1) }
            .first { it.state == CashFlowState.PROJECTED && it.fixingDate != null }
        val sha = "a".repeat(64)
        val fixed = stale.copy(
            state = CashFlowState.FIXED,
            rate = BigDecimal("3.62"),
            amount = BigDecimal("91500.00"),
            rateSource = RateSource.OBSERVED,
            fixingSource = "IMPORT",
            fixingSha256 = sha,
            fixedAt = Instant.now(),
        )
        onContext { fixing.applyFixing(stale.dealId, listOf(fixed), DealEvent("treasury.cashflow.fixed.v1", "{}")) }
        val agent = Actor("bert.backoffice", ActorType.HUMAN)
        val override = CashFlowOverride.propose(
            UUID.randomUUID(),
            stale,
            stale.paymentDate.plusDays(1),
            null,
            "move one day",
            agent,
            Instant.now(),
        )
        assertThatThrownBy {
            onContext {
                cashFlows.saveOverride(
                    override,
                    null,
                    stale.copy(paymentDate = stale.paymentDate.plusDays(1)),
                    null,
                    null,
                )
            }
        }.isInstanceOf(CashFlowConflictException::class.java)
        assertThat(
            count(
                "select count(*) from deal_cash_flows where flow_id = '${stale.flowId}' " +
                    "and fixing_sha256 = '$sha' and state = 'FIXED'",
            ),
        ).isEqualTo(1)
        assertThatThrownBy {
            onContext { fixing.applyFixing(stale.dealId, listOf(fixed), DealEvent("treasury.cashflow.fixed.v1", "{}")) }
        }.isInstanceOf(CashFlowConflictException::class.java)
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
        const val COLS = "flow_id, deal_id, schedule_version, leg_index, period_index, kind, direction, currency, " +
            "accrual_start, accrual_end, fixing_date, payment_date, notional, rate, rate_source, amount, state, " +
            "leg_hash, manual"
        lateinit var deal: String
        lateinit var fixedFlow: CashFlow
    }
}
