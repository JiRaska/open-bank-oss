// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.domestic.application.workflow

import com.openbank.domestic.application.port.out.AccountLookupPort
import com.openbank.domestic.application.port.out.AmlCasePort
import com.openbank.domestic.application.port.out.AmlCaseRiskLevel
import com.openbank.domestic.application.port.out.CustomerNotificationPort
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
import com.openbank.domestic.application.port.out.customerSafeReason
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
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
    private lateinit var accountLookupPort: AccountLookupPort
    private lateinit var customerNotificationPort: CustomerNotificationPort
    private lateinit var metrics: DomainMetrics

    private val ownerPartyId: UUID = UUID.randomUUID()

    private lateinit var activities: DomesticPaymentActivitiesImpl
    private lateinit var activitiesWithScheme: DomesticPaymentActivitiesImpl

    @BeforeEach
    fun setUp() {
        paymentRepository = mockk()
        eventPublisher = mockk()
        screeningPort = mockk()
        amlCasePort = mockk()
        fraudScoringPort = mockk()
        accountLookupPort = mockk()
        customerNotificationPort = mockk()
        metrics = mockk(relaxed = true)
        activities = object : DomesticPaymentActivitiesImpl(
            paymentRepository,
            eventPublisher,
            screeningPort,
            amlCasePort,
            fraudScoringPort,
            schemeGatewayPort = mockk(),
            settlementPort = mockk(),
            accountLookupPort = accountLookupPort,
            customerNotificationPort = customerNotificationPort,
            clock = Clock.systemUTC(),
            metrics = metrics,
            schemeSubmissionEnabled = false,
        ) {
            override fun <T> vtx(block: suspend () -> T): T = runBlocking { block() }
        }
        coEvery { paymentRepository.update(any(), any()) } answers { firstArg() }
        every { eventPublisher.statusChangedPayload(any(), any()) } returns "{\"event\":\"status-changed\"}"
        coJustRun { amlCasePort.openCase(any()) }
        coEvery { paymentRepository.claimSchemeDispatch(any(), any()) } returns true
        coJustRun { paymentRepository.clearSchemeDispatch(any()) }
        coEvery { accountLookupPort.findPartyByAccountId(any()) } returns ownerPartyId
        coJustRun { customerNotificationPort.notifyPaymentFailed(any(), any(), any(), any()) }

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
            accountLookupPort = accountLookupPort,
            customerNotificationPort = customerNotificationPort,
            clock = Clock.systemUTC(),
            metrics = metrics,
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

    // Issue #5049: openbank_sanctions_screenings_total / openbank_sanctions_hits_total had NO
    // call site anywhere in this class -- see the sepa-payment equivalent for why
    // sanctions-service itself cannot record these (no "role" concept of its own).
    @Test
    fun `screenPayment records sanctionsScreening for both roles and no hit when both are clear`() {
        val payment = payment()
        coEvery { paymentRepository.findById(payment.id) } returns payment
        coEvery { screeningPort.screen(any(), any(), any()) } answers {
            ScreeningResult(firstArg(), secondArg(), ScreeningMatchStatus.CLEAR, 0.0, null)
        }

        activities.screenPayment(payment.id)

        verify(exactly = 1) { metrics.sanctionsScreening("debtor") }
        verify(exactly = 1) { metrics.sanctionsScreening("creditor") }
        verify(exactly = 0) { metrics.sanctionsHit(any(), any()) }
    }

    @Test
    fun `screenPayment records a block-severity hit only for the debtor that HIT`() {
        val payment = payment()
        coEvery { paymentRepository.findById(payment.id) } returns payment
        coEvery { screeningPort.screen(payment.debtorName, DEBTOR, any()) } returns
            ScreeningResult(payment.debtorName, DEBTOR, ScreeningMatchStatus.HIT, 0.99, "OFAC:123")
        coEvery { screeningPort.screen(payment.creditorName, CREDITOR, any()) } returns
            ScreeningResult(payment.creditorName, CREDITOR, ScreeningMatchStatus.CLEAR, 0.0, null)
        coJustRun { amlCasePort.openCase(any()) }

        activities.screenPayment(payment.id)

        verify(exactly = 1) { metrics.sanctionsHit("debtor", "block") }
        verify(exactly = 0) { metrics.sanctionsHit("creditor", any()) }
    }

    @Test
    fun `screenPayment records nothing when screening is skipped for an own-accounts transfer`() {
        val payment = payment().copy(transferScope = DomesticTransferScope.OWN_ACCOUNTS)
        coEvery { paymentRepository.findById(payment.id) } returns payment

        activities.screenPayment(payment.id)

        verify(exactly = 0) { metrics.sanctionsScreening(any()) }
        verify(exactly = 0) { metrics.sanctionsHit(any(), any()) }
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

    // ─── TRANSACTION_FAILED notifications (#8432) ───────────────────────────────

    @Test
    fun `a scheme-rejected payment tells the account OWNER, with a safe reason`() {
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = false, reasonCode = "AM05")

        activitiesWithScheme.submitScheme(validated.id)

        coVerify {
            accountLookupPort.findPartyByAccountId(validated.debtorAccountId)
            customerNotificationPort.notifyPaymentFailed(
                ownerPartyId,
                validated.amount,
                validated.currency,
                customerSafeReason(DomesticRejectReason.INSUFFICIENT_FUNDS),
            )
        }
    }

    @Test
    fun `an accepted payment raises no failure notification`() {
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = true, reasonCode = null)

        activitiesWithScheme.submitScheme(validated.id)

        coVerify(exactly = 0) { customerNotificationPort.notifyPaymentFailed(any(), any(), any(), any()) }
    }

    /**
     * The load-bearing guard. `rejectPayment` is the sanctions-screening BLOCK path and always
     * records SANCTIONS_HIT; telling the customer their payment was stopped by a financial-crime
     * control is tipping-off. Whether they should get a neutral message instead is a compliance
     * decision (#8432), so this path stays silent until someone makes it.
     */
    @Test
    fun `the sanctions-screening reject path notifies nobody`() {
        val payment = payment()
        coEvery { paymentRepository.findById(payment.id) } returns payment

        activities.rejectPayment(payment.id)

        coVerify(exactly = 0) { customerNotificationPort.notifyPaymentFailed(any(), any(), any(), any()) }
    }

    @Test
    fun `an unresolvable account owner drops the notification instead of failing the activity`() {
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = false, reasonCode = "AC04")
        coEvery { accountLookupPort.findPartyByAccountId(any()) } returns null

        val result = activitiesWithScheme.submitScheme(validated.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.REJECTED)
        coVerify(exactly = 0) { customerNotificationPort.notifyPaymentFailed(any(), any(), any(), any()) }
    }

    /**
     * A notification that cannot be published must never fail the activity: Temporal would retry
     * it, and the retry would re-run bookkeeping for a verdict already recorded.
     */
    @Test
    fun `a notification failure does not disturb the rejection verdict`() {
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = false, reasonCode = "AC04")
        coEvery { customerNotificationPort.notifyPaymentFailed(any(), any(), any(), any()) } throws
            IllegalStateException("kafka down")

        val result = activitiesWithScheme.submitScheme(validated.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.REJECTED)
        coVerify { paymentRepository.update(match { it.status == DomesticPaymentStatus.REJECTED }, any()) }
    }

    @Test
    fun `submitScheme holds payment in VALIDATED when gateway is unavailable (fail-closed)`() {
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } throws
            SchemeGatewayUnavailableException(
                RuntimeException("connection refused"),
                requestLeftThisProcess = false,
            )

        val result = activitiesWithScheme.submitScheme(validated.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.VALIDATED)
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    // ---- #4218: a payment already dispatched to the scheme must never be dispatched twice ----

    @Test
    fun `submitScheme refuses to re-submit a VALIDATED payment that was already dispatched`() {
        // The #4218 shape exactly: a successful submit whose status write failed leaves the row
        // VALIDATED with the dispatch marker set. Before the guard this re-submitted, producing a
        // second clearing item for one payment.
        val stranded = payment(
            status = DomesticPaymentStatus.VALIDATED,
            schemeDispatchedAt = Instant.parse("2026-08-09T10:15:30Z"),
        )
        coEvery { paymentRepository.findById(stranded.id) } returns stranded
        // The claim is what refuses now, not a read of the row: `false` is the database saying the
        // dispatch is already held.
        coEvery { paymentRepository.claimSchemeDispatch(stranded.id, any()) } returns false

        val result = activitiesWithScheme.submitScheme(stranded.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.VALIDATED)
        coVerify(exactly = 0) { schemeGatewayPort.submit(any()) }
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
        coVerify(exactly = 0) { paymentRepository.clearSchemeDispatch(any()) }
    }

    @Test
    fun `submitScheme does not submit when a concurrent attempt won the dispatch claim`() {
        // The case a read-then-write guard cannot refuse: both attempts read a null marker, both
        // pass, both submit. Here the loser is told so by the claim's return value and stops before
        // the gateway — the row is untouched, and the winner owns the outcome.
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated
        coEvery { paymentRepository.claimSchemeDispatch(validated.id, any()) } returns false

        val result = activitiesWithScheme.submitScheme(validated.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.VALIDATED)
        coVerify(exactly = 0) { schemeGatewayPort.submit(any()) }
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
        coVerify(exactly = 0) { paymentRepository.clearSchemeDispatch(any()) }
    }

    @Test
    fun `submitScheme marks the dispatch BEFORE calling the gateway`() {
        // Ordering is the whole mechanism: a marker written after the call cannot survive a failure
        // of the call's own bookkeeping, which is the case the guard above exists for.
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = true, reasonCode = null)

        activitiesWithScheme.submitScheme(validated.id)

        coVerifyOrder {
            paymentRepository.claimSchemeDispatch(validated.id, any())
            schemeGatewayPort.submit(any())
            paymentRepository.update(any(), any())
        }
    }

    @Test
    fun `submitScheme clears the dispatch marker only when the request provably never left`() {
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } throws
            SchemeGatewayUnavailableException(
                java.net.ConnectException("Connection refused"),
                requestLeftThisProcess = false,
            )

        val result = activitiesWithScheme.submitScheme(validated.id)

        // Scheme is simply down: no clearing item exists, so the payment must stay re-drivable.
        assertThat(result).isEqualTo(DomesticPaymentStatus.VALIDATED)
        coVerify(exactly = 1) { paymentRepository.clearSchemeDispatch(validated.id) }
    }

    @Test
    fun `submitScheme keeps the dispatch marker when the failure is ambiguous`() {
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated
        // A timeout: the gateway may have accepted the pacs.008 and merely answered too late.
        coEvery { schemeGatewayPort.submit(any()) } throws
            SchemeGatewayUnavailableException(java.util.concurrent.TimeoutException("read timeout"))

        val result = activitiesWithScheme.submitScheme(validated.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.VALIDATED)
        // BOTH halves, deliberately. Asserting only "never released" passes against code that
        // never claims either — which is exactly what the pre-#4218 implementation did, so the
        // assertion held while proving nothing.
        coVerify(exactly = 1) { paymentRepository.claimSchemeDispatch(validated.id, any<Instant>()) }
        coVerify(exactly = 0) { paymentRepository.clearSchemeDispatch(any()) }
    }

    @Test
    fun `submitScheme lets a failure of the status write propagate instead of hiding it`() {
        // The defect was that this exception was caught and reported as "holding in VALIDATED",
        // which is what made a submitted payment look unsubmitted. It must now surface, so Temporal
        // retries the activity — and the marker written above makes that retry safe.
        val validated = payment(status = DomesticPaymentStatus.VALIDATED)
        coEvery { paymentRepository.findById(validated.id) } returns validated
        coEvery { schemeGatewayPort.submit(any()) } returns
            SchemeSubmissionOutcome(accepted = true, reasonCode = null)
        coEvery { paymentRepository.update(any(), any()) } throws RuntimeException("db blip")

        assertThatThrownBy { activitiesWithScheme.submitScheme(validated.id) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("db blip")

        coVerify(exactly = 1) { paymentRepository.claimSchemeDispatch(validated.id, any<Instant>()) }
        coVerify(exactly = 0) { paymentRepository.clearSchemeDispatch(any()) }
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

    // The two tests below replace a pair that asserted the defect (#4182). They read as coverage of
    // the outage path and were the reason it survived: both called settlePayment during a simulated
    // settlement outage and asserted it RETURNED SENT_TO_CLEARING — which is exactly the swallow
    // that made the activity a success, put the retry policy out of reach, and completed the
    // workflow on a non-terminal state. A green test asserting the bug is worse than no test.

    @Test
    fun `settlePayment FAILS the activity when settlement is unavailable so Temporal retries`() {
        val sentToClearing = payment(status = DomesticPaymentStatus.SENT_TO_CLEARING)
        coEvery { paymentRepository.findById(sentToClearing.id) } returns sentToClearing
        coEvery { settlementPort.settle(any()) } throws
            SettlementUnavailableException("transaction-service down")

        // Temporal drives the activity retry policy off activity FAILURE. An activity that returns
        // normally is a success, so this must throw or the retries are structurally unreachable.
        assertThatThrownBy { activitiesWithScheme.settlePayment(sentToClearing.id) }
            .isInstanceOf(SettlementUnavailableException::class.java)
            .hasMessageContaining("transaction-service down")

        // The durable record still says SENT_TO_CLEARING — no status is invented from a fault.
        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `settlePayment propagates an unexpected exception instead of absorbing it into a status`() {
        val sentToClearing = payment(status = DomesticPaymentStatus.SENT_TO_CLEARING)
        coEvery { paymentRepository.findById(sentToClearing.id) } returns sentToClearing
        coEvery { settlementPort.settle(any()) } throws RuntimeException("unexpected crash")

        // The blanket catch(Exception) is the actual defect: it made a settlement BUG
        // indistinguishable from a planned degradation, both reported as a terminal-looking
        // success. An unexpected fault must surface with its own type.
        assertThatThrownBy { activitiesWithScheme.settlePayment(sentToClearing.id) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("unexpected crash")

        coVerify(exactly = 0) { paymentRepository.update(any(), any()) }
    }

    @Test
    fun `settlePayment fails rather than reporting SETTLED when the status write fails`() {
        val sentToClearing = payment(status = DomesticPaymentStatus.SENT_TO_CLEARING)
        coEvery { paymentRepository.findById(sentToClearing.id) } returns sentToClearing
        coEvery { settlementPort.settle(any()) } returns
            SettlementOutcome(settled = true, transactionId = UUID.randomUUID())
        coEvery { paymentRepository.update(any(), any()) } throws RuntimeException("db write failed")

        // Booked but not recorded. Retrying is safe (payment-scoped idempotency key, 409 = already
        // booked — see SettlementAdapterTest), so failing the activity re-attempts the write rather
        // than leaving a booked payment behind a row that still says SENT_TO_CLEARING.
        assertThatThrownBy { activitiesWithScheme.settlePayment(sentToClearing.id) }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("db write failed")
    }

    @Test
    fun `settlePayment is re-entrant on an already SETTLED payment and does not book twice`() {
        // This is what makes retrying safe at the activity level: once the status write has landed,
        // a Temporal retry (or an operator re-drive) never reaches the settlement port again.
        val settled = payment(status = DomesticPaymentStatus.SETTLED)
        coEvery { paymentRepository.findById(settled.id) } returns settled

        val result = activitiesWithScheme.settlePayment(settled.id)

        assertThat(result).isEqualTo(DomesticPaymentStatus.SETTLED)
        coVerify(exactly = 0) { settlementPort.settle(any()) }
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

    private fun payment(
        status: DomesticPaymentStatus = DomesticPaymentStatus.RECEIVED,
        schemeDispatchedAt: Instant? = null,
    ) = DomesticPayment(
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
        schemeDispatchedAt = schemeDispatchedAt,
    )
}
