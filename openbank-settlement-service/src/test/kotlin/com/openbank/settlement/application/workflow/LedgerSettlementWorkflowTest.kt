// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.settlement.application.workflow

import com.openbank.settlement.domain.model.SettlementStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.temporal.activity.Activity
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.failure.ApplicationFailure
import io.temporal.testing.TestWorkflowEnvironment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LedgerSettlementWorkflowTest {
    @Test
    fun `only ledger books money after cover is established`() {
        run { workflow, cover, ledger ->
            val id = UUID.randomUUID()
            assertThat(workflow.settleViaLedger(id)).isEqualTo(SettlementStatus.BOOKED)
            verifyOrder {
                cover.reserveSettlementCover(id)
                ledger.bookToLedger(id)
            }
            verify(exactly = 1) { ledger.bookToLedger(id) }
            assertNoDirectMovements(ledger)
        }
    }

    @Test
    fun `worker loss before activity completion retries the original journal`() {
        run { workflow, cover, ledger ->
            val attempts = AtomicInteger()
            every { ledger.bookToLedger(any()) } answers {
                if (attempts.incrementAndGet() == 1) {
                    // Model a dead worker: no completion or failure reaches Temporal.
                    Activity.getExecutionContext().doNotCompleteOnReturn()
                }
            }
            every { cover.recordProjectionOutcomeUnknown(any(), true) } returns SettlementStatus.LEDGER_STATE_UNKNOWN
            val id = UUID.randomUUID()
            val stub = WorkflowStub.fromTyped(workflow)
            stub.start(id)
            val result = stub.getResult(90, TimeUnit.SECONDS, SettlementStatus::class.java)
            assertThat(result).isEqualTo(SettlementStatus.BOOKED)
            verify(exactly = 2) { ledger.bookToLedger(id) }
            verify(exactly = 1) { cover.reserveSettlementCover(id) }
            assertNoDirectMovements(ledger)
        }
    }

    @Test
    fun `lost cover reply cannot start a ledger posting`() {
        run { workflow, cover, ledger ->
            every { cover.reserveSettlementCover(any()) } throws failure()
            every { cover.recordProjectionOutcomeUnknown(any(), false) } returns SettlementStatus.BALANCE_STATE_UNKNOWN
            assertThat(workflow.settleViaLedger(UUID.randomUUID())).isEqualTo(SettlementStatus.BALANCE_STATE_UNKNOWN)
            verify(exactly = 0) { ledger.bookToLedger(any()) }
            assertNoDirectMovements(ledger)
        }
    }

    @Test
    fun `lost journal reply retains cover and records an unresolved outcome`() {
        run { workflow, cover, ledger ->
            every { ledger.bookToLedger(any()) } throws failure()
            every { cover.recordProjectionOutcomeUnknown(any(), true) } returns SettlementStatus.LEDGER_STATE_UNKNOWN
            assertThat(workflow.settleViaLedger(UUID.randomUUID())).isEqualTo(SettlementStatus.LEDGER_STATE_UNKNOWN)
            verify(exactly = 1) { cover.reserveSettlementCover(any()) }
            assertNoDirectMovements(ledger)
        }
    }

    @Test
    fun `late unknown marker reports a booking already confirmed by the database`() {
        run { workflow, cover, ledger ->
            every { ledger.bookToLedger(any()) } throws failure()
            every { cover.recordProjectionOutcomeUnknown(any(), true) } returns SettlementStatus.BOOKED
            assertThat(workflow.settleViaLedger(UUID.randomUUID())).isEqualTo(SettlementStatus.BOOKED)
            assertNoDirectMovements(ledger)
        }
    }

    private fun assertNoDirectMovements(legacy: SettlementActivities) {
        verify(exactly = 0) {
            legacy.debitPayer(any())
            legacy.creditPayee(any())
            legacy.reverseCredit(any())
            legacy.reverseDebit(any())
            legacy.reverseBookToLedger(any())
            legacy.rejectSettlement(any())
        }
    }

    private fun run(check: (LedgerSettlementWorkflow, LedgerSettlementActivities, SettlementActivities) -> Unit) {
        TestWorkflowEnvironment.newInstance().use { env ->
            val cover = mockk<LedgerSettlementActivities>(relaxed = true)
            val ledger = mockk<SettlementActivities>(relaxed = true)
            val worker = env.newWorker("ledger-settlement-test")
            worker.registerWorkflowImplementationTypes(LedgerSettlementWorkflowImpl::class.java)
            worker.registerActivitiesImplementations(cover, ledger)
            env.start()
            val workflow = env.workflowClient.newWorkflowStub(
                LedgerSettlementWorkflow::class.java,
                WorkflowOptions.newBuilder().setTaskQueue("ledger-settlement-test").build(),
            )
            check(workflow, cover, ledger)
        }
    }

    private fun failure() = ApplicationFailure.newFailure("Injected lost response", "TestFailure")
}
