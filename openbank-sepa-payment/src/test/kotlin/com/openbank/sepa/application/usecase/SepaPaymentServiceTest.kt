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
import com.openbank.sepa.application.port.out.AmlCasePort
import com.openbank.sepa.application.port.out.AmlCaseRiskLevel
import com.openbank.sepa.application.port.out.FraudScoreOutcome
import com.openbank.sepa.application.port.out.FraudScoringPort
import com.openbank.sepa.application.port.out.FraudVerdict
import com.openbank.sepa.application.port.out.ReversalPort
import com.openbank.sepa.application.port.out.SanctionsScreeningPort
import com.openbank.sepa.application.port.out.SchemeGatewayPort
import com.openbank.sepa.application.port.out.SchemeGatewayUnavailableException
import com.openbank.sepa.application.port.out.SchemeSubmissionOutcome
import com.openbank.sepa.application.port.out.ScreeningUnavailableException
import com.openbank.sepa.application.port.out.SepaPaymentEventPublisher
import com.openbank.sepa.application.port.out.SepaPaymentOutboxMessage
import com.openbank.sepa.application.port.out.SepaPaymentRepository
import com.openbank.sepa.application.port.out.SettlementOutcome
import com.openbank.sepa.application.port.out.SettlementPort
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import com.openbank.sepa.domain.model.SepaRejectReason
import com.openbank.sepa.domain.screening.ScreeningMatchStatus
import com.openbank.sepa.domain.screening.ScreeningResult
import com.openbank.sepa.domain.screening.ScreeningRole
import io.mockk.coEvery
import io.mockk.coJustRun
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

class SepaPaymentServiceTest {

    private lateinit var paymentRepository: SepaPaymentRepository
    private lateinit var eventPublisher: SepaPaymentEventPublisher
    private lateinit var screeningPort: SanctionsScreeningPort
    private lateinit var amlCasePort: AmlCasePort
    private lateinit var fraudScoringPort: FraudScoringPort
    private lateinit var schemeGatewayPort: SchemeGatewayPort
    private lateinit var settlementPort: SettlementPort
    private lateinit var reversalPort: ReversalPort
    private lateinit var metrics: DomainMetrics

    private lateinit var service: SepaPaymentService

    @BeforeEach
    fun setUp() {
        paymentRepository = mockk()
        eventPublisher = mockk()
        screeningPort = mockk()
        amlCasePort = mockk()
        fraudScoringPort = mockk()
        schemeGatewayPort = mockk()
        settlementPort = mockk()
        reversalPort = mockk()
        // all metric calls are fire-and-forget; relaxed avoids stub boilerplate
        metrics = mockk(relaxed = true)
        service = buildService(schemeSubmissionEnabled = false)

        // ADR-0104 D3: scheme submission is OFF by default, so most tests see today's behaviour.

        // Fraud scoring is SHADOW (ADR-0084): default to ALLOW; never affects the payment outcome.
        coEvery { fraudScoringPort.score(any()) } returns FraudScoreOutcome(FraudVerdict.ALLOW, 0, "v0", emptyList())

        // Defaults reused by most tests: repo echoes the aggregate, publisher returns opaque payloads.
        coEvery { paymentRepository.save(any(), any()) } answers { firstArg() }
        coEvery { paymentRepository.update(any(), any()) } answers { firstArg() }
        every { eventPublisher.paymentCreatedPayload(any()) } returns "{\"event\":\"created\"}"
        every { eventPublisher.statusChangedPayload(any(), any()) } returns "{\"event\":\"status-changed\"}"
        coJustRun { amlCasePort.openCase(any()) }
        // Default: settlement succeeds — tests override this as needed
        coEvery { settlementPort.settle(any()) } returns SettlementOutcome(settled = true, transactionId = null)
    }

    private fun clear(role: ScreeningRole) = ScreeningResult("name", role, ScreeningMatchStatus.CLEAR, 0.0, null)

    private fun buildService(schemeSubmissionEnabled: Boolean) = SepaPaymentService(
        paymentRepository,
        eventPublisher,
        screeningPort,
        amlCasePort,
        fraudScoringPort,
        schemeGatewayPort,
        settlementPort,
        reversalPort,
        metrics,
        schemeSubmissionEnabled = schemeSubmissionEnabled,
        temporalEnabled = false,
        temporalTaskQueue = "openbank-sepa-payment",
        workflowClient = mockk<WorkflowClient>(relaxed = true),
    )

    private suspend fun createCleanlyScreenedPayment(): SepaPayment {
        val command = createCommand()
        coEvery { paymentRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { screeningPort.screen(any(), ScreeningRole.DEBTOR, any()) } returns clear(ScreeningRole.DEBTOR)
        coEvery { screeningPort.screen(any(), ScreeningRole.CREDITOR, any()) } returns clear(ScreeningRole.CREDITOR)
        return buildService(schemeSubmissionEnabled = true).createPayment(command)
    }

    @Test
    fun `D3 scheme ACSC advances a validated payment through PROCESSING to COMPLETED`(): Unit = runBlocking {
        coEvery { schemeGatewayPort.submit(any()) } returns SchemeSubmissionOutcome(accepted = true, reasonCode = null)

        val result = createCleanlyScreenedPayment()

        assertThat(result.status).isEqualTo(SepaPaymentStatus.COMPLETED)
        coVerify { schemeGatewayPort.submit(match { it.status == SepaPaymentStatus.VALIDATED }) }
        coVerify { paymentRepository.update(match { it.status == SepaPaymentStatus.PROCESSING }, any()) }
        coVerify { settlementPort.settle(match { it.status == SepaPaymentStatus.PROCESSING }) }
        coVerify { paymentRepository.update(match { it.status == SepaPaymentStatus.COMPLETED }, any()) }
    }

    @Test
    fun `D3 scheme ACSC settlement unavailable leaves payment in PROCESSING`(): Unit = runBlocking {
        coEvery { schemeGatewayPort.submit(any()) } returns SchemeSubmissionOutcome(accepted = true, reasonCode = null)
        coEvery { settlementPort.settle(any()) } throws
            com.openbank.sepa.application.port.out.SettlementUnavailableException("down")

        val result = createCleanlyScreenedPayment()

        assertThat(result.status).isEqualTo(SepaPaymentStatus.PROCESSING)
    }

    @Test
    fun `D3 scheme RJCT rejects the payment with the mapped reason`(): Unit = runBlocking {
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = false, reasonCode = "AC04")

        val result = createCleanlyScreenedPayment()

        assertThat(result.status).isEqualTo(SepaPaymentStatus.REJECTED)
        assertThat(result.rejectReason).isEqualTo(SepaRejectReason.ACCOUNT_CLOSED)
        assertThat(result.rejectDetail).contains("AC04")
    }

    @Test
    fun `D3 scheme gateway outage holds the payment in VALIDATED (fail-closed)`(): Unit = runBlocking {
        coEvery { schemeGatewayPort.submit(any()) } throws
            SchemeGatewayUnavailableException(RuntimeException("down"))

        val result = createCleanlyScreenedPayment()

        assertThat(result.status).isEqualTo(SepaPaymentStatus.VALIDATED)
    }

    @Test
    fun `clean screening validates the payment and persists both outbox messages`(): Unit = runBlocking {
        val command = createCommand()
        coEvery { paymentRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { screeningPort.screen(any(), ScreeningRole.DEBTOR, any()) } returns clear(ScreeningRole.DEBTOR)
        coEvery { screeningPort.screen(any(), ScreeningRole.CREDITOR, any()) } returns clear(ScreeningRole.CREDITOR)

        val result = service.createPayment(command)

        assertThat(result.status).isEqualTo(SepaPaymentStatus.VALIDATED)
        assertThat(result.debtorName).isEqualTo("Alice Example")
        assertThat(result.creditorName).isEqualTo("Bob Example")
        coVerify {
            paymentRepository.save(
                match { it.status == SepaPaymentStatus.RECEIVED && it.idempotencyKey == command.idempotencyKey },
                match<SepaPaymentOutboxMessage> { it.eventType == "sepa.payment.created" },
            )
        }
        coVerify {
            paymentRepository.update(
                match { it.status == SepaPaymentStatus.VALIDATED },
                match<SepaPaymentOutboxMessage> { it.eventType == "sepa.payment.status-changed" },
            )
        }
        coVerify(exactly = 0) { amlCasePort.openCase(any()) }
        // SHADOW: the scorer is consulted on every created payment (ADR-0084 §4.1).
        coVerify(exactly = 1) { fraudScoringPort.score(any()) }
    }

    @Test
    fun `fraud shadow verdict is observed but never enforced`(): Unit = runBlocking {
        val command = createCommand()
        coEvery { paymentRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { screeningPort.screen(any(), ScreeningRole.DEBTOR, any()) } returns clear(ScreeningRole.DEBTOR)
        coEvery { screeningPort.screen(any(), ScreeningRole.CREDITOR, any()) } returns clear(ScreeningRole.CREDITOR)
        // A blocking-looking verdict in SHADOW must NOT change the screening outcome.
        coEvery { fraudScoringPort.score(any()) } returns
            FraudScoreOutcome(FraudVerdict.DECLINE, 99, "v0", listOf("velocity-cap"))

        val result = service.createPayment(command)

        // Still VALIDATED — shadow scoring is observe-only; the payment proceeds on its screening outcome.
        assertThat(result.status).isEqualTo(SepaPaymentStatus.VALIDATED)
        coVerify(exactly = 1) { fraudScoringPort.score(any()) }
    }

    @Test
    fun `sanctioned creditor rejects the payment and opens a CRITICAL aml case`(): Unit = runBlocking {
        val command = createCommand()
        coEvery { paymentRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        val hit = ScreeningResult(
            "Bob Example",
            ScreeningRole.CREDITOR,
            ScreeningMatchStatus.HIT,
            0.97,
            "BOB EXAMPLE / OFAC",
        )
        coEvery { screeningPort.screen(any(), ScreeningRole.DEBTOR, any()) } returns clear(ScreeningRole.DEBTOR)
        coEvery { screeningPort.screen(any(), ScreeningRole.CREDITOR, any()) } returns hit

        val result = service.createPayment(command)

        assertThat(result.status).isEqualTo(SepaPaymentStatus.REJECTED)
        assertThat(result.rejectReason).isEqualTo(SepaRejectReason.SANCTIONS_HIT)
        coVerify {
            amlCasePort.openCase(
                match {
                    it.riskLevel == AmlCaseRiskLevel.CRITICAL &&
                        it.alertCode == "SANCTIONS_HIT" &&
                        it.paymentId == result.id &&
                        it.matchedEntity == "BOB EXAMPLE / OFAC"
                },
            )
        }
        coVerify { paymentRepository.update(match { it.status == SepaPaymentStatus.REJECTED }, any()) }
    }

    @Test
    fun `sub-threshold potential hit holds the payment in RECEIVED and opens a HIGH aml case`(): Unit = runBlocking {
        val command = createCommand()
        coEvery { paymentRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        val potentialHit = ScreeningResult(
            "Bob Example",
            ScreeningRole.CREDITOR,
            ScreeningMatchStatus.POTENTIAL_HIT,
            0.50,
            "B. EXAMPLE",
        )
        coEvery { screeningPort.screen(any(), ScreeningRole.DEBTOR, any()) } returns clear(ScreeningRole.DEBTOR)
        coEvery { screeningPort.screen(any(), ScreeningRole.CREDITOR, any()) } returns potentialHit

        val result = service.createPayment(command)

        assertThat(result.status).isEqualTo(SepaPaymentStatus.RECEIVED)
        coVerify {
            amlCasePort.openCase(
                match { it.riskLevel == AmlCaseRiskLevel.HIGH && it.alertCode == "AML_HOLD" },
            )
        }
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `screening unavailable holds the payment fail-closed and opens a MEDIUM aml case`(): Unit = runBlocking {
        val command = createCommand()
        val unavailable = ScreeningUnavailableException(RuntimeException("down"))
        coEvery { paymentRepository.findByIdempotencyKey(command.idempotencyKey) } returns null
        coEvery { screeningPort.screen(any(), any(), any()) } throws unavailable

        val result = service.createPayment(command)

        assertThat(result.status).isEqualTo(SepaPaymentStatus.RECEIVED)
        coVerify {
            amlCasePort.openCase(
                match { it.riskLevel == AmlCaseRiskLevel.MEDIUM && it.alertCode == "SCREENING_UNAVAILABLE" },
            )
        }
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `create payment replays existing payment for idempotency key without screening`(): Unit = runBlocking {
        val existing = payment()
        coEvery { paymentRepository.findByIdempotencyKey(existing.idempotencyKey) } returns existing

        val result = service.createPayment(createCommand(idempotencyKey = existing.idempotencyKey))

        assertThat(result).isEqualTo(existing)
        coVerify(exactly = 0) { paymentRepository.save(any(), any()) }
        coVerify(exactly = 0) { screeningPort.screen(any(), any(), any()) }
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

        val result = service.handlePaymentReturn(HandlePaymentReturnCommand(pacs004Xml(existing.endToEndId)))

        assertThat(result.status).isEqualTo(SepaPaymentStatus.RETURNED)
        coVerify { paymentRepository.update(match { it.status == SepaPaymentStatus.RETURNED }, any()) }
    }

    @Test
    fun `handlePaymentReturn is idempotent when payment already RETURNED`(): Unit = runBlocking {
        val existing = payment(status = SepaPaymentStatus.RETURNED)
        coEvery { paymentRepository.findByEndToEndId(existing.endToEndId) } returns existing

        val result = service.handlePaymentReturn(HandlePaymentReturnCommand(pacs004Xml(existing.endToEndId)))

        assertThat(result.status).isEqualTo(SepaPaymentStatus.RETURNED)
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `handlePaymentReturn throws for invalid XML`() {
        assertThatThrownBy {
            runBlocking { service.handlePaymentReturn(HandlePaymentReturnCommand("not-xml")) }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Invalid pacs.004")
    }

    @Test
    fun `handlePaymentReturn throws when no payment found for endToEndId`() {
        val unknownE2eId = "UNKNOWN-E2E"
        coEvery { paymentRepository.findByEndToEndId(unknownE2eId) } returns null
        val xml = pacs004Xml(unknownE2eId)

        assertThatThrownBy {
            runBlocking { service.handlePaymentReturn(HandlePaymentReturnCommand(xml)) }
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
