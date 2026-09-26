// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.usecase

import com.openbank.libs.temporal.TemporalConfig
import com.openbank.settlement.application.port.`in`.OriginateSettlementCommand
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.application.workflow.LedgerSettlementActivities
import com.openbank.settlement.application.workflow.LedgerSettlementWorkflowImpl
import com.openbank.settlement.application.workflow.SettlementActivities
import com.openbank.settlement.application.workflow.SettlementWorkflowImpl
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementProtocol
import com.openbank.settlement.domain.model.SettlementStatus
import com.openbank.settlement.infrastructure.observability.SettlementMetricsAdapter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.Worker
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Coverage for [SettlementService.originate]: PENDING persistence + idempotent replay. Since ADR-0120
 * Phase 6 (issue #1917) retired the legacy saga, originate()'s follow-on settle() dispatches to the
 * Temporal workflow, so a fresh PENDING is driven against a real in-memory [TestWorkflowEnvironment];
 * a repeated key resolves to the existing terminal row without a second dispatch.
 */
class SettlementServiceOriginateTest {

    private val repo: SettlementRepository = mockk(relaxed = true)
    private val temporalConfig: TemporalConfig = mockk(relaxed = true)

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var worker: Worker
    private lateinit var service: SettlementService

    // A REAL metrics adapter over a real registry, not a mock: the point of these two assertions is
    // that originate() actually moves a counter, which a verified mock cannot establish.
    private val registry = SimpleMeterRegistry()
    private val metrics = SettlementMetricsAdapter().apply { bindTo(registry) }

    private fun originatedCount(outcome: String): Double = registry.find(SettlementMetricsAdapter.ORIGINATED_METRIC)
        .tag("outcome", outcome)
        .counter()
        ?.count() ?: 0.0

    private companion object {
        const val TASK_QUEUE = "openbank-settlement"
    }

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance()
        worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(
            SettlementWorkflowImpl::class.java,
            LedgerSettlementWorkflowImpl::class.java,
        )
        worker.registerActivitiesImplementations(
            RelaxedActivities(),
            mockk<LedgerSettlementActivities>(relaxed = true),
        )
        env.start()
        every { temporalConfig.taskQueue() } returns TASK_QUEUE
        service = SettlementService(repo, temporalConfig, env.workflowClient, metrics)
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    @Test
    fun `originate persists a PENDING settlement from the command and starts settlement`() {
        val created = slot<Settlement>()
        // No existing settlement for the key (dedup miss) until create captures it.
        coEvery { repo.findById(any()) } answers { if (created.isCaptured) created.captured else null }
        coEvery { repo.create(capture(created)) } answers { created.captured }

        val payer = UUID.randomUUID()
        val payee = UUID.randomUUID()
        val result = runBlocking {
            service.originate(OriginateSettlementCommand("e2e-key-1", payer, payee, BigDecimal("250.00"), "CZK"))
        }

        // The persisted settlement reflects the command and starts PENDING; settle() dispatched the
        // Temporal workflow (the workflow's debit/credit/book + compensation are covered by
        // SettlementWorkflowImplTest).
        assertThat(created.captured.payerAccountId).isEqualTo(payer)
        assertThat(created.captured.payeeAccountId).isEqualTo(payee)
        assertThat(created.captured.amount).isEqualByComparingTo(BigDecimal("250.00"))
        assertThat(created.captured.currency).isEqualTo("CZK")
        assertThat(created.captured.status).isEqualTo(SettlementStatus.PENDING)
        assertThat(created.captured.protocol).isEqualTo(SettlementProtocol.LEGACY)
        assertThat(result.id).isEqualTo(created.captured.id)
        coVerify { repo.create(any()) }
        // A new row is `created`, and specifically NOT `replayed` — the pair is what makes the
        // outcome tag load-bearing rather than a constant.
        assertThat(originatedCount("created")).isEqualTo(1.0)
        assertThat(originatedCount("replayed")).isEqualTo(0.0)
    }

    @Test
    fun `originate is idempotent — a repeated key returns the original without re-settling`() {
        val now = Instant.now()
        val existing = Settlement(
            id = UUID.nameUUIDFromBytes("settlement:dup-key".toByteArray()),
            payerAccountId = UUID.randomUUID(),
            payeeAccountId = UUID.randomUUID(),
            amount = BigDecimal("10.00"),
            currency = "CZK",
            status = SettlementStatus.BOOKED,
            createdAt = now,
            updatedAt = now,
        )
        coEvery { repo.findById(existing.id) } returns existing

        val result = runBlocking {
            service.originate(
                OriginateSettlementCommand(
                    "dup-key",
                    existing.payerAccountId,
                    existing.payeeAccountId,
                    BigDecimal("10.0"),
                    "CZK",
                ),
            )
        }

        assertThat(result.id).isEqualTo(existing.id)
        coVerify(exactly = 0) { repo.create(any()) }
        assertThat(originatedCount("replayed")).isEqualTo(1.0)
        assertThat(originatedCount("created")).isEqualTo(0.0)
    }

    @Test
    fun `same key with a different instruction never starts the existing pending workflow`() {
        assertConflictingInstructionRejected(concurrent = false)
    }

    @Test
    fun `concurrent insert winner with a different instruction never starts its workflow`() {
        assertConflictingInstructionRejected(concurrent = true)
    }

    private fun assertConflictingInstructionRejected(concurrent: Boolean) {
        val command = OriginateSettlementCommand(
            "conflicting-key",
            UUID.randomUUID(),
            UUID.randomUUID(),
            BigDecimal.TEN,
            "CZK",
        )
        val winner = Settlement(
            id = UUID.nameUUIDFromBytes("settlement:${command.idempotencyKey}".toByteArray()),
            payerAccountId = command.payerAccountId,
            payeeAccountId = command.payeeAccountId,
            amount = command.amount,
            currency = command.currency,
            status = SettlementStatus.PENDING,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
        val changedInstructions = listOf(
            command.copy(payerAccountId = UUID.randomUUID()),
            command.copy(payeeAccountId = UUID.randomUUID()),
            command.copy(amount = BigDecimal("10.01")),
            command.copy(currency = "EUR"),
        )
        changedInstructions.forEach { changed ->
            if (concurrent) {
                coEvery { repo.findById(winner.id) } returnsMany listOf(null, winner)
                coEvery { repo.create(any()) } throws IllegalStateException("duplicate primary key")
            } else {
                coEvery { repo.findById(winner.id) } returns winner
            }
            val failure = assertThrows<IllegalArgumentException> {
                runBlocking { service.originate(changed) }
            }
            assertThat(failure).hasMessage("Idempotency key is already bound to a different settlement instruction")
        }
        // settle() must not even select a queue, including for an orphaned PENDING winner.
        verify(exactly = 0) { temporalConfig.taskQueue() }
        coVerify(exactly = if (concurrent) changedInstructions.size else 0) { repo.create(any()) }
        assertThat(originatedCount("created")).isZero()
        assertThat(originatedCount("replayed")).isZero()
    }

    @Test
    fun `commands reject amounts that would round or overflow before persistence`() {
        for (amount in listOf("0.00001", "1000000000000000", "1E+100", "-1", "0")) {
            assertThrows<IllegalArgumentException> {
                OriginateSettlementCommand(
                    "invalid-amount",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    BigDecimal(amount),
                    "CZK",
                )
            }
        }
        val maximum = OriginateSettlementCommand(
            "exact-maximum",
            UUID.randomUUID(),
            UUID.randomUUID(),
            BigDecimal("999999999999999.999900"),
            "CZK",
        )
        assertThat(maximum.amount).isEqualByComparingTo("999999999999999.9999")
        coVerify(exactly = 0) { repo.create(any()) }
    }

    @Test
    fun `enabled rollout persists the protocol before workflow dispatch`() {
        val created = slot<Settlement>()
        coEvery { repo.findById(any()) } answers { if (created.isCaptured) created.captured else null }
        coEvery { repo.create(capture(created)) } answers { created.captured }
        val enabled = SettlementService(repo, temporalConfig, env.workflowClient, metrics, true)
        val result = runBlocking {
            enabled.originate(
                OriginateSettlementCommand(
                    "projection-origination",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    BigDecimal.TEN,
                    "CZK",
                ),
            )
        }
        assertThat(created.captured.protocol).isEqualTo(SettlementProtocol.LEDGER_PROJECTION)
        assertThat(result.protocol).isEqualTo(SettlementProtocol.LEDGER_PROJECTION)
        val workflow = env.workflowClient.newUntypedWorkflowStub("settlement-${result.id}")
        assertThat(workflow.getResult(SettlementStatus::class.java)).isEqualTo(SettlementStatus.BOOKED)
    }

    /** Activities stub that never throws — originate coverage only exercises settle() dispatch. */
    private class RelaxedActivities : SettlementActivities {
        override fun debitPayer(settlementId: UUID) = Unit
        override fun creditPayee(settlementId: UUID) = Unit
        override fun bookToLedger(settlementId: UUID) = Unit
        override fun reverseDebit(settlementId: UUID) = Unit
        override fun reverseCredit(settlementId: UUID) = Unit
        override fun reverseBookToLedger(settlementId: UUID) = Unit
        override fun recordBalanceStateUnknown(settlementId: UUID) = Unit
        override fun rejectSettlement(settlementId: UUID) = Unit
    }
}
