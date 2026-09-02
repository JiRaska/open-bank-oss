// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.settlement.application.usecase

import com.openbank.libs.temporal.TemporalConfig
import com.openbank.settlement.application.port.out.SettlementRepository
import com.openbank.settlement.application.workflow.SettlementActivities
import com.openbank.settlement.application.workflow.SettlementWorkflowImpl
import com.openbank.settlement.domain.model.Settlement
import com.openbank.settlement.domain.model.SettlementStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.Worker
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Coverage for [SettlementService.settle]'s Temporal-enabled dispatch, including the idempotent
 * double-start no-op (WorkflowExecutionAlreadyStarted), driven against a real in-memory
 * [TestWorkflowEnvironment] rather than mocking Temporal's static `WorkflowClient.start` entry
 * point. SettlementServiceSettleTest covers the not-found guard; SettlementWorkflowImplTest covers
 * the workflow's compensation-on-failure behaviour.
 */
class SettlementServiceTemporalSettleTest {

    private val repo: SettlementRepository = mockk(relaxed = true)
    private val temporalConfig: TemporalConfig = mockk(relaxed = true)

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var worker: Worker
    private lateinit var service: SettlementService

    private companion object {
        const val TASK_QUEUE = "openbank-settlement"
    }

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance()
        worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(SettlementWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(RelaxedActivities())
        env.start()
        every { temporalConfig.taskQueue() } returns TASK_QUEUE
        service = SettlementService(repo, temporalConfig, env.workflowClient, mockk(relaxed = true))
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    private fun pendingSettlement(id: UUID = UUID.randomUUID()) = Settlement(
        id = id,
        payerAccountId = UUID.randomUUID(),
        payeeAccountId = UUID.randomUUID(),
        amount = BigDecimal("10.00"),
        currency = "CZK",
        status = SettlementStatus.PENDING,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Test
    fun `settle starts the Temporal workflow and returns the pre-dispatch status`() {
        val settlement = pendingSettlement()
        coEvery { repo.findById(settlement.id) } returns settlement

        val result = runBlocking { service.settle(settlement.id) }

        // settle() returns the status read BEFORE workflow dispatch; the workflow itself
        // reaches BOOKED asynchronously (verified separately by SettlementWorkflowImplTest).
        assertThat(result).isEqualTo(SettlementStatus.PENDING)
    }

    @Test
    fun `settle is idempotent when the workflow-id is already running`() {
        val settlement = pendingSettlement()
        coEvery { repo.findById(settlement.id) } returns settlement

        // First call starts "settlement-$id"; the second call for the SAME id must not throw
        // even though the workflow id is already in use (WorkflowExecutionAlreadyStarted is
        // caught and swallowed as an idempotent no-op).
        runBlocking { service.settle(settlement.id) }
        val second = runBlocking { service.settle(settlement.id) }

        assertThat(second).isEqualTo(SettlementStatus.PENDING)
    }

    /** Activities stub that never throws — this test suite only exercises SettlementService dispatch. */
    private class RelaxedActivities : SettlementActivities {
        override fun debitPayer(settlementId: UUID) = Unit
        override fun creditPayee(settlementId: UUID) = Unit
        override fun bookToLedger(settlementId: UUID) = Unit
        override fun reverseDebit(settlementId: UUID) = Unit
        override fun reverseCredit(settlementId: UUID) = Unit
        override fun reverseBookToLedger(settlementId: UUID) = Unit
        override fun rejectSettlement(settlementId: UUID) = Unit
    }
}
