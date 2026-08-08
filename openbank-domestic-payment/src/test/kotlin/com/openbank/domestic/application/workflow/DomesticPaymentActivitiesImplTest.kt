// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import com.openbank.domestic.application.port.out.AmlCasePort
import com.openbank.domestic.application.port.out.AmlCaseRiskLevel
import com.openbank.domestic.application.port.out.DomesticPaymentEventPublisher
import com.openbank.domestic.application.port.out.DomesticPaymentRepository
import com.openbank.domestic.application.port.out.FraudScoreOutcome
import com.openbank.domestic.application.port.out.FraudScoringPort
import com.openbank.domestic.application.port.out.FraudVerdict
import com.openbank.domestic.application.port.out.OpenAmlCaseCommand
import com.openbank.domestic.application.port.out.SanctionsScreeningPort
import com.openbank.domestic.application.port.out.SchemeGatewayPort
import com.openbank.domestic.application.port.out.SchemeGatewayUnavailableException
import com.openbank.domestic.application.port.out.SchemeSubmissionOutcome
import com.openbank.domestic.application.port.out.ScreeningUnavailableException
import com.openbank.domestic.application.port.out.SettlementOutcome
import com.openbank.domestic.application.port.out.SettlementPort
import com.openbank.domestic.application.port.out.SettlementUnavailableException
import com.openbank.domestic.domain.model.DomesticPayment
import com.openbank.domestic.domain.model.DomesticPaymentPriority
import com.openbank.domestic.domain.model.DomesticPaymentStatus
import com.openbank.domestic.domain.model.DomesticRejectReason
import com.openbank.domestic.domain.model.DomesticTransferScope
import com.openbank.domestic.domain.screening.ScreeningDecision
import com.openbank.domestic.domain.screening.ScreeningMatchStatus
import com.openbank.domestic.domain.screening.ScreeningResult
import com.openbank.domestic.domain.screening.ScreeningRole.CREDITOR
import com.openbank.domestic.domain.screening.ScreeningRole.DEBTOR
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for the Temporal activity implementations (ADR-0101 P2). The
 * activities are plain suspend-free entrypoints (they wrap their port calls in
 * runBlocking), so they exercise directly with mocked ports.
 */
class DomesticPaymentActivitiesImplTest {
    private lateinit var paymentRepository: DomesticPaymentRepository
    private lateinit var eventPublisher: DomesticPaymentEventPublisher
    private lateinit var screeningPort: SanctionsScreeningPort
    private lateinit var amlCasePort: AmlCasePort
    private lateinit var fraudScoringPort: FraudScoringPort
    private lateinit var schemeGatewayPort: SchemeGatewayPort
    private lateinit var settlementPort: SettlementPort

    private lateinit var activities: DomesticPaymentActivitiesImpl
    private lateinit var activitiesWithScheme: DomesticPaymentActivitiesImpl

    @BeforeEach
    fun setUp() {
        paymentRepository = mockk()
        eventPublisher = mockk()
        screeningPort = mockk()
        amlCasePort = mockk()
        fraudScoringPort = mockk()
        activities = object : DomesticPaymentActivitiesImpl(
            paymentRepository,
            eventPublisher,
            screeningPort,
            amlCasePort,
            fraudScoringPort,
            schemeGatewayPort = mockk(),
            settlementPort = mockk(),
            clock = Clock.systemUTC(),
            schemeSubmissionEnabled = false,
        ) {
            override fun <T> vtx(block: suspend () -> T): T = runBlocking { block() }
        }
        coEvery { paymentRepository.update(any(), any()) } answers { firstArg() }
        every { eventPublisher.statusChangedPayload(any(), any()) } returns "{\"event\":\"status-changed\"}"
        coJustRun { amlCasePort.openCase(any()) }

        schemeGatewayPort = mockk()
        settlementPort = mockk()
        activitiesWithScheme = object : DomesticPaymentActivitiesImpl(
            paymentRepository,
            eventPublisher,
            screeningPort,
            amlCasePort,
            fraudScoringPort,
            schemeGatewayPort = schemeGatewayPort,
            settlementPort = settlementPort,
            clock = Clock.systemUTC(),
            schemeSubmissionEnabled = true,
        ) {
            override fun <T> vtx(block: suspend () -> T): T = runBlocking { block() }
        }
    }

    @Test
    fun `screenPayment returns CLEAR and opens no AML case when both names clear`() {
        val payment = payment()
        coEvery { paymentRepository.findById(payment.id) } returns payment
        coEvery { screeningPort.screen(any(), any(), any()) } answers {
            ScreeningResult(firstArg(), secondArg(), ScreeningMatchStatus.CLEAR, 0.0, null)
        }

        val decision = activities.screenPayment(payment.id)

        assertThat(decision).isEqualTo(ScreeningDecision.CLEAR)
        coVerify(exactly = 0) { amlCasePort.openCase(any()) }
    }

    @Test
    fun `screenPayment returns BLOCK and opens CRITICAL AML case on a sanctions hit`() {
        val payment = payment()
        coEvery { paymentRepository.findById(payment.id) } returns payment
        coEvery { screeningPort.screen(payment.debtorName, DEBTOR, any()) } returns
            ScreeningResult(payment.debtorName, DEBTOR, ScreeningMatchStatus.HIT, 0.99, "OFAC:123")
        coEvery { screeningPort.screen(payment.creditorName, CREDITOR, any()) } returns
            ScreeningResult(payment.creditorName, CREDITOR, ScreeningMatchStatus.CLEAR, 0.0, null)

        val captured = slot<OpenAmlCaseCommand>()
        coJustRun { amlCasePort.openCase(capture(captured)) }

        val decision = activities.screenPayment(payment.id)

        assertThat(decision).isEqualTo(ScreeningDecision.BLOCK)
        assertThat(captured.captured.riskLevel).isEqualTo(AmlCaseRiskLevel.CRITICAL)
        assertThat(captured.captured.matchedEntity).isEqualTo("OFAC:123")
        assertThat(captured.captured.idempotencyKey).isEqualTo("aml-${payment.id}-SANCTIONS_HIT")
    }

    @Test
    fun `screenPayment returns REVIEW and opens HIGH AML case on a potential hit`() {
        val payment = payment()
        coEvery { paymentRepository.findById(payment.id) } returns payment
        coEvery { screeningPort.screen(payment.debtorName, DEBTOR, any()) } returns
            ScreeningResult(payment.debtorName, DEBTOR, ScreeningMatchStatus.POTENTIAL_HIT, 0.5, null)
        coEvery { screeningPort.screen(payment.creditorName, CREDITOR, any()) } returns
            ScreeningResult(payment.creditorName, CREDITOR, ScreeningMatchStatus.CLEAR, 0.0, null)

        val captured = slot<OpenAmlCaseCommand>()
        coJustRun { amlCasePort.openCase(capture(captured)) }

        val decision = activities.screenPayment(payment.id)

        assertThat(decision).isEqualTo(ScreeningDecision.REVIEW)
        assertThat(captured.captured.riskLevel).isEqualTo(AmlCaseRiskLevel.HIGH)
    }

    @Test
    fun `screenPayment returns REVIEW and opens MEDIUM case when screening unavailable`() {
        val payment = payment()
        coEvery { paymentRepository.findById(payment.id) } returns payment
        coEvery { screeningPort.screen(any(), any(), any()) } throws
            ScreeningUnavailableException(RuntimeException("provider down"))

        val captured = slot<OpenAmlCaseCommand>()
        coJustRun { amlCasePort.openCase(capture(captured)) }

        val decision = activities.screenPayment(payment.id)

        assertThat(decision).isEqualTo(ScreeningDecision.REVIEW)
        assertThat(captured.captured.riskLevel).isEqualTo(AmlCaseRiskLevel.MEDIUM)
        assertThat(captured.captured.idempotencyKey).isEqualTo("aml-${payment.id}-SCREENING_UNAVAILABLE")
    }

    @Test
    fun `screenPayment swallows AML case failures (fail-open)`() {
        val payment = payment()
        coEvery { paymentRepository.findById(payment.id) } returns payment
        coEvery { screeningPort.screen(any(), any(), any()) } answers {
            ScreeningResult(firstArg(), secondArg(), ScreeningMatchStatus.HIT, 0.99, null)
        }
        coEvery { amlCasePort.openCase(any()) } throws RuntimeException("aml service down")

        // The AML failure must not propagate — the screening decision still returns.
        val decision = activities.screenPayment(payment.id)

        assertThat(decision).isEqualTo(ScreeningDecision.BLOCK)
    }

    @Test
    fun `screenPayment throws when payment is missing`() {
        val id = UUID.randomUUID()
        coEvery { paymentRepository.findById(id) } returns null

        assertThatThrownBy { activities.screenPayment(id) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `validatePayment transitions to VALIDATED and persists status-changed outbox`() {
        val payment = payment()
        coEvery { paymentRepository.findById(payment.id) } returns payment

        val updated = slot<DomesticPayment>()
        val outbox = slot<OutboxMessage>()
        coEvery { paymentRepository.update(capture(updated), capture(outbox)) } answers { firstArg() }

        activities.validatePayment(payment.id)

        assertThat(updated.captured.status).isEqualTo(DomesticPaymentStatus.VALIDATED)
        assertThat(outbox.captured.eventType).isEqualTo("domestic.payment.status-changed")
        coVerify(exactly = 1) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `validatePayment is a no-op on a payment that already moved past validation`() {
        // A payment stranded in SENT_TO_CLEARING is recovered by re-driving the workflow, which
        // replays every activity from the top. Without the guard this call throws "Invalid
        // domestic payment status transition: SENT_TO_CLEARING -> VALIDATED" and the re-drive
        // never reaches settlePayment — the step that actually recovers it (#4182).
        val payment = payment(status = DomesticPaymentStatus.SENT_TO_CLEARING)
        coEvery { paymentRepository.findById(payment.id) } returns payment

        activities.validatePayment(payment.id)

        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `rejectPayment transitions to REJECTED with SANCTIONS_HIT reason`() {
        val payment = payment()
        coEvery { paymentRepository.findById(payment.id) } returns payment

        val updated = slot<DomesticPayment>()
        coEvery { paymentRepository.update(capture(updated), any()) } answers { firstArg() }

        activities.rejectPayment(payment.id)

        assertThat(updated.captured.status).isEqualTo(DomesticPaymentStatus.REJECTED)
        assertThat(updated.captured.rejectReason).isEqualTo(DomesticRejectReason.SANCTIONS_HIT)
    }

    @Test
    fun `shadowFraudScore scores via the port and observes a non-ALLOW verdict`() {
        val payment = payment()
        coEvery { paymentRepository.findById(payment.id) } returns payment
        coEvery { fraudScoringPort.score(any()) } returns
            FraudScoreOutcome(FraudVerdict.REVIEW, 88, "v1", listOf("velocity"))

        activities.shadowFraudScore(payment.id)

        coVerify(exactly = 1) { fraudScoringPort.score(any()) }
    }

    @Test
    fun `shadowFraudScore is a no-op observation on ALLOW`() {
        val payment = payment()
        coEvery { paymentRepository.findById(payment.id) } returns payment
        coEvery { fraudScoringPort.score(any()) } returns
            FraudScoreOutcome(FraudVerdict.ALLOW, 0, "v1", emptyList())

        activities.shadowFraudScore(payment.id)

        coVerify(exactly = 1) { fraudScoringPort.score(any()) }
    }

    @Test
    fun `submitScheme advances VALIDATED payment to SENT_TO_CLEARING on scheme accept (ACSC)`() {
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = true, reasonCode = null)

        val result = activitiesWithScheme.submitScheme(validated.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.SENT_TO_CLEARING)
        coVerify {
            paymentRepository.update(
                match { it.status == DomesticPaymentStatus.SENT_TO_CLEARING },
                any(),
            )
        }
    }

    @Test
    fun `submitScheme advances VALIDATED payment to REJECTED on scheme reject AC04`() {
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = false, reasonCode = "AC04")

        val result = activitiesWithScheme.submitScheme(validated.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.REJECTED)
        coVerify {
            paymentRepository.update(
                match {
                    it.status == DomesticPaymentStatus.REJECTED &&
                        it.rejectReason == DomesticRejectReason.BENEFICIARY_ACCOUNT_CLOSED
                },
                any(),
            )
        }
    }

    @Test
    fun `submitScheme holds payment in VALIDATED when gateway is unavailable (fail-closed)`() {
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } throws
            SchemeGatewayUnavailableException(RuntimeException("connection refused"))

        val result = activitiesWithScheme.submitScheme(validated.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.VALIDATED)
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `submitScheme returns VALIDATED immediately when scheme submission is disabled`() {
        val id = payment(status = DomesticPaymentStatus.VALIDATED).id

        val result = activities.submitScheme(id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.VALIDATED)
        coVerify(exactly = 0) { paymentRepository.findById(any()) }
    }

    @Test
    fun `submitScheme returns current status unchanged when payment is not VALIDATED`() {
        val received = payment(status = DomesticPaymentStatus.RECEIVED)
        coEvery { paymentRepository.findById(received.id) } returns received

        val result = activitiesWithScheme.submitScheme(received.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.RECEIVED)
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `submitScheme maps AC06 to BENEFICIARY_ACCOUNT_CLOSED`() {
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = false, reasonCode = "AC06")

        val result = activitiesWithScheme.submitScheme(validated.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.REJECTED)
        coVerify {
            paymentRepository.update(
                match { it.rejectReason == DomesticRejectReason.BENEFICIARY_ACCOUNT_CLOSED },
                any(),
            )
        }
    }

    @Test
    fun `submitScheme maps RC01 to INVALID_BANK_CODE`() {
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = false, reasonCode = "RC01")

        val result = activitiesWithScheme.submitScheme(validated.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.REJECTED)
        coVerify {
            paymentRepository.update(
                match { it.rejectReason == DomesticRejectReason.INVALID_BANK_CODE },
                any(),
            )
        }
    }

    @Test
    fun `submitScheme maps AM05 to INSUFFICIENT_FUNDS`() {
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = false, reasonCode = "AM05")

        val result = activitiesWithScheme.submitScheme(validated.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.REJECTED)
        coVerify {
            paymentRepository.update(
                match { it.rejectReason == DomesticRejectReason.INSUFFICIENT_FUNDS },
                any(),
            )
        }
    }

    @Test
    fun `submitScheme maps unknown scheme reason code to TECHNICAL_ERROR`() {
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = false, reasonCode = "XX99")

        val result = activitiesWithScheme.submitScheme(validated.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.REJECTED)
        coVerify {
            paymentRepository.update(
                match { it.rejectReason == DomesticRejectReason.TECHNICAL_ERROR },
                any(),
            )
        }
    }

    @Test
    fun `submitScheme holds payment in VALIDATED on unexpected exception (fail-closed)`() {
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } throws RuntimeException("unexpected crash")

        val result = activitiesWithScheme.submitScheme(validated.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.VALIDATED)
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `settlePayment transitions SENT_TO_CLEARING to SETTLED on success`() {
        val sentToClearing = payment(status = DomesticPaymentStatus.SENT_TO_CLEARING)
        coEvery { paymentRepository.findById(sentToClearing.id) } returns sentToClearing
        coEvery { settlementPort.settle(any()) } returns
            SettlementOutcome(settled = true, transactionId = UUID.randomUUID())

        val updated = slot<DomesticPayment>()
        coEvery { paymentRepository.update(capture(updated), any()) } answers { firstArg() }

        val result = activitiesWithScheme.settlePayment(sentToClearing.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.SETTLED)
        assertThat(updated.captured.status).isEqualTo(DomesticPaymentStatus.SETTLED)
        coVerify(exactly = 1) { settlementPort.settle(any()) }
    }

    @Test
    fun `settlePayment holds in SENT_TO_CLEARING when settlement unavailable`() {
        val sentToClearing = payment(status = DomesticPaymentStatus.SENT_TO_CLEARING)
        coEvery { paymentRepository.findById(sentToClearing.id) } returns sentToClearing
        coEvery { settlementPort.settle(any()) } throws
            SettlementUnavailableException("transaction-service down")

        val result = activitiesWithScheme.settlePayment(sentToClearing.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.SENT_TO_CLEARING)
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `settlePayment holds in SENT_TO_CLEARING on unexpected exception`() {
        val sentToClearing = payment(status = DomesticPaymentStatus.SENT_TO_CLEARING)
        coEvery { paymentRepository.findById(sentToClearing.id) } returns sentToClearing
        coEvery { settlementPort.settle(any()) } throws RuntimeException("unexpected crash")

        val result = activitiesWithScheme.settlePayment(sentToClearing.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.SENT_TO_CLEARING)
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `settlePayment returns current status unchanged when not in SENT_TO_CLEARING`() {
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated

        val result = activitiesWithScheme.settlePayment(validated.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.VALIDATED)
        coVerify(exactly = 0) { settlementPort.settle(any()) }
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    private fun payment(status: DomesticPaymentStatus = DomesticPaymentStatus.RECEIVED) = DomesticPayment(
        id = UUID.randomUUID(),
        idempotencyKey = "dom-idem-activity",
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
        transferScope = DomesticTransferScope.EXTERNAL,
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
