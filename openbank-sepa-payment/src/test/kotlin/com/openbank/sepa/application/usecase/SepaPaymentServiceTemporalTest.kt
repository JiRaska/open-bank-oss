// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.usecase

import com.openbank.libs.observability.DomainMetrics
import com.openbank.sepa.application.port.`in`.CreateSepaPaymentCommand
import com.openbank.sepa.application.port.out.ReversalPort
import com.openbank.sepa.application.port.out.SepaPaymentEventPublisher
import com.openbank.sepa.application.port.out.SepaPaymentRepository
import com.openbank.sepa.application.workflow.SepaPaymentWorkflow
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
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
import java.util.UUID

/**
 * Coverage for [SepaPaymentService.createPayment]'s Temporal dispatch — the sole orchestration path
 * since ADR-0120 Phase 6 (issue #1917) retired the legacy in-service flow — driven against a real
 * in-memory [TestWorkflowEnvironment] rather than mocking Temporal's static `WorkflowClient.start`.
 * A fresh payment is persisted RECEIVED and its workflow is started fire-and-forget; the workflow's
 * screening/fraud/scheme routing is covered by SepaPaymentWorkflowImplTest.
 */
class SepaPaymentServiceTemporalTest {

    private val repo: SepaPaymentRepository = mockk()
    private val eventPublisher: SepaPaymentEventPublisher = mockk()
    private val reversalPort: ReversalPort = mockk()
    private val metrics: DomainMetrics = mockk(relaxed = true)

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var worker: Worker
    private lateinit var service: SepaPaymentService

    private companion object {
        const val TASK_QUEUE = "openbank-sepa-payment"
    }

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance()
        worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(NoOpSepaPaymentWorkflow::class.java)
        env.start()
        service = SepaPaymentService(repo, eventPublisher, reversalPort, metrics, TASK_QUEUE, env.workflowClient)

        coEvery { repo.save(any(), any()) } answers { firstArg() }
        every { eventPublisher.paymentCreatedPayload(any()) } returns "{\"event\":\"created\"}"
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    @Test
    fun `createPayment persists RECEIVED and dispatches the Temporal workflow`() {
        coEvery { repo.findByIdempotencyKey("sepa-idem-new") } returns null

        val result = runBlocking { service.createPayment(command()) }

        // createPayment returns the freshly-persisted RECEIVED row; the workflow drives it onward
        // asynchronously (routing verified in SepaPaymentWorkflowImplTest).
        assertThat(result.status).isEqualTo(SepaPaymentStatus.RECEIVED)
    }

    private fun command() = CreateSepaPaymentCommand(
        idempotencyKey = "sepa-idem-new",
        type = SepaPaymentType.SCT,
        debtorAccountId = UUID.randomUUID(),
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice Example",
        creditorIban = "FR7630006000011234567890189",
        creditorName = "Bob Example",
        creditorBic = "DEUTDEFF",
        amount = BigDecimal("205.45"),
        currency = "EUR",
        remittanceInfo = "Invoice 2026-01",
        endToEndId = null,
    )

    /** No-op workflow so the dispatch has a registered type on the queue; behaviour is tested elsewhere. */
    class NoOpSepaPaymentWorkflow : SepaPaymentWorkflow {
        override fun process(paymentId: UUID): SepaPaymentStatus = SepaPaymentStatus.RECEIVED
    }
}
