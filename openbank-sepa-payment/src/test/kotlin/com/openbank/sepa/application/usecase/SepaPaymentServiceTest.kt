// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.usecase

import com.openbank.libs.domain.error.Unavailable
import com.openbank.libs.domain.money.Money
import com.openbank.libs.iso20022.Pacs004Builder
import com.openbank.libs.iso20022.PaymentReturn
import com.openbank.libs.iso20022.SettlementMethod
import com.openbank.libs.observability.DomainMetrics
import com.openbank.sepa.application.port.`in`.CreateSepaPaymentCommand
import com.openbank.sepa.application.port.`in`.HandlePaymentReturnCommand
import com.openbank.sepa.application.port.`in`.ListSepaPaymentsQuery
import com.openbank.sepa.application.port.`in`.TransitionSepaPaymentStatusCommand
import com.openbank.sepa.application.port.out.ReversalOutcome
import com.openbank.sepa.application.port.out.ReversalPort
import com.openbank.sepa.application.port.out.ReversalUnavailableException
import com.openbank.sepa.application.port.out.SepaPaymentEventPublisher
import com.openbank.sepa.application.port.out.SepaPaymentOutboxMessage
import com.openbank.sepa.application.port.out.SepaPaymentRepository
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import com.openbank.sepa.domain.model.SepaReturnIntent
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
        coEvery { reversalPort.reverseTransaction(any(), any(), any()) } returns
            ReversalOutcome(true, UUID.fromString("00000000-0000-0000-0000-000000001362"))
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

        coEvery { paymentRepository.beginReturn(any(), any()) } coAnswers {
            val id = firstArg<UUID>()
            val existing = checkNotNull(paymentRepository.findById(id))
            existing.copy(returnIntent = secondArg(), revision = existing.revision + 1)
        }
        coEvery { paymentRepository.save(any(), any()) } answers { firstArg() }
        coEvery { paymentRepository.update(any(), any()) } answers { firstArg() }
        coEvery { paymentRepository.updateWithEvidence(any(), any(), any()) } answers { firstArg() }
        every { eventPublisher.paymentCreatedPayload(any()) } returns "{\"event\":\"created\"}"
        every { eventPublisher.statusChangedPayload(any(), any()) } returns "{\"event\":\"status-changed\"}"
        every {
            eventPublisher.returnEvidencePayload(any(), any(), any(), any(), any(), any(), any(), any())
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
        val existing = payment(status = SepaPaymentStatus.PROCESSING).copy(transactionId = UUID.randomUUID())
        coEvery { paymentRepository.findByEndToEndId(existing.endToEndId) } returns existing
        coEvery { paymentRepository.findById(existing.id) } returns existing

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
            val existing = payment(status = SepaPaymentStatus.PROCESSING).copy(transactionId = UUID.randomUUID())
            coEvery { paymentRepository.findByEndToEndId(existing.endToEndId) } returns existing
            coEvery { paymentRepository.findById(existing.id) } returns existing
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
        val existing = payment(status = SepaPaymentStatus.PROCESSING).copy(transactionId = UUID.randomUUID())
        coEvery { paymentRepository.findByEndToEndId(existing.endToEndId) } returns existing
        coEvery { paymentRepository.findById(existing.id) } returns existing

        service.handlePaymentReturn(returnCommand(pacs004Xml(existing.endToEndId, reasonCode = "AM09")))

        verify {
            eventPublisher.returnEvidencePayload(
                payment = any(),
                originalEndToEndId = existing.endToEndId,
                returnReasonCode = "AM09",
                actorId = "service-account-openbank-services",
                actorType = "ROLE_API",
                correlationId = "corr-6056",
                reversalPerformed = true,
                reversalTransactionId = UUID.fromString("00000000-0000-0000-0000-000000001362"),
            )
        }
    }

    @Test
    fun `return evidence carries only the reversal identity reported by transaction service`(): Unit = runBlocking {
        val existing = payment(status = SepaPaymentStatus.PROCESSING).copy(transactionId = UUID.randomUUID())
        val reversalId = UUID.randomUUID()
        coEvery { paymentRepository.findByEndToEndId(existing.endToEndId) } returns existing
        coEvery { paymentRepository.findById(existing.id) } returns existing
        coEvery { reversalPort.reverseTransaction(existing.transactionId!!, any(), any()) } returns
            ReversalOutcome(reversed = true, reversalTransactionId = reversalId)

        service.handlePaymentReturn(returnCommand(pacs004Xml(existing.endToEndId)))

        verify { eventPublisher.returnEvidencePayload(any(), any(), any(), any(), any(), any(), true, reversalId) }
    }

    @Test
    fun `unproven reversal never writes terminal status or success evidence`(): Unit = runBlocking {
        val existing = payment(status = SepaPaymentStatus.PROCESSING).copy(transactionId = UUID.randomUUID())
        coEvery { paymentRepository.findByEndToEndId(existing.endToEndId) } returns existing
        coEvery { paymentRepository.findById(existing.id) } returns existing
        coEvery { paymentRepository.markReturnUnresolved(existing.id, any()) } returns existing
        val failures = listOf(
            ReversalOutcome(false, null),
            ReversalOutcome(true, null),
        )
        for (outcome in failures) {
            coEvery { reversalPort.reverseTransaction(any(), any(), any()) } returns outcome
            assertThatThrownBy {
                runBlocking { service.handlePaymentReturn(returnCommand(pacs004Xml(existing.endToEndId))) }
            }
                .isInstanceOf(Unavailable::class.java)
        }
        coEvery { reversalPort.reverseTransaction(any(), any(), any()) } throws
            ReversalUnavailableException("HTTP 409")
        assertThatThrownBy {
            runBlocking { service.handlePaymentReturn(returnCommand(pacs004Xml(existing.endToEndId))) }
        }
            .isInstanceOf(Unavailable::class.java)
        coVerify(exactly = 3) { paymentRepository.markReturnUnresolved(existing.id, "REVERSAL_UNPROVEN") }
        coVerify(exactly = 0) { paymentRepository.updateWithEvidence(any(), any(), any()) }
        verify(exactly = 0) {
            eventPublisher.returnEvidencePayload(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `missing original transaction is unresolved and never interpreted as no debit`(): Unit = runBlocking {
        val existing = payment(status = SepaPaymentStatus.PROCESSING)
        coEvery { paymentRepository.findByEndToEndId(existing.endToEndId) } returns existing
        coEvery { paymentRepository.findById(existing.id) } returns existing
        coEvery { paymentRepository.markReturnUnresolved(existing.id, any()) } returns existing
        assertThatThrownBy {
            runBlocking { service.handlePaymentReturn(returnCommand(pacs004Xml(existing.endToEndId))) }
        }
            .isInstanceOf(Unavailable::class.java)
        coVerify { paymentRepository.markReturnUnresolved(existing.id, "UNRESOLVED_ORIGINAL_TRANSACTION") }
        coVerify(exactly = 0) { reversalPort.reverseTransaction(any(), any(), any()) }
        coVerify(exactly = 0) { paymentRepository.updateWithEvidence(any(), any(), any()) }
    }

    @Test
    fun `recovery uses persisted original identity key and metadata instead of retry caller`(): Unit = runBlocking {
        val originalTx = UUID.randomUUID()
        val intent = SepaReturnIntent(
            originalTx,
            Instant.parse("2026-01-01T00:00:00Z"),
            "AM09",
            "original-actor",
            "ROLE_API",
            "original-correlation",
        )
        val existing = payment(status = SepaPaymentStatus.PROCESSING).copy(
            transactionId = UUID.randomUUID(),
            returnIntent = intent,
        )
        coEvery { paymentRepository.findByEndToEndId(existing.endToEndId) } returns existing
        coEvery { paymentRepository.beginReturn(existing.id, any()) } returns existing
        service.handlePaymentReturn(returnCommand(pacs004Xml(existing.endToEndId, "AC01")))
        coVerify { reversalPort.reverseTransaction(originalTx, "sepa-reversal-${existing.id}", "AM09") }
        verify {
            eventPublisher.returnEvidencePayload(
                any(),
                existing.endToEndId,
                "AM09",
                "original-actor",
                "ROLE_API",
                "original-correlation",
                true,
                any(),
            )
        }
        coVerify {
            paymentRepository.updateWithEvidence(
                match {
                    it.status == SepaPaymentStatus.RETURNED &&
                        it.returnIntent?.firstReceivedAt == intent.firstReceivedAt &&
                        it.returnIntent?.resolvedAt != null &&
                        it.returnIntent?.reversalTransactionId != null
                },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `settled payment can return under ADR0111 only after proven reversal`(): Unit = runBlocking {
        val existing = payment(status = SepaPaymentStatus.COMPLETED).copy(transactionId = UUID.randomUUID())
        coEvery { paymentRepository.findByEndToEndId(existing.endToEndId) } returns existing
        coEvery { paymentRepository.findById(existing.id) } returns existing
        val returned = service.handlePaymentReturn(returnCommand(pacs004Xml(existing.endToEndId)))
        assertThat(returned.status).isEqualTo(SepaPaymentStatus.RETURNED)
        assertThat(returned.returnIntent?.resolvedAt).isNotNull()
    }

    @Test
    fun `unsupported return state is rejected before reversal IO`(): Unit = runBlocking {
        val existing = payment(status = SepaPaymentStatus.CANCELLED).copy(transactionId = UUID.randomUUID())
        coEvery { paymentRepository.findByEndToEndId(existing.endToEndId) } returns existing
        assertThatThrownBy {
            runBlocking { service.handlePaymentReturn(returnCommand(pacs004Xml(existing.endToEndId))) }
        }
            .isInstanceOf(InvalidSepaPaymentStateTransitionException::class.java)
        coVerify(exactly = 0) { paymentRepository.beginReturn(any(), any()) }
        coVerify(exactly = 0) { reversalPort.reverseTransaction(any(), any(), any()) }
    }

    @Test
    fun `handlePaymentReturn is idempotent when payment already RETURNED`(): Unit = runBlocking {
        val existing = payment(status = SepaPaymentStatus.RETURNED)
        coEvery { paymentRepository.findByEndToEndId(existing.endToEndId) } returns existing
        coEvery { paymentRepository.findById(existing.id) } returns existing

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
        amount = Money.of(BigDecimal("205.45"), "EUR"),
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
        amount = Money.of(BigDecimal("205.45"), "EUR"),
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
