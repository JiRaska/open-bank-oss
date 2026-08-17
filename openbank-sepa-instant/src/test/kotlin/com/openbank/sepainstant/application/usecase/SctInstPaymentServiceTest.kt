// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.application.usecase

import com.openbank.libs.observability.DomainMetrics
import com.openbank.sepainstant.application.port.`in`.SubmitSctInstCommand
import com.openbank.sepainstant.application.port.out.AmlCasePort
import com.openbank.sepainstant.application.port.out.AmlCaseRiskLevel
import com.openbank.sepainstant.application.port.out.FraudScoreOutcome
import com.openbank.sepainstant.application.port.out.FraudScoringPort
import com.openbank.sepainstant.application.port.out.FraudVerdict
import com.openbank.sepainstant.application.port.out.OpenAmlCaseCommand
import com.openbank.sepainstant.application.port.out.SanctionsScreeningPort
import com.openbank.sepainstant.application.port.out.SchemeGatewayPort
import com.openbank.sepainstant.application.port.out.SchemeGatewayUnavailableException
import com.openbank.sepainstant.application.port.out.SchemeSubmissionOutcome
import com.openbank.sepainstant.application.port.out.ScreeningUnavailableException
import com.openbank.sepainstant.application.port.out.SctInstEventPublisher
import com.openbank.sepainstant.application.port.out.SctInstPaymentRepository
import com.openbank.sepainstant.application.port.out.SettlementOutcome
import com.openbank.sepainstant.application.port.out.SettlementPort
import com.openbank.sepainstant.application.port.out.SettlementUnavailableException
import com.openbank.sepainstant.domain.event.SctInstPaymentRecalled
import com.openbank.sepainstant.domain.event.SctInstPaymentRejected
import com.openbank.sepainstant.domain.event.SctInstPaymentSubmitted
import com.openbank.sepainstant.domain.model.SctInstPayment
import com.openbank.sepainstant.domain.model.SctInstStatus
import com.openbank.sepainstant.domain.screening.ScreeningMatchStatus
import com.openbank.sepainstant.domain.screening.ScreeningResult
import com.openbank.sepainstant.domain.screening.ScreeningRole
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.BadRequestException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class SctInstPaymentServiceTest {

    private val fixedInstant = Instant.parse("2026-01-01T12:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    private val repo = mockk<SctInstPaymentRepository>()
    private val publisher = mockk<SctInstEventPublisher>()
    private val screeningPort = mockk<SanctionsScreeningPort>()
    private val amlCasePort = mockk<AmlCasePort>()
    private val fraudScoringPort = mockk<FraudScoringPort>()
    private val schemeGatewayPort = mockk<SchemeGatewayPort>()
    private val settlementPort = mockk<SettlementPort>()
    private val metrics = mockk<DomainMetrics>(relaxed = true)
    private val service = buildService(schemeSubmissionEnabled = false)

    private fun buildService(schemeSubmissionEnabled: Boolean) = SctInstPaymentService(
        repo,
        publisher,
        screeningPort,
        amlCasePort,
        fraudScoringPort,
        schemeGatewayPort,
        settlementPort,
        metrics,
        timeoutSeconds = 10L,
        schemeSubmissionEnabled = schemeSubmissionEnabled,
        clock = clock,
    )

    @BeforeEach
    fun setUp() {
        // Defaults reused by most tests: repo echoes the saved aggregate, publisher and AML case succeed.
        every { repo.save(any()) } answers { Uni.createFrom().item(firstArg<SctInstPayment>()) }
        every { publisher.publish(any()) } returns Uni.createFrom().nullItem()
        every { amlCasePort.openCase(any()) } returns Uni.createFrom().voidItem()
        // Fraud scoring is SHADOW (ADR-0084): default to ALLOW; never affects the payment outcome.
        every { fraudScoringPort.score(any()) } returns
            Uni.createFrom().item(FraudScoreOutcome(FraudVerdict.ALLOW, 0, "v0", emptyList()))
    }

    private fun clearScreening() {
        every { screeningPort.screen(any(), ScreeningRole.DEBTOR, any()) } returns
            Uni.createFrom().item(
                ScreeningResult("Alice Debtor", ScreeningRole.DEBTOR, ScreeningMatchStatus.CLEAR, 0.0, null),
            )
        every { screeningPort.screen(any(), ScreeningRole.CREDITOR, any()) } returns
            Uni.createFrom().item(
                ScreeningResult("Bob Creditor", ScreeningRole.CREDITOR, ScreeningMatchStatus.CLEAR, 0.0, null),
            )
    }

    @Test
    fun `submit returns existing payment for same idempotency key`() {
        val existing = payment(status = SctInstStatus.PROCESSING)
        val command = command()

        every { repo.findByIdempotencyKey(command.idempotencyKey) } returns Uni.createFrom().item(existing)

        val result = service.submit(command).await().indefinitely()

        assertThat(result).isSameAs(existing)
        verify(exactly = 1) { repo.findByIdempotencyKey(command.idempotencyKey) }
        verify(exactly = 0) { repo.save(any()) }
        verify(exactly = 0) { publisher.publish(any()) }
        verify(exactly = 0) { screeningPort.screen(any(), any(), any()) }
        // SHADOW: idempotent replay skips fraud scoring (payment was already scored on first submit).
        verify(exactly = 0) { fraudScoringPort.score(any()) }
    }

    @Test
    fun `fraud shadow verdict is observed but never enforced`() {
        val command = command(idempotencyKey = "idem-shadow")
        every { repo.findByIdempotencyKey(command.idempotencyKey) } returns Uni.createFrom().nullItem()
        clearScreening()
        // A blocking-looking verdict in SHADOW must NOT change the payment outcome.
        every { fraudScoringPort.score(any()) } returns
            Uni.createFrom().item(FraudScoreOutcome(FraudVerdict.DECLINE, 99, "v0", listOf("velocity-cap")))

        val result = service.submit(command).await().indefinitely()

        // Still PROCESSING — shadow scoring is observe-only.
        assertThat(result.status).isEqualTo(SctInstStatus.PROCESSING)
        verify(exactly = 1) { fraudScoringPort.score(any()) }
    }

    @Test
    fun `D4 and ADR-0108 scheme ACSC settles the instant payment`() {
        val command = command(idempotencyKey = "idem-scheme-acsc")
        every { repo.findByIdempotencyKey(command.idempotencyKey) } returns Uni.createFrom().nullItem()
        clearScreening()
        every { schemeGatewayPort.submit(any()) } returns
            Uni.createFrom().item(SchemeSubmissionOutcome(accepted = true, reasonCode = null))
        every { settlementPort.settle(any()) } returns
            Uni.createFrom().item(SettlementOutcome(settled = true, transactionId = null))

        val result = buildService(schemeSubmissionEnabled = true).submit(command).await().indefinitely()

        assertThat(result.status).isEqualTo(SctInstStatus.SETTLED)
        verify(exactly = 1) { schemeGatewayPort.submit(any()) }
        verify(exactly = 1) { settlementPort.settle(any()) }
        // Issue #5049 follow-up: paymentCompleted() was already wired (correctly, per PR #5068's
        // investigation) but paymentProcessingDuration() had no call site anywhere in this class —
        // sepa-payment, domestic-payment and fx-service all record it at their terminal
        // transitions, sepa-instant never did.
        verify(exactly = 1) { metrics.paymentCompleted("sepa_instant", result.currency, "settled") }
        verify(exactly = 1) { metrics.paymentProcessingDuration("sepa_instant", "settled", any()) }
        verify(exactly = 0) { metrics.paymentCompleted("sepa_instant", result.currency, "rejected") }
    }

    @Test
    fun `ADR-0108 settlement unavailable leaves payment in PROCESSING`() {
        val command = command(idempotencyKey = "idem-scheme-acsc-settle-fail")
        every { repo.findByIdempotencyKey(command.idempotencyKey) } returns Uni.createFrom().nullItem()
        clearScreening()
        every { schemeGatewayPort.submit(any()) } returns
            Uni.createFrom().item(SchemeSubmissionOutcome(accepted = true, reasonCode = null))
        every { settlementPort.settle(any()) } returns
            Uni.createFrom().failure(SettlementUnavailableException("tx-service down"))

        val result = buildService(schemeSubmissionEnabled = true).submit(command).await().indefinitely()

        assertThat(result.status).isEqualTo(SctInstStatus.PROCESSING)
    }

    @Test
    fun `D4 scheme RJCT rejects the instant payment with the scheme reason`() {
        val command = command(idempotencyKey = "idem-scheme-rjct")
        every { repo.findByIdempotencyKey(command.idempotencyKey) } returns Uni.createFrom().nullItem()
        clearScreening()
        every { schemeGatewayPort.submit(any()) } returns
            Uni.createFrom().item(SchemeSubmissionOutcome(accepted = false, reasonCode = "AC04"))

        val result = buildService(schemeSubmissionEnabled = true).submit(command).await().indefinitely()

        assertThat(result.status).isEqualTo(SctInstStatus.REJECTED)
        assertThat(result.rejectReason).isEqualTo("AC04")
        verify(exactly = 1) { metrics.paymentCompleted("sepa_instant", result.currency, "rejected") }
        verify(exactly = 1) { metrics.paymentProcessingDuration("sepa_instant", "rejected", any()) }
        verify(exactly = 0) { metrics.paymentCompleted("sepa_instant", result.currency, "settled") }
    }

    @Test
    fun `D4 scheme gateway outage holds the payment PENDING (fail-closed)`() {
        val command = command(idempotencyKey = "idem-scheme-down")
        every { repo.findByIdempotencyKey(command.idempotencyKey) } returns Uni.createFrom().nullItem()
        clearScreening()
        every { schemeGatewayPort.submit(any()) } returns
            Uni.createFrom().failure(SchemeGatewayUnavailableException(RuntimeException("down")))

        val result = buildService(schemeSubmissionEnabled = true).submit(command).await().indefinitely()

        assertThat(result.status).isEqualTo(SctInstStatus.PENDING)
    }

    @Test
    fun `clean screening sets status to PROCESSING and publishes Submitted`() {
        val command = command(idempotencyKey = "idem-new")
        val paymentSlot = slot<SctInstPayment>()

        every { repo.findByIdempotencyKey(command.idempotencyKey) } returns Uni.createFrom().nullItem()
        every { repo.save(capture(paymentSlot)) } answers { Uni.createFrom().item(paymentSlot.captured) }
        clearScreening()

        val result = service.submit(command).await().indefinitely()

        assertThat(paymentSlot.captured.status).isEqualTo(SctInstStatus.PROCESSING)
        assertThat(paymentSlot.captured.idempotencyKey).isEqualTo(command.idempotencyKey)
        assertThat(paymentSlot.captured.debtorName).isEqualTo(command.debtorName)
        assertThat(paymentSlot.captured.creditorName).isEqualTo(command.creditorName)
        assertThat(paymentSlot.captured.creditorBic).isEqualTo(command.creditorBic)
        assertThat(paymentSlot.captured.amount).isEqualByComparingTo(command.amount)
        assertThat(paymentSlot.captured.currency).isEqualTo(command.currency)
        assertThat(paymentSlot.captured.endToEndId).isEqualTo(command.endToEndId)
        val executionTimeoutAt = requireNotNull(paymentSlot.captured.executionTimeoutAt)
        val submittedAt = requireNotNull(paymentSlot.captured.submittedAt)
        assertThat(executionTimeoutAt).isAfter(submittedAt)
        assertThat(result).isSameAs(paymentSlot.captured)

        verify(exactly = 1) { repo.save(any()) }
        verify(exactly = 1) { publisher.publish(match<SctInstPaymentSubmitted> { it.paymentId == result.paymentId }) }
        verify(exactly = 0) { amlCasePort.openCase(any()) }
    }

    @Test
    fun `sanctioned creditor rejects the payment and opens a CRITICAL aml case`() {
        val command = command(idempotencyKey = "idem-hit")
        val paymentSlot = slot<SctInstPayment>()

        every { repo.findByIdempotencyKey(command.idempotencyKey) } returns Uni.createFrom().nullItem()
        every { repo.save(capture(paymentSlot)) } answers { Uni.createFrom().item(paymentSlot.captured) }
        every { screeningPort.screen(any(), ScreeningRole.DEBTOR, any()) } returns
            Uni.createFrom().item(
                ScreeningResult("Alice Debtor", ScreeningRole.DEBTOR, ScreeningMatchStatus.CLEAR, 0.0, null),
            )
        every { screeningPort.screen(any(), ScreeningRole.CREDITOR, any()) } returns
            Uni.createFrom().item(
                ScreeningResult(
                    "Bob Creditor",
                    ScreeningRole.CREDITOR,
                    ScreeningMatchStatus.HIT,
                    0.97,
                    "BOB CREDITOR / OFAC",
                ),
            )

        val result = service.submit(command).await().indefinitely()

        assertThat(result.status).isEqualTo(SctInstStatus.REJECTED)
        assertThat(result.rejectReason).isEqualTo("SANCTIONS_HIT")
        verify {
            amlCasePort.openCase(
                match<OpenAmlCaseCommand> {
                    it.riskLevel == AmlCaseRiskLevel.CRITICAL &&
                        it.alertCode == "SANCTIONS_HIT" &&
                        it.matchedEntity == "BOB CREDITOR / OFAC"
                },
            )
        }
        verify(exactly = 1) { publisher.publish(match<SctInstPaymentRejected> { it.paymentId == result.paymentId }) }
        verify(exactly = 0) { publisher.publish(match<SctInstPaymentSubmitted> { true }) }
        verify(exactly = 1) { metrics.paymentCompleted("sepa_instant", result.currency, "rejected") }
        verify(exactly = 1) { metrics.paymentProcessingDuration("sepa_instant", "rejected", any()) }
    }

    @Test
    fun `sub-threshold potential hit holds the payment in PENDING and opens a HIGH aml case`() {
        val command = command(idempotencyKey = "idem-review")

        every { repo.findByIdempotencyKey(command.idempotencyKey) } returns Uni.createFrom().nullItem()
        every { screeningPort.screen(any(), ScreeningRole.DEBTOR, any()) } returns
            Uni.createFrom().item(
                ScreeningResult("Alice Debtor", ScreeningRole.DEBTOR, ScreeningMatchStatus.CLEAR, 0.0, null),
            )
        every { screeningPort.screen(any(), ScreeningRole.CREDITOR, any()) } returns
            Uni.createFrom().item(
                ScreeningResult(
                    "Bob Creditor",
                    ScreeningRole.CREDITOR,
                    ScreeningMatchStatus.POTENTIAL_HIT,
                    0.50,
                    "B. CREDITOR",
                ),
            )

        val result = service.submit(command).await().indefinitely()

        assertThat(result.status).isEqualTo(SctInstStatus.PENDING)
        verify {
            amlCasePort.openCase(
                match<OpenAmlCaseCommand> {
                    it.riskLevel == AmlCaseRiskLevel.HIGH && it.alertCode == "AML_HOLD"
                },
            )
        }
        verify(exactly = 0) { publisher.publish(any()) }
        // PENDING is not terminal — neither completion metric should fire.
        verify(exactly = 0) { metrics.paymentCompleted(any(), any(), any()) }
        verify(exactly = 0) { metrics.paymentProcessingDuration(any(), any(), any()) }
    }

    @Test
    fun `screening unavailable holds the payment in PENDING fail-closed and opens a MEDIUM aml case`() {
        val command = command(idempotencyKey = "idem-down")

        every { repo.findByIdempotencyKey(command.idempotencyKey) } returns Uni.createFrom().nullItem()
        every { screeningPort.screen(any(), any(), any()) } returns
            Uni.createFrom().failure(ScreeningUnavailableException(RuntimeException("down")))

        val result = service.submit(command).await().indefinitely()

        assertThat(result.status).isEqualTo(SctInstStatus.PENDING)
        verify {
            amlCasePort.openCase(
                match<OpenAmlCaseCommand> {
                    it.riskLevel == AmlCaseRiskLevel.MEDIUM && it.alertCode == "SCREENING_UNAVAILABLE"
                },
            )
        }
        verify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `recall fails for non settled payment`() {
        val paymentId = UUID.fromString("33333333-3333-3333-3333-333333333333")

        every { repo.findByPaymentId(paymentId) } returns
            Uni.createFrom().item(payment(status = SctInstStatus.PROCESSING, paymentId = paymentId))

        assertThatThrownBy {
            service.recall(paymentId, "Too late").await().indefinitely()
        }.isInstanceOf(BadRequestException::class.java)
            .hasMessageContaining("Only SETTLED payments can be recalled")

        verify(exactly = 0) { repo.updateStatus(any(), any()) }
        verify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `recall works only for settled payments`() {
        val paymentId = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val settled = payment(status = SctInstStatus.SETTLED, paymentId = paymentId)

        every { repo.findByPaymentId(paymentId) } returns Uni.createFrom().item(settled)
        every { repo.updateStatus(paymentId, SctInstStatus.RECALLED) } returns Uni.createFrom().item(1)
        every {
            publisher.publish(
                match<SctInstPaymentRecalled> {
                    it.paymentId == paymentId && it.recallReason == "Customer requested"
                },
            )
        } returns Uni.createFrom().nullItem()

        val result = service.recall(paymentId, "Customer requested").await().indefinitely()

        assertThat(result.paymentId).isEqualTo(paymentId)
        assertThat(result.status).isEqualTo(SctInstStatus.RECALLED)
        assertThat(result.recalledAt).isNotNull
        assertThat(result.recallReason).isEqualTo("Customer requested")

        verify(exactly = 1) { repo.findByPaymentId(paymentId) }
        verify(exactly = 1) { repo.updateStatus(paymentId, SctInstStatus.RECALLED) }
        verify(exactly = 1) { publisher.publish(any()) }
    }

    private fun command(
        idempotencyKey: String = "idem-123",
        debtorAccountId: UUID = UUID.fromString("55555555-5555-5555-5555-555555555555"),
        debtorIban: String = "DE89370400440532013000",
        debtorName: String = "Alice Debtor",
        creditorIban: String = "FR7630006000011234567890189",
        creditorName: String = "Bob Creditor",
        creditorBic: String? = "AGRIFRPP",
        amount: BigDecimal = BigDecimal("123.45"),
        currency: String = "EUR",
        remittanceInfo: String? = "Invoice 42",
        endToEndId: String = "E2E-123",
    ) = SubmitSctInstCommand(
        idempotencyKey = idempotencyKey,
        debtorAccountId = debtorAccountId,
        debtorIban = debtorIban,
        debtorName = debtorName,
        creditorIban = creditorIban,
        creditorName = creditorName,
        creditorBic = creditorBic,
        amount = amount,
        currency = currency,
        remittanceInfo = remittanceInfo,
        endToEndId = endToEndId,
    )

    private fun payment(
        status: SctInstStatus,
        paymentId: UUID = UUID.fromString("66666666-6666-6666-6666-666666666666"),
    ) = SctInstPayment(
        id = 1,
        paymentId = paymentId,
        idempotencyKey = "idem-123",
        status = status,
        debtorAccountId = UUID.fromString("55555555-5555-5555-5555-555555555555"),
        debtorIban = "DE89370400440532013000",
        debtorName = "Alice Debtor",
        creditorIban = "FR7630006000011234567890189",
        creditorName = "Bob Creditor",
        creditorBic = "AGRIFRPP",
        amount = BigDecimal("123.45"),
        currency = "EUR",
        remittanceInfo = "Invoice 42",
        endToEndId = "E2E-123",
        executionTimeoutAt = OffsetDateTime.parse("2026-01-01T10:15:30Z"),
        settledAt = if (status == SctInstStatus.SETTLED) OffsetDateTime.parse("2026-01-01T10:16:30Z") else null,
        recalledAt = null,
        recallReason = null,
        rejectReason = null,
        rejectDetail = null,
        submittedAt = OffsetDateTime.parse("2026-01-01T10:15:00Z"),
        createdAt = OffsetDateTime.parse("2026-01-01T10:14:00Z"),
        updatedAt = OffsetDateTime.parse("2026-01-01T10:15:30Z"),
    )
}
