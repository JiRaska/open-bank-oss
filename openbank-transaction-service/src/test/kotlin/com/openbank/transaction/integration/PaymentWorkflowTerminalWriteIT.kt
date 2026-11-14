// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.transaction.integration

import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.openbank.libs.domain.money.Money
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.libs.synthetic.SyntheticTaint
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
 * - The cover hold and journal HTTP calls are isolated in [LedgerWireMockResource].
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

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `workflow reconstructs journal and terminal event origin from the persisted transfer`(synthetic: Boolean) {
        val seeded = seedPendingTransfer(synthetic)

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
        assertThat(reloaded.synthetic).isEqualTo(synthetic)

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
        assertThat(completedEvents.single().synthetic).isEqualTo(synthetic)

        val ordinary = LedgerWireMockResource.JOURNALS_PATH
        val tainted = LedgerWireMockResource.SYNTHETIC_JOURNALS_PATH
        val transactionId = matchingJsonPath("$.transactionId", equalTo(seeded.id.toString()))
        val expected = postRequestedFor(urlEqualTo(if (synthetic) tainted else ordinary))
            .withRequestBody(transactionId)
        val taint = if (synthetic) equalTo(SyntheticTaint.TRUE_VALUE) else absent()
        LedgerWireMockResource.server.verify(1, expected.withHeader(SyntheticTaint.KAFKA_HEADER, taint))
        LedgerWireMockResource.server.verify(
            0,
            postRequestedFor(urlEqualTo(if (synthetic) ordinary else tainted)).withRequestBody(transactionId),
        )
    }

    private fun newWorkflow(transactionId: UUID): PaymentWorkflow = env.workflowClient.newWorkflowStub(
        PaymentWorkflow::class.java,
        WorkflowOptions.newBuilder()
            .setTaskQueue(TASK_QUEUE)
            .setWorkflowId("payment-$transactionId")
            .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .build(),
    )

    private fun seedPendingTransfer(synthetic: Boolean): Transaction {
        val today = LocalDate.now()
        val transaction = Transaction(
            id = UUID.randomUUID(),
            referenceNumber = "TXN${System.nanoTime()}",
            type = TransactionType.TRANSFER,
            sourceAccountId = UUID.randomUUID(),
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
            synthetic = synthetic,
        )
        return onVertxContext {
            transactionRepository.save(
                transaction = transaction,
                outboxMessage = OutboxMessage(
                    aggregateId = transaction.id,
                    eventType = "openbank.transactions.transaction.initiated",
                    payload = """{"transactionId":"${transaction.id}"}""",
                    synthetic = synthetic,
                ),
            )
        }
    }

    private fun <T> onVertxContext(block: suspend () -> T): T =
        VertxContextSupport.subscribeAndAwait { uni(CoroutineScope(Dispatchers.Unconfined)) { block() } }
}
