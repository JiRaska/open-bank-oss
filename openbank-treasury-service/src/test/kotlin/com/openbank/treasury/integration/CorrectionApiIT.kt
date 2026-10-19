// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.application.port.out.CashFlowConflictException
import com.openbank.treasury.application.port.out.FixingFlowRepository
import com.openbank.treasury.application.usecase.CorrectionService
import com.openbank.treasury.application.usecase.PaymentService
import com.openbank.treasury.domain.cashflow.CashFlowState
import com.openbank.treasury.domain.cashflow.RateSource
import com.openbank.treasury.domain.model.Actor
import com.openbank.treasury.domain.model.ActorType
import com.openbank.treasury.domain.model.CorrectionBlockedByPaymentException
import com.openbank.treasury.domain.payment.PaymentStaleException
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
import org.hamcrest.Matchers.greaterThan
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
 * ADR-0344 D7 corrections over real HTTP and Postgres. A settled IRS (pay 4 % fixed, receive 3 %
 * fixed, quarterly, first coupons tomorrow) has accrued 3 months and carries PROPOSED payments.
 *
 * - four-eyes: the proposer, the deal's creator and an AI agent cannot approve;
 * - a correction and a release of a payment on a CHANGED flow race: exactly one wins and the other
 *   gets a typed 409; either way the database is consistent;
 * - a released payment on an UNCHANGED flow does not block, and follows its flow onto the new version;
 *   the PROPOSED payment of the changed flow is withdrawn;
 * - the delta journals are exactly the accrual differences, posted once and recorded;
 * - a fixing of a superseded schedule and a commit over a moved schedule are both refused.
 */
@QuarkusTest
@QuarkusTestResource(CashFlowLedgerApiIT.NoDispatch::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestProfile(CorrectionApiIT.OwnDatabase::class)
@Suppress("LongMethod") // each ordered step is one scenario, asserted end to end
class CorrectionApiIT {
    class OwnDatabase : QuarkusTestProfile

    @Inject
    lateinit var ledgerRead: FakeLedgerRead

    @Inject
    lateinit var ledger: FakeLedger

    @Inject
    lateinit var payments: PaymentService

    @Inject
    lateinit var corrections: CorrectionService

    @Inject
    lateinit var deals: com.openbank.treasury.application.port.`in`.TreasuryDealUseCase

    @Inject
    lateinit var fixingFlows: FixingFlowRepository

    @Inject
    lateinit var cashFlows: com.openbank.treasury.application.port.out.CashFlowRepository

    @Inject
    lateinit var correctionRepo: com.openbank.treasury.application.port.out.CorrectionRepository

    private val today: LocalDate = AccountingClock.bank(Clock.systemUTC()).today()
    private val tomorrow: LocalDate = today.plusDays(1)

    private fun post(path: String, body: String? = null) = given()
        .contentType("application/json").header("Idempotency-Key", UUID.randomUUID().toString())
        .apply { if (body != null) body(body) }
        .`when`().post("/api/v1/treasury$path")

    private fun <T> onContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        Uni.createFrom().completionStage(CoroutineScope(Dispatchers.Unconfined).future { block() })
    }

    private fun legs(payRate: String, receiveRate: String): String {
        val start = tomorrow.minusMonths(3)
        val end = start.plusYears(1)
        return """
            [{"payReceive":"PAY","currency":"CZK","notional":10000000.00,"accrualStart":"$start","accrualEnd":"$end",
              "accrualFrequency":"QUARTERLY","paymentFrequency":"QUARTERLY","dayCount":"ACT/360","rate":{"type":"FIXED","rate":$payRate}},
             {"payReceive":"RECEIVE","currency":"CZK","notional":10000000.00,"accrualStart":"$start","accrualEnd":"$end",
              "accrualFrequency":"QUARTERLY","paymentFrequency":"QUARTERLY","dayCount":"ACT/360","rate":{"type":"FIXED","rate":$receiveRate}}]
        """.trimIndent()
    }

    @Test
    @Order(1)
    @TestSecurity(user = "erik.editor", roles = ["ROLE_TREASURY_REFERENCE_EDITOR"])
    fun `1 - the IRS mandate and the counterparty's CZK settlement instruction are proposed`() {
        val from = today.minusMonths(4)
        post(
            "/reference/product-type",
            """{"key":"IRS","validFrom":"$from","reason":"open IRS dealing",
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
    fun `3 - the dealer books the IRS`() {
        deal = post(
            "/deals",
            """{"product":"IRS","counterpartyId":"SIMBK-A","bookId":"TRADING-BOOK","calendars":["CERTIS"],
               "tradeDate":"${tomorrow.minusMonths(3).minusDays(2)}","businessDayConvention":"UNADJUSTED",
               "legs":${legs("4.0", "3.0")}}""",
        ).then().statusCode(201).extract().path("dealId")
        post("/deals/$deal/submit").then().statusCode(200)
        jdbc { it.prepareStatement("update deals set state = 'CONFIRMED' where deal_id = '$deal'").executeUpdate() }
    }

    @Test
    @Order(4)
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `4 - it settles, accrues three months and its first coupons are proposed for payment`() {
        ledger.reset()
        post("/deals/$deal/settle").then().statusCode(200)
        val run = onContext { deals.accrueInterest(today) }
        assertThat(run.failures).isEmpty()
        assertThat(run.journals).isGreaterThan(MIN_ACCRUAL_DAYS)
        onContext { payments.propose(today) }
        payOut = paymentOf(flow(0))
        payIn = paymentOf(flow(1))
        assertThat(payOut).isNotEqualTo(payIn)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `5 - back office proposes raising the receive rate, with a preview of flows and delta journals`() {
        correctionIn = post(
            "/deals/$deal/corrections",
            """{"changes":{"legs":${legs("4.0", "3.5")}},"reason":"receive rate captured wrong",
               "effectiveFrom":"${tomorrow.minusMonths(3)}"}""",
        ).then().statusCode(201)
            .body("status", equalTo("PENDING"))
            .body("preview.deltaJournals.size()", greaterThan(MIN_ACCRUAL_DAYS))
            .body("preview.changedFlows.size()", greaterThan(0))
            .extract().path("correctionId")
        // Only the receive leg (leg 1) changed.
        val legsChanged: List<String> = given().`when`().get("/api/v1/treasury/deals/$deal/corrections/$correctionIn")
            .then().statusCode(200).extract().path("preview.changedFlows.slot")
        assertThat(legsChanged).allMatch { it.startsWith("1/") }
        // A second pending correction on the same deal is refused.
        post(
            "/deals/$deal/corrections",
            """{"changes":{"note":"x"},"reason":"again","effectiveFrom":"$today"}""",
        ).then().statusCode(409).body("error", equalTo("CORRECTION_STALE"))
    }

    @Test
    @Order(6)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_APPROVER"])
    fun `6 - its proposer cannot approve it`() {
        post("/deals/$deal/corrections/$correctionIn/approve").then().statusCode(422)
            .body("error", equalTo("FOUR_EYES_VIOLATION"))
    }

    @Test
    @Order(7)
    @TestSecurity(user = "dana.dealer", roles = ["ROLE_TREASURY_APPROVER"])
    fun `7 - the deal's creator cannot approve it`() {
        post("/deals/$deal/corrections/$correctionIn/approve").then().statusCode(422)
            .body("error", equalTo("FOUR_EYES_VIOLATION"))
    }

    @Test
    @Order(8)
    @TestSecurity(user = "agent:treasury-drafter", roles = ["ROLE_TREASURY_APPROVER"])
    fun `8 - an AI agent can never approve it`() {
        post("/deals/$deal/corrections/$correctionIn/approve").then().statusCode(403)
    }

    @Test
    @Order(9)
    fun `9 - the unchanged leg's payment is released first - it does not block the correction`() {
        ledgerRead.balances[Triple("1001", "CZK", tomorrow)] = BigDecimal("1000000000.00")
        onContext { payments.release(UUID.fromString(payOut), Actor("rita.releaser", ActorType.HUMAN)) }
        assertThat(status(payOut)).isIn("RELEASED", "RAIL_NOT_SUPPORTED")
    }

    @Test
    @Order(10)
    @TestSecurity(user = "olga.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `10 - a second human approves - new version, PROPOSED payment of the changed flow withdrawn, deltas posted`() {
        val before = scheduleVersion()
        val accrualKeys = ledger.journals.keys.count { it.contains(":accrual:") }
        post("/deals/$deal/corrections/$correctionIn/approve").then().statusCode(200)
            .body("status", equalTo("POSTED")).body("newVersion", greaterThan(1))
        assertThat(scheduleVersion()).isEqualTo(before + 1)
        assertThat(status(payIn)).isEqualTo("CANCELLED")
        // The released payment of the unchanged flow follows it onto the new version.
        assertThat(
            count(
                "select count(*) from treasury_payment_flows pf join deal_cash_flows f on f.flow_id = pf.flow_id " +
                    "where pf.payment_id = '$payOut' and f.schedule_version = ${before + 1}",
            ),
        ).isEqualTo(1)
        val deltas = ledger.journals.keys.filter { it.contains(":correction:$correctionIn:") }
        assertThat(deltas).hasSizeGreaterThanOrEqualTo(accrualKeys)
        assertThat(count("select count(*) from deal_journals where event = 'CORRECTION' and deal_id = '$deal'"))
            .isEqualTo(deltas.size)
        // Every delta raises receive-leg interest income: Cr income, Dr accrued, nothing else.
        assertThat(
            ledger.journals.filterKeys {
                it in deltas
            }.values.flatMap { it.second.lines }.map { it.role.name }.toSet(),
        )
            .containsExactlyInAnyOrder("ACCRUED_INTEREST", "INTEREST_INCOME")
        assertThat(
            count(
                "select count(*) from treasury_outbox where event_type = 'treasury.deal.corrected.v1' and aggregate_id = '$deal'",
            ),
        ).isEqualTo(1)
    }

    @Test
    @Order(11)
    @TestSecurity(user = "bert.backoffice", roles = ["ROLE_TREASURY_BACK_OFFICE"])
    fun `11 - a correction of the pay leg, whose payment is released, is refused with nothing changed`() {
        correctionOut = post(
            "/deals/$deal/corrections",
            """{"changes":{"legs":${legs(
                "4.1",
                "3.5",
            )}},"reason":"pay rate captured wrong","effectiveFrom":"$today"}""",
        ).then().statusCode(201).extract().path("correctionId")
    }

    @Test
    @Order(12)
    @TestSecurity(user = "olga.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `12 - approval 409 PAYMENT_RELEASED, the correction stays PENDING and the deal unchanged`() {
        val before = scheduleVersion()
        post("/deals/$deal/corrections/$correctionOut/approve").then().statusCode(409)
            .body("error", equalTo("PAYMENT_RELEASED")).body("paymentId", equalTo(payOut))
        assertThat(scheduleVersion()).isEqualTo(before)
        assertThat(
            count(
                "select count(*) from deal_corrections where correction_id = '$correctionOut' and status = 'PENDING'",
            ),
        )
            .isEqualTo(1)
    }

    @Test
    @Order(13)
    @TestSecurity(user = "olga.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `13 - it is rejected with a reason`() {
        post("/deals/$deal/corrections/$correctionOut/reject", """{"reason":"pay leg was right"}""")
            .then().statusCode(200).body("status", equalTo("REJECTED"))
    }

    @Test
    @Order(14)
    fun `14 - a correction racing a release of a payment on the CHANGED flow - exactly one wins, typed 409`() {
        // The new receive-leg coupon gets a fresh PROPOSED payment; a correction of that leg races its release.
        onContext { payments.propose(today) }
        val p = paymentOf(flow(1))
        val bert = Actor("bert.backoffice", ActorType.HUMAN)
        val raise = onContext {
            corrections.propose(
                UUID.fromString(deal),
                com.openbank.treasury.domain.model.CorrectionTerms(
                    legs = deals.get(UUID.fromString(deal)).deal.legs.mapIndexed { i, l ->
                        if (i == 1) l.copy(rate = l.rate.copy(rate = BigDecimal("3.75"))) else l
                    },
                ),
                false,
                "race",
                today,
                bert,
                UUID.randomUUID().toString(),
            )
        }.correction
        val pool = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        val approve = CompletableFuture.supplyAsync({
            gate.await()
            runCatching {
                onContext {
                    corrections.approve(
                        UUID.fromString(deal),
                        raise.correctionId,
                        Actor("olga.approver", ActorType.HUMAN),
                        UUID.randomUUID().toString(),
                    )
                }
            }
        }, pool)
        val release = CompletableFuture.supplyAsync({
            gate.await()
            runCatching { onContext { payments.release(UUID.fromString(p), Actor("rita.releaser", ActorType.HUMAN)) } }
        }, pool)
        gate.countDown()
        val a = approve.get()
        val r = release.get()
        pool.shutdown()
        println(
            "CORRECTION-RACE approve=${a.isSuccess} release=${r.isSuccess} ${r.exceptionOrNull()} ${a.exceptionOrNull()}",
        )
        // An incoming payment may not be releasable at all; then only the correction can win.
        if (r.isFailure && r.exceptionOrNull() !is PaymentStaleException) {
            assertThat(a.isSuccess).isTrue()
            return
        }
        assertThat(listOf(a, r).count { it.isSuccess }).isEqualTo(1)
        if (a.isSuccess) {
            assertThat(r.exceptionOrNull()).isInstanceOf(PaymentStaleException::class.java)
            assertThat(status(p)).isEqualTo("CANCELLED")
        } else {
            assertThat(a.exceptionOrNull()).isInstanceOf(CorrectionBlockedByPaymentException::class.java)
            assertThat(
                count(
                    "select count(*) from deal_corrections where correction_id = '${raise.correctionId}' and status = 'PENDING'",
                ),
            )
                .isEqualTo(1)
        }
    }

    @Test
    @Order(15)
    fun `15 - a fixing of a superseded schedule, and a commit over a moved schedule, are refused`() {
        val current = scheduleVersion()
        val old = onContext { cashFlows.flows(UUID.fromString(deal), current - 1) }.first()
        // Make the superseded row look fixable, so ONLY the schedule-version guard can refuse it.
        jdbc {
            it.prepareStatement(
                "update deal_cash_flows set state = 'PROJECTED', rate_source = 'PROJECTED', fixing_sha256 = null " +
                    "where flow_id = '${old.flowId}'",
            ).executeUpdate()
        }
        val fixed = old.copy(
            state = CashFlowState.FIXED,
            rateSource = RateSource.OBSERVED,
            rate = old.rate ?: BigDecimal.ONE,
            amount = old.amount ?: BigDecimal.ONE,
            fixingSource = "MANUAL",
            fixingSha256 = "0".repeat(64),
            fixedAt = java.time.Instant.now(),
        )
        assertThatThrownBy {
            onContext {
                fixingFlows.applyFixing(
                    UUID.fromString(deal),
                    listOf(fixed),
                    com.openbank.treasury.application.port.out.DealEvent("treasury.cashflow.fixed.v1", "{}"),
                )
            }
        }.isInstanceOf(CashFlowConflictException::class.java)
        // The commit re-reads the base schedule under the deal lock: a write between the plan and the
        // commit (here: a fingerprint one behind) is a 409 and leaves the correction PENDING.
        val dealId = UUID.fromString(deal)
        val c = onContext {
            corrections.propose(
                dealId,
                com.openbank.treasury.domain.model.CorrectionTerms(note = "documentation only"),
                false,
                "note",
                today,
                Actor("bert.backoffice", ActorType.HUMAN),
                UUID.randomUUID().toString(),
            )
        }.correction
        val d = onContext { deals.get(dealId) }.deal
        val approved = c.approve(Actor("olga.approver", ActorType.HUMAN), d, emptySet(), java.time.Instant.now())
        val fingerprint = onContext { correctionRepo.fingerprint(dealId, current) }
        assertThatThrownBy {
            onContext {
                correctionRepo.approve(
                    com.openbank.treasury.application.port.out.CorrectionCommit(
                        approved,
                        d.correct(
                            c.terms,
                            false,
                            Actor("olga.approver", ActorType.HUMAN),
                            c.reason,
                            java.time.Instant.now(),
                        ),
                        null,
                        fingerprint - 1,
                        emptySet(),
                        emptyList(),
                        com.openbank.treasury.application.port.out.CommandKey(
                            UUID.randomUUID().toString(),
                            "X:${c.correctionId}",
                            dealId,
                        ),
                        "[]",
                    ),
                )
            }
        }.isInstanceOf(CashFlowConflictException::class.java)
        assertThat(
            count(
                "select count(*) from deal_corrections where correction_id = '${c.correctionId}' and status = 'PENDING'",
            ),
        )
            .isEqualTo(1)
        assertThat(scheduleVersion()).isEqualTo(current)
    }

    private fun flow(leg: Int): String = jdbc { c ->
        c.prepareStatement(
            "select f.flow_id from deal_cash_flows f join deals d on d.deal_id = f.deal_id " +
                "and d.schedule_version = f.schedule_version " +
                "where f.deal_id = '$deal' and f.leg_index = $leg and f.period_index = 0 and f.kind = 'INTEREST'",
        ).executeQuery().use { rs ->
            rs.next()
            rs.getString(1)
        }
    }

    private fun paymentOf(flow: String): String = jdbc { c ->
        c.prepareStatement("select payment_id from treasury_payment_flows where flow_id = '$flow'").executeQuery()
            .use { rs ->
                check(rs.next()) { "flow $flow is on no payment" }
                rs.getString(1)
            }
    }

    private fun status(payment: String): String = jdbc { c ->
        c.prepareStatement(
            "select status from treasury_payments where payment_id = '$payment'",
        ).executeQuery().use { rs ->
            rs.next()
            rs.getString(1)
        }
    }

    private fun scheduleVersion(): Int = count("select schedule_version from deals where deal_id = '$deal'")

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
        const val MIN_ACCRUAL_DAYS = 60
        lateinit var deal: String
        lateinit var payOut: String
        lateinit var payIn: String
        lateinit var correctionIn: String
        lateinit var correctionOut: String
    }
}
