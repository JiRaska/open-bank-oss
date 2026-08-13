// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.integration

import com.openbank.libs.domain.money.Money
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.transaction.application.port.out.TransactionRepository
import com.openbank.transaction.application.workflow.PaymentActivitiesImpl
import com.openbank.transaction.application.workflow.PaymentWorkflow
import com.openbank.transaction.application.workflow.PaymentWorkflowImpl
import com.openbank.transaction.domain.model.Transaction
import com.openbank.transaction.domain.model.TransactionStatus
import com.openbank.transaction.domain.model.TransactionType
import com.openbank.transaction.domain.saga.SagaState
import com.openbank.transaction.infrastructure.persistence.repository.TransactionOutboxRepositoryImpl
import com.openbank.transaction.it.PostgresRedpandaTestResource
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.smallrye.mutiny.coroutines.uni
import io.temporal.api.enums.v1.WorkflowIdReusePolicy
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestWorkflowEnvironment
import jakarta.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Regression coverage for #4238: a settled transaction stayed PENDING forever because the terminal
 * status write lived in `TransactionService`, AFTER the blocking `stub.execute(...)` returned —
 * outside the durable unit. A pod eviction, rollout or client disconnect in that window left the
 * money moved (hold placed, journal posted, balances updated), the Temporal workflow COMPLETED, and
 * the row PENDING with no `...transaction.completed` outbox event, with nothing anywhere to retry.
 *
 * The test therefore runs the REAL [PaymentWorkflowImpl] against the REAL [PaymentActivitiesImpl]
 * CDI bean and then **stops** — it never runs a single line of the caller-side code that used to do
 * the write. What it asserts is exactly the property the fix introduces: *when the workflow returns,
 * the terminal state is already committed*.
 *
 * Falsification: against `origin/main` (terminal write in `TransactionService`) this fails with
 * `expected COMPLETED but was PENDING`, and it passes with the write moved into the workflow's
 * `markCompleted` activity. It compiles against both, so the failure is a real assertion failure and
 * not a build error.
 *
 * Mechanics notes:
 * - The reactive Panache repository cannot be driven from a bare @QuarkusTest thread ("No current
 *   Vertx context found"), so every DB touch goes through [onVertxContext] — the same
 *   `VertxContextSupport` bridge `TransactionOutboxClaimIT` and the activities themselves use.
 * - The seeded transaction has **no source account** (an incoming credit), so `placeHold` returns
 *   the sentinel and the only downstream HTTP call is the ledger journal, stubbed by
 *   [LedgerWireMockResource].
 * - Its own [TestWorkflowEnvironment] — not the one `WorkflowClientTestProducer` publishes, which
 *   registers a stub workflow — because the point of this test is the real workflow's own steps.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
@QuarkusTestResource(LedgerWireMockResource::class)
class PaymentWorkflowTerminalWriteIT {

    @Inject
    lateinit var transactionRepository: TransactionRepository

    @Inject
    lateinit var activities: PaymentActivitiesImpl

    @Inject
    lateinit var outboxRepository: TransactionOutboxRepositoryImpl

    private lateinit var env: TestWorkflowEnvironment

    private companion object {
        const val TASK_QUEUE = "terminal-write-it"
        const val COMPLETED_EVENT = "openbank.transactions.transaction.completed"
    }

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance()
        val worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(PaymentWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(activities)
        env.start()
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    @Test
    fun `workflow itself commits the terminal status and the completed outbox event`() {
        val seeded = seedPendingCredit()

        val state = newWorkflow(seeded.id).execute(seeded.id)

        // Nothing runs between here and the assertions: this is the "the request died" scenario.
        assertThat(state).isEqualTo(SagaState.COMPLETED)

        val reloaded = onVertxContext { transactionRepository.findById(seeded.id) }
        assertThat(reloaded).isNotNull
        assertThat(reloaded!!.status)
            .`as`("the workflow, not the caller, must own the terminal write (#4238)")
            .isEqualTo(TransactionStatus.COMPLETED)
        assertThat(reloaded.completedAt).isNotNull
        assertThat(reloaded.version).isEqualTo(seeded.version + 1)

        val completedEvents = onVertxContext {
            Panache.withSession {
                outboxRepository.find(
                    "aggregateId = ?1 and eventType = ?2",
                    seeded.id,
                    COMPLETED_EVENT,
                ).list()
            }.awaitSuspending()
        }
        assertThat(completedEvents)
            .`as`("the completed event is written in the SAME transaction as the status")
            .hasSize(1)
    }

    private fun newWorkflow(transactionId: UUID): PaymentWorkflow = env.workflowClient.newWorkflowStub(
        PaymentWorkflow::class.java,
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setWorkflowId("payment-$transactionId")
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .build(),
    )

    /** An incoming credit (no source account -> no cover hold), saved PENDING with its initiated event. */
    private fun seedPendingCredit(): Transaction {
        val today = LocalDate.now()
        val transaction = Transaction(
            id = UUID.randomUUID(),
            referenceNumber = "TXN${System.nanoTime()}",
            type = TransactionType.CREDIT,
            sourceAccountId = null,
            targetAccountId = UUID.randomUUID(),
            amount = Money.of(BigDecimal("700.00"), "CZK"),
            fxRate = null,
            baseAmount = Money.of(BigDecimal("700.00"), "CZK"),
            status = TransactionStatus.PENDING,
            description = "Terminal-write IT",
            valueDate = today,
            bookingDate = today,
            initiatedAt = Instant.now(),
            completedAt = null,
            failedAt = null,
            failureReason = null,
            idempotencyKey = "terminal-write-it-${UUID.randomUUID()}",
            version = 0L,
        )
        return onVertxContext {
            transactionRepository.save(
                transaction = transaction,
                outboxMessage = OutboxMessage(
                    aggregateId = transaction.id,
                    eventType = "openbank.transactions.transaction.initiated",
                    payload = """{"transactionId":"${transaction.id}"}""",
                ),
            )
        }
    }

    private fun <T> onVertxContext(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }
}
