// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.usecase

import com.openbank.domestic.application.port.`in`.CreateDomesticPaymentCommand
import com.openbank.domestic.application.port.`in`.TransitionDomesticPaymentStatusCommand
import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.DelegatedSpendBindingRepository
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
import io.mockk.verify
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
    private lateinit var delegatedSpendBindingRepository: DelegatedSpendBindingRepository
    private lateinit var eventPublisher: DomesticPaymentEventPublisher
    private lateinit var accountLookupPort: AccountLookupPort
    private lateinit var metrics: DomainMetrics
    private lateinit var workflowClient: WorkflowClient

    private lateinit var service: DomesticPaymentService

    @BeforeEach
    fun setUp() {
        paymentRepository = mockk()
        delegatedSpendBindingRepository = mockk()
        eventPublisher = mockk()
        accountLookupPort = mockk()
        metrics = mockk(relaxed = true)
        workflowClient = mockk(relaxed = true)
        service = DomesticPaymentService(
            paymentRepository,
            delegatedSpendBindingRepository,
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
            val command = createCommand(idempotencyKey = "dom-idem-existing")
            val existing = payment().copy(requestFingerprint = DomesticPaymentRequestFingerprint.sha256(command))
            coEvery { paymentRepository.findByIdempotencyKey(existing.idempotencyKey) } returns existing

            val result = service.createPayment(command)

            assertThat(result.payment).isEqualTo(existing)
            assertThat(result.replayed).isTrue()
            coVerify(exactly = 0) { paymentRepository.save(any(), any()) }
        }

    @Test
    fun `idempotent replay refuses any changed request`() {
        val command = createCommand(idempotencyKey = "dom-idem-existing")
        val existing = payment().copy(requestFingerprint = DomesticPaymentRequestFingerprint.sha256(command))
        coEvery { paymentRepository.findByIdempotencyKey(existing.idempotencyKey) } returns existing

        assertThatThrownBy {
            runBlocking {
                service.createPayment(command.copy(amount = command.amount + BigDecimal.ONE))
            }
        }
            .isInstanceOf(DomesticPaymentIdempotencyConflictException::class.java)
            .hasMessageContaining("another domestic payment request")

        coVerify(exactly = 0) { paymentRepository.save(any(), any()) }
    }

    @Test
    fun `idempotent replay refuses a legacy row without a fingerprint`() {
        val existing = payment().copy(requestFingerprint = null)
        coEvery { paymentRepository.findByIdempotencyKey(existing.idempotencyKey) } returns existing

        assertThatThrownBy {
            runBlocking { service.createPayment(createCommand(idempotencyKey = existing.idempotencyKey)) }
        }.isInstanceOf(DomesticPaymentIdempotencyConflictException::class.java)

        coVerify(exactly = 0) { paymentRepository.save(any(), any()) }
    }

    @Test
    fun `concurrent insert loser verifies and replays the database winner without dispatching twice`(): Unit =
        runBlocking {
            val command = createCommand(idempotencyKey = "dom-idem-race")
            val fingerprint = DomesticPaymentRequestFingerprint.sha256(command)
            val winner = payment().copy(idempotencyKey = command.idempotencyKey, requestFingerprint = fingerprint)
            coEvery { paymentRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
            coEvery { paymentRepository.save(any(), any()) } returns winner

            val result = service.createPayment(command)

            assertThat(result.payment).isEqualTo(winner)
            assertThat(result.replayed).isTrue()
            verify(exactly = 0) { metrics.paymentSubmitted(any(), any()) }
        }

    @Test
    fun `create command refuses a half delegation reservation pair`() {
        assertThatThrownBy { createCommand().copy(delegationId = UUID.randomUUID()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must either both be present or both be absent")
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

    // Issue #5049: paymentCompleted()/paymentProcessingDuration() had NO call site anywhere in this
    // class -- only paymentSubmitted() (in createPayment) was ever wired -- so
    // openbank_payments_completed_total{type="domestic",...} and
    // openbank_payment_processing_duration_seconds{type="domestic",...} could never have a sample
    // no matter how much real domestic-payment traffic occurred. Nothing in this file asserted the
    // metrics call before, which is exactly how the gap went unnoticed; these two cases are the
    // falsifying pair -- a terminal transition must record, a non-terminal one must not.
    @Test
    fun `settling a payment records paymentCompleted and paymentProcessingDuration as settled`(): Unit = runBlocking {
        val existing = payment(status = DomesticPaymentStatus.SENT_TO_CLEARING)
        coEvery { paymentRepository.findById(existing.id) } returns existing

        service.transitionStatus(
            TransitionDomesticPaymentStatusCommand(
                paymentId = existing.id,
                targetStatus = DomesticPaymentStatus.SETTLED,
                rejectReason = null,
                rejectDetail = null,
            ),
        )

        verify(exactly = 1) { metrics.paymentCompleted("domestic", existing.currency, "settled") }
        verify(exactly = 1) { metrics.paymentProcessingDuration("domestic", "settled", any()) }
    }

    @Test
    fun `rejecting a payment records paymentCompleted as rejected, not settled`(): Unit = runBlocking {
        val existing = payment(status = DomesticPaymentStatus.RECEIVED)
        coEvery { paymentRepository.findById(existing.id) } returns existing

        service.transitionStatus(
            TransitionDomesticPaymentStatusCommand(
                paymentId = existing.id,
                targetStatus = DomesticPaymentStatus.REJECTED,
                rejectReason = com.openbank.domestic.domain.model.DomesticRejectReason.INVALID_ACCOUNT_NUMBER,
                rejectDetail = "Account not found",
            ),
        )

        verify(exactly = 1) { metrics.paymentCompleted("domestic", existing.currency, "rejected") }
        verify(exactly = 0) { metrics.paymentCompleted("domestic", existing.currency, "settled") }
    }

    @Test
    fun `a non-terminal transition records neither paymentCompleted nor paymentProcessingDuration`(): Unit =
        runBlocking {
            val existing = payment(status = DomesticPaymentStatus.RECEIVED)
            coEvery { paymentRepository.findById(existing.id) } returns existing

            service.transitionStatus(
                TransitionDomesticPaymentStatusCommand(
                    paymentId = existing.id,
                    targetStatus = DomesticPaymentStatus.VALIDATED,
                    rejectReason = null,
                    rejectDetail = null,
                ),
            )

            verify(exactly = 0) { metrics.paymentCompleted(any(), any(), any()) }
            verify(exactly = 0) { metrics.paymentProcessingDuration(any(), any(), any()) }
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
