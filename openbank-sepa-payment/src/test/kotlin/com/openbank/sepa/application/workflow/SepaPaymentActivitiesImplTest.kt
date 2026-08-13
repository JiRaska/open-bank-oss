// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepa.application.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.sepa.application.port.out.AmlCasePort
import com.openbank.sepa.application.port.out.FraudScoreOutcome
import com.openbank.sepa.application.port.out.FraudScoringPort
import com.openbank.sepa.application.port.out.FraudVerdict
import com.openbank.sepa.application.port.out.SanctionsScreeningPort
import com.openbank.sepa.application.port.out.SchemeGatewayPort
import com.openbank.sepa.application.port.out.SchemeGatewayUnavailableException
import com.openbank.sepa.application.port.out.SchemeSubmissionOutcome
import com.openbank.sepa.application.port.out.ScreeningUnavailableException
import com.openbank.sepa.application.port.out.SepaPaymentOutboxMessage
import com.openbank.sepa.application.port.out.SepaPaymentRepository
import com.openbank.sepa.application.port.out.SettlementOutcome
import com.openbank.sepa.application.port.out.SettlementPort
import com.openbank.sepa.domain.model.SepaPayment
import com.openbank.sepa.domain.model.SepaPaymentStatus
import com.openbank.sepa.domain.model.SepaPaymentType
import com.openbank.sepa.domain.model.SepaRejectReason
import com.openbank.sepa.domain.screening.ScreeningDecision
import com.openbank.sepa.domain.screening.ScreeningMatchStatus
import com.openbank.sepa.domain.screening.ScreeningResult
import com.openbank.sepa.domain.screening.ScreeningRole
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SepaPaymentActivitiesImplTest {

    private lateinit var paymentRepository: SepaPaymentRepository
    private lateinit var screeningPort: SanctionsScreeningPort
    private lateinit var amlCasePort: AmlCasePort
    private lateinit var fraudScoringPort: FraudScoringPort
    private lateinit var schemeGatewayPort: SchemeGatewayPort
    private lateinit var settlementPort: SettlementPort
    private lateinit var activities: SepaPaymentActivitiesImpl

    private val clock = Clock.systemUTC()

    private val paymentId = UUID.randomUUID()
    private val payment = SepaPayment(
        id = paymentId,
        idempotencyKey = "key-1",
        type = SepaPaymentType.SCT,
        status = SepaPaymentStatus.RECEIVED,
        debtorAccountId = UUID.randomUUID(),
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice",
        creditorIban = "GB29NWBK60161331926819",
        creditorName = "Bob",
        creditorBic = null,
        amount = BigDecimal("100.00"),
        currency = "EUR",
        remittanceInfo = null,
        endToEndId = "E2E001",
        rejectReason = null,
        rejectDetail = null,
        submittedAt = null,
        completedAt = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @BeforeEach
    fun setUp() {
        paymentRepository = mockk()
        screeningPort = mockk()
        amlCasePort = mockk()
        fraudScoringPort = mockk()
        schemeGatewayPort = mockk()
        settlementPort = mockk()
        activities = TestableActivities(
            paymentRepository,
            screeningPort,
            amlCasePort,
            fraudScoringPort,
            schemeGatewayPort,
            settlementPort,
            clock = clock,
            schemeSubmissionEnabled = true,
        )
        coJustRun { amlCasePort.openCase(any()) }
        coEvery { settlementPort.settle(any()) } returns SettlementOutcome(settled = true, transactionId = null)
        coEvery { paymentRepository.update(any(), any()) } answers { firstArg() }
    }

    @Test
    fun `screenPayment returns CLEAR when both names are clear`() {
        coEvery { paymentRepository.findById(paymentId) } returns payment
        coEvery { screeningPort.screen(any(), ScreeningRole.DEBTOR, any()) } returns
            ScreeningResult("Alice", ScreeningRole.DEBTOR, ScreeningMatchStatus.CLEAR, 0.0, null)
        coEvery { screeningPort.screen(any(), ScreeningRole.CREDITOR, any()) } returns
            ScreeningResult("Bob", ScreeningRole.CREDITOR, ScreeningMatchStatus.CLEAR, 0.0, null)

        val result = activities.screenPayment(paymentId)

        assertThat(result).isEqualTo(ScreeningDecision.CLEAR)
    }

    @Test
    fun `screenPayment returns BLOCK when a HIT is detected`() {
        coEvery { paymentRepository.findById(paymentId) } returns payment
        coEvery { screeningPort.screen(any(), ScreeningRole.DEBTOR, any()) } returns
            ScreeningResult("Alice", ScreeningRole.DEBTOR, ScreeningMatchStatus.HIT, 0.99, "SanctionedEntity")
        coEvery { screeningPort.screen(any(), ScreeningRole.CREDITOR, any()) } returns
            ScreeningResult("Bob", ScreeningRole.CREDITOR, ScreeningMatchStatus.CLEAR, 0.0, null)

        val result = activities.screenPayment(paymentId)

        assertThat(result).isEqualTo(ScreeningDecision.BLOCK)
        coVerify { amlCasePort.openCase(any()) }
    }

    @Test
    fun `screenPayment returns REVIEW on ScreeningUnavailableException`() {
        coEvery { paymentRepository.findById(paymentId) } returns payment
        coEvery { screeningPort.screen(any(), ScreeningRole.DEBTOR, any()) } throws
            ScreeningUnavailableException(RuntimeException("timeout"))
        coEvery { screeningPort.screen(any(), ScreeningRole.CREDITOR, any()) } throws
            ScreeningUnavailableException(RuntimeException("timeout"))

        val result = activities.screenPayment(paymentId)

        assertThat(result).isEqualTo(ScreeningDecision.REVIEW)
        coVerify { amlCasePort.openCase(any()) }
    }

    @Test
    fun `validatePayment transitions payment to VALIDATED`() {
        coEvery { paymentRepository.findById(paymentId) } returns payment

        activities.validatePayment(paymentId)

        coVerify {
            paymentRepository.update(
                match { it.status == SepaPaymentStatus.VALIDATED },
                any(),
            )
        }
    }

    @Test
    fun `rejectPayment transitions payment to REJECTED`() {
        coEvery { paymentRepository.findById(paymentId) } returns payment

        activities.rejectPayment(paymentId)

        coVerify {
            paymentRepository.update(
                match { it.status == SepaPaymentStatus.REJECTED },
                any(),
            )
        }
    }

    @Test
    fun `shadowFraudScore logs non-ALLOW verdict and does not throw`(): Unit = runBlocking {
        coEvery { paymentRepository.findById(paymentId) } returns payment
        coEvery { fraudScoringPort.score(any()) } returns
            FraudScoreOutcome(FraudVerdict.REVIEW, 75, "v1", listOf("velocity"))

        activities.shadowFraudScore(paymentId)
        // no assertion needed — test verifies no exception is thrown for non-ALLOW verdict
    }

    @Test
    fun `submitToScheme advances a VALIDATED payment through PROCESSING to COMPLETED on scheme accept (ACSC)`() {
        val validated = payment.copy(status = SepaPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(paymentId) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = true, reasonCode = null)

        val result = activities.submitToScheme(paymentId)

        assertThat(result).isEqualTo(SepaPaymentStatus.COMPLETED)
        coVerify { paymentRepository.update(match { it.status == SepaPaymentStatus.PROCESSING }, any()) }
        coVerify { settlementPort.settle(match { it.status == SepaPaymentStatus.PROCESSING }) }
        coVerify { paymentRepository.update(match { it.status == SepaPaymentStatus.COMPLETED }, any()) }
    }

    @Test
    fun `submitToScheme stays at PROCESSING when settlement is unavailable (fail open)`() {
        val validated = payment.copy(status = SepaPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(paymentId) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = true, reasonCode = null)
        coEvery { settlementPort.settle(any()) } throws
            com.openbank.sepa.application.port.out.SettlementUnavailableException("down")

        val result = activities.submitToScheme(paymentId)

        assertThat(result).isEqualTo(SepaPaymentStatus.PROCESSING)
        coVerify { paymentRepository.update(match { it.status == SepaPaymentStatus.PROCESSING }, any()) }
        coVerify(exactly = 0) { paymentRepository.update(match { it.status == SepaPaymentStatus.COMPLETED }, any()) }
    }

    @Test
    fun `submitToScheme rejects with the mapped reason on scheme reject (RJCT)`() {
        val validated = payment.copy(status = SepaPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(paymentId) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = false, reasonCode = "AC04")

        val result = activities.submitToScheme(paymentId)

        assertThat(result).isEqualTo(SepaPaymentStatus.REJECTED)
        coVerify {
            paymentRepository.update(
                match { it.status == SepaPaymentStatus.REJECTED && it.rejectReason == SepaRejectReason.ACCOUNT_CLOSED },
                any(),
            )
        }
    }

    @Test
    fun `submitToScheme holds at VALIDATED when the gateway is unavailable (fail closed)`() {
        val validated = payment.copy(status = SepaPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(paymentId) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } throws
            SchemeGatewayUnavailableException(RuntimeException("gateway down"))

        val result = activities.submitToScheme(paymentId)

        assertThat(result).isEqualTo(SepaPaymentStatus.VALIDATED)
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `submitToScheme is a no-op when the pilot flag is off`() {
        val validated = payment.copy(status = SepaPaymentStatus.VALIDATED)
        val flagOff = TestableActivities(
            paymentRepository,
            screeningPort,
            amlCasePort,
            fraudScoringPort,
            schemeGatewayPort,
            settlementPort,
            clock = clock,
            schemeSubmissionEnabled = false,
        )
        coEvery { paymentRepository.findById(paymentId) } returns validated

        val result = flagOff.submitToScheme(paymentId)

        assertThat(result).isEqualTo(SepaPaymentStatus.VALIDATED)
        coVerify(exactly = 0) { schemeGatewayPort.submit(any()) }
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    // --- #3914: business event time on the Temporal-path payloads -----------------------------
    //
    // These five payloads carried no time field at all, so AuditConsumer.eventTime() returned null
    // and audit_entries recorded the CONSUMER's ingest time as the business time. The assertion is
    // an EXACT expected instant against a fixed clock, never `isNotNull()` — a non-null check would
    // pass against Instant.EPOCH, and an `isNotEmpty()` on the string would pass against the
    // four-character text "null" that Jackson's asText() yields for a JSON null.

    private val fixedInstant: Instant = Instant.parse("2026-03-01T12:34:56Z")

    private fun fixedClockActivities(): SepaPaymentActivitiesImpl = TestableActivities(
        paymentRepository,
        screeningPort,
        amlCasePort,
        fraudScoringPort,
        schemeGatewayPort,
        settlementPort,
        clock = Clock.fixed(fixedInstant, ZoneOffset.UTC),
        schemeSubmissionEnabled = true,
    )

    @Test
    fun `validatePayment stamps occurredAt with the transition instant`() {
        val outbox: CapturingSlot<SepaPaymentOutboxMessage> = slot()
        coEvery { paymentRepository.findById(paymentId) } returns payment
        coEvery { paymentRepository.update(any(), capture(outbox)) } answers { firstArg() }

        fixedClockActivities().validatePayment(paymentId)

        assertThat(outbox.captured.payload).contains(""""occurredAt":"$fixedInstant"""")
        val parsed = Instant.parse(
            objectMapper.readTree(outbox.captured.payload).get("occurredAt").asText(),
        )
        assertThat(parsed).isEqualTo(fixedInstant)
    }

    @Test
    fun `rejectPayment stamps occurredAt with the transition instant`() {
        val outbox: CapturingSlot<SepaPaymentOutboxMessage> = slot()
        coEvery { paymentRepository.findById(paymentId) } returns payment
        coEvery { paymentRepository.update(any(), capture(outbox)) } answers { firstArg() }

        fixedClockActivities().rejectPayment(paymentId)

        assertThat(Instant.parse(objectMapper.readTree(outbox.captured.payload).get("occurredAt").asText()))
            .isEqualTo(fixedInstant)
    }

    @Test
    fun `submitToScheme stamps occurredAt on PROCESSING and COMPLETED`() {
        val validated = payment.copy(status = SepaPaymentStatus.VALIDATED)
        val outboxes = mutableListOf<SepaPaymentOutboxMessage>()
        coEvery { paymentRepository.findById(paymentId) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } returns SchemeSubmissionOutcome(accepted = true, reasonCode = null)
        coEvery { paymentRepository.update(any(), capture(outboxes)) } answers { firstArg() }

        val result = fixedClockActivities().submitToScheme(paymentId)

        assertThat(result).isEqualTo(SepaPaymentStatus.COMPLETED)
        assertThat(outboxes).hasSize(2)
        assertThat(outboxes.map { objectMapper.readTree(it.payload).get("status").asText() })
            .containsExactly("PROCESSING", "COMPLETED")
        outboxes.forEach {
            assertThat(Instant.parse(objectMapper.readTree(it.payload).get("occurredAt").asText()))
                .isEqualTo(fixedInstant)
        }
    }

    private val objectMapper = ObjectMapper()
}

/**
 * Test double that runs the activity bodies on the calling thread via [runBlocking] instead of a
 * Vert.x duplicated context. A plain JUnit test has no Quarkus-managed Vert.x, so the production
 * `VertxContextSupport.subscribeAndAwait` would NPE on `VertxCoreRecorder.getVertx() == null`. The
 * mocked reactive ports resolve synchronously, so `runBlocking` is sufficient here.
 */
@Suppress("LongParameterList")
private class TestableActivities(
    paymentRepository: SepaPaymentRepository,
    screeningPort: SanctionsScreeningPort,
    amlCasePort: AmlCasePort,
    fraudScoringPort: FraudScoringPort,
    schemeGatewayPort: SchemeGatewayPort,
    settlementPort: SettlementPort,
    clock: Clock,
    schemeSubmissionEnabled: Boolean,
) : SepaPaymentActivitiesImpl(
    paymentRepository,
    screeningPort,
    amlCasePort,
    fraudScoringPort,
    schemeGatewayPort,
    settlementPort,
    clock,
    schemeSubmissionEnabled,
) {
    override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
}
