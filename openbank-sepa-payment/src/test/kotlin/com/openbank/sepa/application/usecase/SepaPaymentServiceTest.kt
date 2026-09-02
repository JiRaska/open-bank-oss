// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.usecase

import com.openbank.libs.iso20022.Pacs004Builder
import com.openbank.libs.iso20022.PaymentReturn
import com.openbank.libs.iso20022.SettlementMethod
import com.openbank.libs.observability.DomainMetrics
import com.openbank.sepa.application.port.`in`.CreateSepaPaymentCommand
import com.openbank.sepa.application.port.`in`.HandlePaymentReturnCommand
import com.openbank.sepa.application.port.`in`.ListSepaPaymentsQuery
import com.openbank.sepa.application.port.`in`.TransitionSepaPaymentStatusCommand
import com.openbank.sepa.application.port.out.ReversalPort
import com.openbank.sepa.application.port.out.SepaPaymentEventPublisher
import com.openbank.sepa.application.port.out.SepaPaymentOutboxMessage
import com.openbank.sepa.application.port.out.SepaPaymentRepository
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
 * Coverage for [SepaPaymentService]'s non-orchestration paths: idempotent replay, status transitions,
 * queries, and pacs.004 return handling. Since ADR-0120 Phase 6 (issue #1917) retired the legacy
 * in-service flow, `createPayment` for a NEW payment dispatches the Temporal `SepaPaymentWorkflow`
 * (screening → shadow fraud → scheme → settle) — that dispatch, and the workflow's routing/compensation,
 * are covered by SepaPaymentServiceTemporalTest and SepaPaymentWorkflowImplTest against a real
 * in-memory TestWorkflowEnvironment. The replay path here returns before any dispatch, so a relaxed
 * WorkflowClient mock suffices.
 */
class SepaPaymentServiceTest {

    private lateinit var paymentRepository: SepaPaymentRepository
    private lateinit var eventPublisher: SepaPaymentEventPublisher
    private lateinit var reversalPort: ReversalPort
    private lateinit var metrics: DomainMetrics
    private lateinit var workflowClient: WorkflowClient

    private lateinit var service: SepaPaymentService

    @BeforeEach
    fun setUp() {
        paymentRepository = mockk()
        eventPublisher = mockk()
        reversalPort = mockk()
        metrics = mockk(relaxed = true)
        workflowClient = mockk(relaxed = true)
        service = SepaPaymentService(
            paymentRepository,
            eventPublisher,
            reversalPort,
            metrics,
            "openbank-sepa-payment",
            workflowClient,
        )

        coEvery { paymentRepository.save(any(), any()) } answers { firstArg() }
        coEvery { paymentRepository.update(any(), any()) } answers { firstArg() }
        coEvery { paymentRepository.updateWithEvidence(any(), any(), any()) } answers { firstArg() }
        every { eventPublisher.paymentCreatedPayload(any()) } returns "{\"event\":\"created\"}"
        every { eventPublisher.statusChangedPayload(any(), any()) } returns "{\"event\":\"status-changed\"}"
        every {
            eventPublisher.returnEvidencePayload(any(), any(), any(), any(), any(), any(), any())
        } returns "{\"event\":\"returned\"}"
    }

    @Test
    fun `create payment replays existing payment for idempotency key without dispatching a workflow`(): Unit =
        runBlocking {
            val existing = payment()
            coEvery { paymentRepository.findByIdempotencyKey(existing.idempotencyKey) } returns existing

            val result = service.createPayment(createCommand(idempotencyKey = existing.idempotencyKey))

            assertThat(result).isEqualTo(existing)
            coVerify(exactly = 0) { paymentRepository.save(any(), any()) }
        }

    @Test
    fun `transition to rejected requires reject reason`() {
        val existing = payment(status = SepaPaymentStatus.RECEIVED)
        coEvery { paymentRepository.findById(existing.id) } returns existing

        assertThatThrownBy {
            runBlocking {
                service.transitionStatus(
                    TransitionSepaPaymentStatusCommand(
                        paymentId = existing.id,
                        targetStatus = SepaPaymentStatus.REJECTED,
                        rejectReason = null,
                        rejectDetail = "IBAN invalid",
                    ),
                )
            }
        }
            .isInstanceOf(InvalidSepaPaymentStateTransitionException::class.java)
            .hasMessageContaining("Reject reason is required")

        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `getPayment returns the persisted payment`(): Unit = runBlocking {
        val existing = payment()
        coEvery { paymentRepository.findById(existing.id) } returns existing

        val result = service.getPayment(existing.id)

        assertThat(result).isEqualTo(existing)
    }

    @Test
    fun `getPayment throws not-found when the payment is missing`() {
        val missingId = UUID.randomUUID()
        coEvery { paymentRepository.findById(missingId) } returns null

        assertThatThrownBy { runBlocking { service.getPayment(missingId) } }
            .isInstanceOf(SepaPaymentNotFoundException::class.java)
            .hasMessageContaining(missingId.toString())
    }

    @Test
    fun `listPayments clamps the limit and offset before delegating to the repository`(): Unit = runBlocking {
        val accountId = UUID.randomUUID()
        val expected = listOf(payment())
        coEvery {
            paymentRepository.list(SepaPaymentStatus.RECEIVED, accountId, 200, 0)
        } returns expected

        val result = service.listPayments(
            ListSepaPaymentsQuery(
                status = SepaPaymentStatus.RECEIVED,
                debtorAccountId = accountId,
                limit = 5000,
                offset = -10,
            ),
        )

        assertThat(result).isEqualTo(expected)
        coVerify { paymentRepository.list(SepaPaymentStatus.RECEIVED, accountId, 200, 0) }
    }

    @Test
    fun `transitionStatus persists a valid transition and enqueues a status-changed outbox message`(): Unit =
        runBlocking {
            val existing = payment(status = SepaPaymentStatus.PROCESSING)
            coEvery { paymentRepository.findById(existing.id) } returns existing

            val result = service.transitionStatus(
                TransitionSepaPaymentStatusCommand(
                    paymentId = existing.id,
                    targetStatus = SepaPaymentStatus.COMPLETED,
                ),
            )

            assertThat(result.status).isEqualTo(SepaPaymentStatus.COMPLETED)
            coVerify {
                paymentRepository.update(
                    match { it.status == SepaPaymentStatus.COMPLETED },
                    match<SepaPaymentOutboxMessage> { it.eventType == "sepa.payment.status-changed" },
                )
            }
        }

    @Test
    fun `transitionStatus throws not-found when the payment is missing`() {
        val missingId = UUID.randomUUID()
        coEvery { paymentRepository.findById(missingId) } returns null

        assertThatThrownBy {
            runBlocking {
                service.transitionStatus(
                    TransitionSepaPaymentStatusCommand(missingId, SepaPaymentStatus.VALIDATED),
                )
            }
        }.isInstanceOf(SepaPaymentNotFoundException::class.java)

        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `transitionStatus rejects a disallowed state transition`() {
        val existing = payment(status = SepaPaymentStatus.COMPLETED)
        coEvery { paymentRepository.findById(existing.id) } returns existing

        assertThatThrownBy {
            runBlocking {
                service.transitionStatus(
                    TransitionSepaPaymentStatusCommand(existing.id, SepaPaymentStatus.VALIDATED),
                )
            }
        }
            .isInstanceOf(InvalidSepaPaymentStateTransitionException::class.java)
            .hasMessageContaining("Invalid SEPA payment status transition")

        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    // ---- pacs.004 return handling tests ----

    private val pacs004Builder = Pacs004Builder()

    /**
     * The actor/correlation triple the REST adapter derives server-side (issue #6056). Written once
     * here so a test cannot accidentally assert against an actor a caller could have chosen.
     */
    private fun returnCommand(xml: String) = HandlePaymentReturnCommand(
        pacs004Xml = xml,
        actorId = "service-account-openbank-services",
        actorType = "ROLE_API",
        correlationId = "corr-6056",
    )

    private fun pacs004Xml(endToEndId: String, reasonCode: String? = "AC04"): String = pacs004Builder.build(
        PaymentReturn(
            messageId = "MSG-001",
            creationDateTime = java.time.OffsetDateTime.now(),
            settlementMethod = SettlementMethod.CLRG,
            returnId = "RTR-001",
            originalEndToEndId = endToEndId,
            originalTransactionId = "TX-001",
            returnedAmount = java.math.BigDecimal("100.00"),
            currency = "EUR",
            returnReasonCode = reasonCode,
        ),
    )

    @Test
    fun `handlePaymentReturn parses pacs004 finds payment and transitions to RETURNED`(): Unit = runBlocking {
        val existing = payment(status = SepaPaymentStatus.PROCESSING)
        coEvery { paymentRepository.findByEndToEndId(existing.endToEndId) } returns existing

        val result = service.handlePaymentReturn(returnCommand(pacs004Xml(existing.endToEndId)))

        assertThat(result.status).isEqualTo(SepaPaymentStatus.RETURNED)
        // The evidence-carrying write path, not the plain one — a return that transitions without
        // its non-repudiation record is the defect this fixes (issue #6056).
        coVerify {
            paymentRepository.updateWithEvidence(
                match { it.status == SepaPaymentStatus.RETURNED },
                any(),
                any(),
            )
        }
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `handlePaymentReturn writes the evidence outbox message in the same call as the transition`(): Unit =
        runBlocking {
            val existing = payment(status = SepaPaymentStatus.PROCESSING)
            coEvery { paymentRepository.findByEndToEndId(existing.endToEndId) } returns existing
            val evidence = slot<SepaPaymentOutboxMessage>()
            val statusMessage = slot<SepaPaymentOutboxMessage>()
            coEvery {
                paymentRepository.updateWithEvidence(any(), capture(statusMessage), capture(evidence))
            } answers { firstArg() }

            service.handlePaymentReturn(returnCommand(pacs004Xml(existing.endToEndId)))

            assertThat(evidence.captured.eventType).isEqualTo("sepa.payment.returned")
            assertThat(evidence.captured.aggregateId).isEqualTo(existing.id)
            assertThat(statusMessage.captured.eventType).isEqualTo("sepa.payment.status-changed")
        }

    @Test
    fun `handlePaymentReturn takes the actor from the command, never from the pacs004 body`(): Unit = runBlocking {
        val existing = payment(status = SepaPaymentStatus.PROCESSING)
        coEvery { paymentRepository.findByEndToEndId(existing.endToEndId) } returns existing

        service.handlePaymentReturn(returnCommand(pacs004Xml(existing.endToEndId, reasonCode = "AM09")))

        verify {
            eventPublisher.returnEvidencePayload(
                payment = any(),
                originalEndToEndId = existing.endToEndId,
                returnReasonCode = "AM09",
                actorId = "service-account-openbank-services",
                actorType = "ROLE_API",
                correlationId = "corr-6056",
                // No transactionId on the seeded payment, so no reversal was performed. The record
                // must say so: an evidence row claiming a reversal that did not happen is worse
                // than none at all.
                reversalPerformed = false,
            )
        }
    }

    @Test
    fun `handlePaymentReturn is idempotent when payment already RETURNED`(): Unit = runBlocking {
        val existing = payment(status = SepaPaymentStatus.RETURNED)
        coEvery { paymentRepository.findByEndToEndId(existing.endToEndId) } returns existing

        val result = service.handlePaymentReturn(returnCommand(pacs004Xml(existing.endToEndId)))

        assertThat(result.status).isEqualTo(SepaPaymentStatus.RETURNED)
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `handlePaymentReturn throws for invalid XML`() {
        assertThatThrownBy {
            runBlocking { service.handlePaymentReturn(returnCommand("not-xml")) }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid pacs.004")
    }

    @Test
    fun `handlePaymentReturn throws when no payment found for endToEndId`() {
        val unknownE2eId = "UNKNOWN-E2E"
        coEvery { paymentRepository.findByEndToEndId(unknownE2eId) } returns null
        val xml = pacs004Xml(unknownE2eId)

        assertThatThrownBy {
            runBlocking { service.handlePaymentReturn(returnCommand(xml)) }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("No payment found for endToEndId")
    }

    private fun createCommand(idempotencyKey: String = "sepa-idem-1") = CreateSepaPaymentCommand(
        idempotencyKey = idempotencyKey,
        type = SepaPaymentType.SCT,
        debtorAccountId = UUID.randomUUID(),
        debtorIban = "  DE89370400440532013000  ",
        debtorName = "  Alice Example  ",
        creditorIban = "  FR7630006000011234567890189  ",
        creditorName = "  Bob Example  ",
        creditorBic = "  DEUTDEFF  ",
        amount = BigDecimal("205.45"),
        currency = " eur ",
        remittanceInfo = "  Invoice 2026-01  ",
        endToEndId = "   ",
    )

    private fun payment(status: SepaPaymentStatus = SepaPaymentStatus.RECEIVED) = SepaPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "sepa-idem-existing",
        type = SepaPaymentType.SCT,
        status = status,
        debtorAccountId = UUID.randomUUID(),
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice Example",
        creditorIban = "FR7630006000011234567890189",
        creditorName = "Bob Example",
        creditorBic = "DEUTDEFF",
        amount = BigDecimal("205.45"),
        currency = "EUR",
        remittanceInfo = "Invoice 2026-01",
        endToEndId = "E2E123",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        completedAt = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )
}
