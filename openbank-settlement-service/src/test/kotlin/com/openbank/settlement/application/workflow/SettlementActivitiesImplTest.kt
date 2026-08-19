// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.workflow

import com.openbank.libs.audit.AuditEvent
import com.openbank.libs.audit.AuditEventPublisher
import com.openbank.libs.audit.AuditResult
import com.openbank.settlement.application.port.out.CreditPort
import com.openbank.settlement.application.port.out.DebitPort
import com.openbank.settlement.application.port.out.LedgerPort
import com.openbank.settlement.application.port.out.SettlementMetricsPort
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.settlement.support.RecordingSettlementMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Also covers issue #1502: each state transition below must emit an [AuditEvent] onto the shared
 * libs audit pipeline so settlement-service has a DORA Art. 17 reconstructable audit trail.
 */
class SettlementActivitiesImplTest {

    private val settlementRepository: SettlementRepository = mockk(relaxed = true)
    private val debitPort: DebitPort = mockk(relaxed = true)
    private val creditPort: CreditPort = mockk(relaxed = true)
    private val ledgerPort: LedgerPort = mockk(relaxed = true)
    private val auditPublisher: AuditEventPublisher = mockk(relaxed = true)

    private lateinit var metrics: RecordingSettlementMetrics

    private lateinit var activities: SettlementActivitiesImpl

    private fun settlement(id: UUID, status: SettlementStatus) = Settlement(
        id = id,
        payerAccountId = UUID.randomUUID(),
        payeeAccountId = UUID.randomUUID(),
        amount = BigDecimal("10.00"),
        currency = "CZK",
        status = status,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @BeforeEach
    fun setUp() {
        // TestableActivities overrides runOnVertxContext to run synchronously — the production impl
        // needs a real Vert.x context (VertxContextSupport), which a plain unit test does not have.
        metrics = RecordingSettlementMetrics()
        activities =
            TestableActivities(settlementRepository, debitPort, creditPort, ledgerPort, auditPublisher, metrics)
        coEvery { settlementRepository.updateStatus(any(), any()) } answers {
            settlement(firstArg(), secondArg())
        }
    }

    @Test
    fun `debitPayer calls debit port and sets DEBITED status`() {
        val id = UUID.randomUUID()
        activities.debitPayer(id)
        coVerify { debitPort.debit(id) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.DEBITED) }
    }

    @Test
    fun `debitPayer publishes a settlement_debit audit event`() {
        val id = UUID.randomUUID()
        val events = mutableListOf<AuditEvent>()
        coEvery { auditPublisher.publish(capture(events)) } returns Unit

        activities.debitPayer(id)

        assertThat(events).singleElement().satisfies({ e ->
            assertThat(e.operation).isEqualTo("settlement.debit")
            assertThat(e.actorId).isEqualTo("settlement-service")
            assertThat(e.actorType).isEqualTo("SERVICE")
            assertThat(e.resourceType).isEqualTo("settlement")
            assertThat(e.resourceId).isEqualTo(id.toString())
            assertThat(e.result).isEqualTo(AuditResult.SUCCESS)
            assertThat(e.payload["status"]).isEqualTo("DEBITED")
        })
    }

    @Test
    fun `creditPayee calls credit port and sets CREDITED status`() {
        val id = UUID.randomUUID()
        activities.creditPayee(id)
        coVerify { creditPort.credit(id) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.CREDITED) }
    }

    @Test
    fun `bookToLedger calls ledger port and sets BOOKED status`() {
        val id = UUID.randomUUID()
        activities.bookToLedger(id)
        coVerify { ledgerPort.book(id) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.BOOKED) }
    }

    @Test
    fun `reverseDebit does not call debit port and sets REVERSED status`() {
        val id = UUID.randomUUID()
        activities.reverseDebit(id)
        coVerify(exactly = 0) { debitPort.debit(any()) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.REVERSED) }
    }

    @Test
    fun `reverseCredit does not call credit port and sets CREDITED_REVERSED status`() {
        val id = UUID.randomUUID()
        activities.reverseCredit(id)
        coVerify(exactly = 0) { creditPort.credit(any()) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.CREDITED_REVERSED) }
    }

    @Test
    fun `reverseBookToLedger does not call ledger port and sets LEDGER_REVERSED status`() {
        val id = UUID.randomUUID()
        activities.reverseBookToLedger(id)
        coVerify(exactly = 0) { ledgerPort.book(any()) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.LEDGER_REVERSED) }
    }

    @Test
    fun `rejectSettlement sets REJECTED status without port calls`() {
        val id = UUID.randomUUID()
        activities.rejectSettlement(id)
        coVerify(exactly = 0) { debitPort.debit(any()) }
        coVerify(exactly = 0) { creditPort.credit(any()) }
        coVerify { settlementRepository.updateStatus(id, SettlementStatus.REJECTED) }
        assertThat(metrics.transitions).containsExactly(SettlementStatus.REJECTED)
    }

    @Test
    fun `every saga transition is counted, compensations included`() {
        // settlement-service emitted no metric of any kind before #5705, so there was no series
        // that could tell "running normally" from "has booked nothing for six hours". The
        // compensating transitions get their own status values rather than folding into a failure
        // count — a reversal is a distinct thing to alert on from a rejection.
        val id = UUID.randomUUID()
        activities.debitPayer(id)
        activities.creditPayee(id)
        activities.bookToLedger(id)
        activities.reverseBookToLedger(id)
        activities.reverseCredit(id)
        activities.reverseDebit(id)
        activities.rejectSettlement(id)

        assertThat(metrics.transitions).containsExactly(
            SettlementStatus.DEBITED,
            SettlementStatus.CREDITED,
            SettlementStatus.BOOKED,
            SettlementStatus.LEDGER_REVERSED,
            SettlementStatus.CREDITED_REVERSED,
            SettlementStatus.REVERSED,
            SettlementStatus.REJECTED,
        )
    }

    @Test
    fun `rejectSettlement publishes a FAILURE settlement_reject audit event`() {
        val id = UUID.randomUUID()
        val events = mutableListOf<AuditEvent>()
        coEvery { auditPublisher.publish(capture(events)) } returns Unit

        activities.rejectSettlement(id)

        assertThat(events).singleElement().satisfies({ e ->
            assertThat(e.operation).isEqualTo("settlement.reject")
            assertThat(e.result).isEqualTo(AuditResult.FAILURE)
            assertThat(e.resourceId).isEqualTo(id.toString())
        })
    }
}

/** Runs the activity bodies synchronously, bypassing the real Vert.x-context bridge. */
private class TestableActivities(
    settlementRepository: SettlementRepository,
    debitPort: DebitPort,
    creditPort: CreditPort,
    ledgerPort: LedgerPort,
    auditPublisher: AuditEventPublisher,
    metrics: SettlementMetricsPort,
) : SettlementActivitiesImpl(settlementRepository, debitPort, creditPort, ledgerPort, auditPublisher, metrics) {
    override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
}
