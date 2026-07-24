// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.`in`.CreateDomesticPaymentCommand
import com.openbank.domestic.application.port.`in`.TransitionDomesticPaymentStatusCommand
import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.DomesticPaymentEventPublisher
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.libs.observability.DomainMetrics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.temporal.client.WorkflowClient
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Coverage for [DomesticPaymentService]'s non-orchestration paths: idempotent replay and status
 * transitions. Since ADR-0120 Phase 6 (issue #1917) retired the legacy in-service flow, `createPayment`
 * for a NEW payment dispatches the Temporal [com.openbank.domestic.application.workflow.DomesticPaymentWorkflow]
 * (screening → shadow fraud → scheme → settle) — that dispatch and its scope derivation are covered by
 * DomesticPaymentServiceTemporalTest, and the workflow routing by DomesticPaymentWorkflowImplTest, both
 * against a real in-memory TestWorkflowEnvironment. The replay path here returns before any dispatch, so
 * a relaxed WorkflowClient mock suffices.
 */
class DomesticPaymentServiceTest {
    private lateinit var paymentRepository: DomesticPaymentRepository
    private lateinit var eventPublisher: DomesticPaymentEventPublisher
    private lateinit var accountLookupPort: AccountLookupPort
    private lateinit var metrics: DomainMetrics
    private lateinit var workflowClient: WorkflowClient

    private lateinit var service: DomesticPaymentService

    @BeforeEach
    fun setUp() {
        paymentRepository = mockk()
        eventPublisher = mockk()
        accountLookupPort = mockk()
        metrics = mockk(relaxed = true)
        workflowClient = mockk(relaxed = true)
        service = DomesticPaymentService(
            paymentRepository,
            eventPublisher,
            accountLookupPort,
            metrics,
            "openbank-domestic-payments",
            workflowClient,
        )

        coEvery { paymentRepository.save(any(), any()) } answers { firstArg() }
        coEvery { paymentRepository.update(any(), any()) } answers { firstArg() }
        every { eventPublisher.paymentCreatedPayload(any()) } returns "{\"event\":\"created\"}"
        every { eventPublisher.statusChangedPayload(any(), any()) } returns "{\"event\":\"status-changed\"}"
    }

    @Test
    fun `create payment replays existing domestic payment on idempotency key without dispatching`(): Unit =
        runBlocking {
            val existing = payment()
            coEvery { paymentRepository.findByIdempotencyKey(existing.idempotencyKey) } returns existing

            val result = service.createPayment(createCommand(idempotencyKey = existing.idempotencyKey))

            assertThat(result).isEqualTo(existing)
            coVerify(exactly = 0) { paymentRepository.save(any(), any()) }
        }

    @Test
    fun `transition to rejected requires reject reason`() {
        val existing = payment(status = DomesticPaymentStatus.RECEIVED)
        coEvery { paymentRepository.findById(existing.id) } returns existing

        assertThatThrownBy {
            runBlocking {
                service.transitionStatus(
                    TransitionDomesticPaymentStatusCommand(
                        paymentId = existing.id,
                        targetStatus = DomesticPaymentStatus.REJECTED,
                        rejectReason = null,
                        rejectDetail = "Bank code invalid",
                    ),
                )
            }
        }
            .isInstanceOf(InvalidDomesticPaymentStateTransitionException::class.java)
            .hasMessageContaining("Reject reason is required")

        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    private fun createCommand(
        idempotencyKey: String = "dom-idem-1",
        technicalAccountCode: String? = null,
        creditorBankCode: String = " 0100 ",
        actorId: UUID? = null,
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
        technicalAccountCode = technicalAccountCode,
        statementLabel = "  Monthly settlement  ",
        endToEndId = "   ",
        actorId = actorId,
    )

    private fun payment(status: DomesticPaymentStatus = DomesticPaymentStatus.RECEIVED) = DomesticPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "dom-idem-existing",
        status = status,
        debtorAccountId = UUID.randomUUID(),
        debtorAccountNumber = "1234567890",
        debtorBankCode = "0800",
        debtorName = "Alice Example",
        creditorAccountNumber = "9876543210",
        creditorBankCode = "0100",
        creditorName = "Brno Utility",
        amount = BigDecimal("1500.00"),
        currency = "CZK",
        variableSymbol = "2026001",
        specificSymbol = null,
        constantSymbol = "0308",
        messageForPayee = "Utility bill",
        priority = DomesticPaymentPriority.URGENT,
        transferScope = DomesticTransferScope.INTERNAL_CLIENT,
        technicalAccountCode = null,
        statementLabel = "Monthly settlement",
        endToEndId = "DOMU123",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        settledAt = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}
