// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.`in`.CreateDomesticPaymentCommand
import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.DomesticPaymentEventPublisher
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.workflow.DomesticPaymentWorkflow
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.OutboxMessage
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
 * Coverage for [DomesticPaymentService.createPayment]'s Temporal dispatch — the sole orchestration path
 * since ADR-0120 Phase 6 (issue #1917) retired the legacy in-service flow — driven against a real
 * in-memory [TestWorkflowEnvironment] rather than mocking Temporal's static `WorkflowClient.start`.
 * A fresh payment is persisted RECEIVED with its server-derived transferScope and its workflow started
 * fire-and-forget; the workflow's screening/fraud/scheme routing is covered by DomesticPaymentWorkflowImplTest.
 */
class DomesticPaymentServiceTemporalTest {

    private val repo: DomesticPaymentRepository = mockk()
    private val eventPublisher: DomesticPaymentEventPublisher = mockk()
    private val accountLookupPort: AccountLookupPort = mockk()
    private val metrics: DomainMetrics = mockk(relaxed = true)

    private lateinit var env: TestWorkflowEnvironment
    private lateinit var worker: Worker
    private lateinit var service: DomesticPaymentService

    private companion object {
        const val TASK_QUEUE = "openbank-domestic-payments"
    }

    @BeforeEach
    fun setUp() {
        env = TestWorkflowEnvironment.newInstance()
        worker = env.newWorker(TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(NoOpDomesticPaymentWorkflow::class.java)
        env.start()
        service = DomesticPaymentService(
            repo,
            eventPublisher,
            accountLookupPort,
            metrics,
            TASK_QUEUE,
            env.workflowClient,
        )

        coEvery { repo.save(any(), any()) } answers { firstArg() }
        coEvery { accountLookupPort.findPartyByIban(any()) } returns null
        every { eventPublisher.paymentCreatedPayload(any()) } returns "{\"event\":\"created\"}"
    }

    @AfterEach
    fun tearDown() {
        env.close()
    }

    @Test
    fun `createPayment persists RECEIVED and dispatches the Temporal workflow`() {
        coEvery { repo.findByIdempotencyKey("dom-idem-new") } returns null

        val result = runBlocking { service.createPayment(command()) }

        // createPayment returns the freshly-persisted RECEIVED row; the workflow drives it onward
        // asynchronously (routing verified in DomesticPaymentWorkflowImplTest).
        assertThat(result.status).isEqualTo(DomesticPaymentStatus.RECEIVED)
        assertThat(result.currency).isEqualTo("CZK")
    }

    @Test
    fun `createPayment carries the command's actorId onto the persisted payment as initiatedByPartyId`() {
        val actorId = UUID.randomUUID()
        coEvery { repo.findByIdempotencyKey(any()) } returns null

        val result = runBlocking { service.createPayment(command(actorId = actorId)) }

        // #3994: the JWT-authenticated caller was already derived for transferScope and then
        // discarded before reaching the wire — this is what makes it survive onto the payment so
        // DomesticPaymentCreatedEvent (and, via AuditConsumer's existing initiatedByPartyId
        // fallback, the audit trail) can name an actor.
        assertThat(result.initiatedByPartyId).isEqualTo(actorId)
    }

    @Test
    fun `createPayment leaves initiatedByPartyId null when the command carries no actor`() {
        coEvery { repo.findByIdempotencyKey(any()) } returns null

        val result = runBlocking { service.createPayment(command(actorId = null)) }

        assertThat(result.initiatedByPartyId).isNull()
    }

    @Test
    fun `createPayment persists trusted synthetic taint in the durable outbox boundary`() {
        val outbox = io.mockk.slot<OutboxMessage>()
        coEvery { repo.findByIdempotencyKey(any()) } returns null
        coEvery { repo.save(any(), capture(outbox)) } answers { firstArg() }

        runBlocking { service.createPayment(command(synthetic = true)) }

        assertThat(outbox.captured.synthetic).isTrue()
    }

    @Test
    fun `createPayment derives EXTERNAL scope for a non-own-bank creditor`() {
        coEvery { repo.findByIdempotencyKey(any()) } returns null

        val result = runBlocking { service.createPayment(command(creditorBankCode = " 0100 ")) }

        assertThat(result.transferScope).isEqualTo(DomesticTransferScope.EXTERNAL)
    }

    @Test
    fun `createPayment derives INTERNAL_CLIENT scope for an own-bank creditor with unknown party`() {
        coEvery { repo.findByIdempotencyKey(any()) } returns null

        val result = runBlocking { service.createPayment(command(creditorBankCode = "0000")) }

        assertThat(result.transferScope).isEqualTo(DomesticTransferScope.INTERNAL_CLIENT)
    }

    @Test
    fun `createPayment derives OWN_ACCOUNTS scope when the own-bank creditor party matches the actor`() {
        val actorId = UUID.randomUUID()
        coEvery { repo.findByIdempotencyKey(any()) } returns null
        coEvery { accountLookupPort.findPartyByIban(any()) } returns actorId

        val result = runBlocking { service.createPayment(command(creditorBankCode = "0000", actorId = actorId)) }

        assertThat(result.transferScope).isEqualTo(DomesticTransferScope.OWN_ACCOUNTS)
    }

    private fun command(
        idempotencyKey: String = "dom-idem-new",
        creditorBankCode: String = " 0100 ",
        actorId: UUID? = null,
        synthetic: Boolean = false,
    ) = CreateDomesticPaymentCommand(
        idempotencyKey = idempotencyKey,
        debtorAccountId = UUID.randomUUID(),
        debtorAccountNumber = " 1234567890 ",
        debtorBankCode = " 0800 ",
        debtorName = "  Alice Example ",
        creditorAccountNumber = " 9876543210 ",
        creditorBankCode = creditorBankCode,
        creditorName = "  Brno Utility ",
        amount = BigDecimal("1500.00"),
        currency = " czk ",
        variableSymbol = " 2026001 ",
        specificSymbol = null,
        constantSymbol = " 0308 ",
        messageForPayee = "  Utility bill ",
        priority = DomesticPaymentPriority.URGENT,
        technicalAccountCode = null,
        statementLabel = "  Monthly settlement  ",
        endToEndId = "   ",
        actorId = actorId,
        synthetic = synthetic,
    )

    /** No-op workflow so the dispatch has a registered type on the queue; behaviour is tested elsewhere. */
    class NoOpDomesticPaymentWorkflow : DomesticPaymentWorkflow {
        override fun process(paymentId: UUID): DomesticPaymentStatus = DomesticPaymentStatus.RECEIVED
    }
}
