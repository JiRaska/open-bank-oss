// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.port.out.CollateralRepository
import com.openbank.lending.application.port.out.InstallmentRepository
import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.LedgerPostingPort
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LoanApplicationRepository
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.application.port.out.PostingKind
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.lending.domain.model.LoanTerminationPolicy
import com.openbank.lending.infrastructure.compliance.CompliancePackGuard
import com.openbank.lending.infrastructure.persistence.entity.SettlementQuoteEntity
import com.openbank.lending.infrastructure.persistence.repository.SettlementQuoteRepository
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.libs.lending.compliance.AprDisclosure
import com.openbank.libs.lending.compliance.CompiledCompliancePack
import com.openbank.libs.lending.compliance.CompliancePack
import com.openbank.libs.lending.compliance.CompliancePackCompiler
import com.openbank.libs.lending.compliance.PackProductType
import com.openbank.libs.lending.compliance.TerminationGround
import com.openbank.libs.lending.compliance.TerminationRules
import com.openbank.libs.lending.origination.OriginationState
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Covers ADR-0215: quote/settle, withdrawal, DPD gates, four-eyes termination, acceleration. */
class TerminationServiceTest {

    private val loans = mockk<LoanRepository>()
    private val applications = mockk<LoanApplicationRepository>()
    private val installments = mockk<InstallmentRepository>()
    private val collateral = mockk<CollateralRepository>()
    private val ledger = mockk<LedgerPostingPort>()
    private val events = mockk<LoanEventEmitter>()
    private val guard = mockk<CompliancePackGuard>()
    private val quotes = mockk<SettlementQuoteRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-08-10T10:00:00Z"), ZoneOffset.UTC)

    private val service = TerminationService(
        loans, applications, installments, collateral, ledger, events, guard, quotes, clock,
    )

    private val partyId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val applicationId = LoanApplicationId.random()

    private fun eur(v: String) = Money.of(v, "EUR")

    private fun loan(status: LoanStatus, disbursedAt: OffsetDateTime = OffsetDateTime.parse("2026-08-01T00:00:00Z")) =
        Loan(
            applicationId = applicationId,
            partyId = partyId,
            principal = eur("12000.00"),
            nominalAnnualRate = BigDecimal("0.12"),
            termPeriods = 12,
            method = AmortizationMethod.ANNUITY,
            firstDueDate = LocalDate.parse("2026-06-30"),
            status = status,
            disbursedAt = disbursedAt,
            createdAt = OffsetDateTime.parse("2026-08-01T00:00:00Z"),
        )

    private fun czPack(): CompiledCompliancePack = CompliancePackCompiler.compile(
        CompliancePack(
            jurisdiction = "CZ",
            productType = PackProductType.CONSUMER_CREDIT,
            version = 1,
            effectiveFrom = LocalDate.parse("2026-01-01"),
            coolingOffDays = 14,
            aprDisclosure = AprDisclosure("RPSN", "cs-CZ"),
            earlyRepaymentCompensationCap = BigDecimal("0.01"),
            terminationRules = TerminationRules(
                noticePeriodDays = 30,
                permittedGrounds = setOf(TerminationGround.DEFAULT_DPD, TerminationGround.MATERIAL_BREACH),
                defaultDpdThreshold = 90,
            ),
        ),
    )

    private fun stubPack() {
        every { applications.findById(any()) } returns Uni.createFrom().item(
            LoanApplication(
                partyId = partyId,
                requestedAmount = eur("12000.00"),
                nominalAnnualRate = BigDecimal("0.12"),
                termPeriods = 12,
                firstDueDate = LocalDate.parse("2026-06-30"),
                status = OriginationState.DISBURSED,
                proposedBy = "alice",
                createdAt = OffsetDateTime.parse("2026-08-01T00:00:00Z"),
                jurisdiction = "CZ",
                productType = "CONSUMER_CREDIT",
                packVersion = 1,
            ),
        )
        every { guard.resolveOriginationPack("CZ", "CONSUMER_CREDIT") } returns czPack()
    }

    @org.junit.jupiter.api.BeforeEach
    fun defaults() {
        every { events.emit(any<LendingOutboxMessage>()) } returns Uni.createFrom().item(Unit)
        every { loans.update(any()) } answers { Uni.createFrom().item(firstArg<Loan>()) }
        every { ledger.post(any()) } returns Uni.createFrom().item(Unit)
        every { collateral.findByLoan(any()) } returns Uni.createFrom().item(emptyList())
    }

    @Test
    fun `quote on an ACTIVE loan persists a binding quote and moves to SETTLEMENT_QUOTED`() {
        val loan = loan(LoanStatus.ACTIVE)
        stubPack()
        every { loans.findById(loan.id) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loan.id) } returns Uni.createFrom().item(emptyList())
        val quoteSlot = slot<SettlementQuoteEntity>()
        every { quotes.save(capture(quoteSlot)) } answers { Uni.createFrom().item(quoteSlot.captured) }

        val quote = service.requestSettlementQuote(loan.id, "officer-1").await().indefinitely()

        assertThat(quote.outstandingPrincipal).isEqualTo(eur("12000.00"))
        assertThat(quote.compensation).isEqualTo(eur("120.00"))
        assertThat(quote.validUntil).isEqualTo(LocalDate.parse("2026-09-09"))
        assertThat(quoteSlot.captured.total).isEqualByComparingTo(quote.total.amount)
    }

    @Test
    fun `settle against a valid quote posts principal and compensation and closes the loan`() {
        val loan = loan(LoanStatus.SETTLEMENT_QUOTED)
        val quoteEntity = SettlementQuoteEntity().apply {
            id = UUID.randomUUID()
            loanId = loan.id.value
            validUntil = LocalDate.parse("2026-09-09")
            outstandingPrincipal = BigDecimal("12000.00")
            accruedInterest = BigDecimal("0.00")
            compensation = BigDecimal("120.00")
            unappliedCredit = BigDecimal.ZERO
            total = BigDecimal("12120.00")
            currency = "EUR"
        }
        every { loans.findById(loan.id) } returns Uni.createFrom().item(loan)
        every { quotes.findLatestUnsettled(loan.id.value) } returns Uni.createFrom().item(quoteEntity)
        every { quotes.markSettled(any(), any()) } returns Uni.createFrom().item(1)
        val postingSlot = mutableListOf<LedgerPosting>()
        every { ledger.post(capture(postingSlot)) } returns Uni.createFrom().item(Unit)
        stubPack()

        val closed = service.settle(loan.id, "officer-2").await().indefinitely()

        assertThat(closed.status).isEqualTo(LoanStatus.CLOSED)
        assertThat(postingSlot.map { it.kind }).containsExactly(
            PostingKind.SETTLEMENT,
            PostingKind.EARLY_REPAYMENT_COMPENSATION,
        )
        assertThat(postingSlot[0].amount).isEqualTo(eur("12000.00"))
        assertThat(postingSlot[1].amount).isEqualTo(eur("120.00"))
    }

    @Test
    fun `settle against an expired quote is refused fail-closed`() {
        val loan = loan(LoanStatus.SETTLEMENT_QUOTED)
        val expired = SettlementQuoteEntity().apply {
            id = UUID.randomUUID()
            loanId = loan.id.value
            validUntil = LocalDate.parse("2026-08-01")
            currency = "EUR"
        }
        every { loans.findById(loan.id) } returns Uni.createFrom().item(loan)
        every { quotes.findLatestUnsettled(loan.id.value) } returns Uni.createFrom().item(expired)

        assertThatThrownBy { service.settle(loan.id, "officer-2").await().indefinitely() }
            .hasMessageContaining("expired")
    }

    @Test
    fun `withdrawal inside cooling-off unwinds with the day interest`() {
        val loan = loan(LoanStatus.ACTIVE)
        stubPack()
        every { loans.findById(loan.id) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loan.id) } returns Uni.createFrom().item(emptyList())
        val postingSlot = mutableListOf<LedgerPosting>()
        every { ledger.post(capture(postingSlot)) } returns Uni.createFrom().item(Unit)

        val unwound = service.withdraw(loan.id, "customer-1").await().indefinitely()

        assertThat(unwound.status).isEqualTo(LoanStatus.UNWOUND)
        assertThat(postingSlot.map { it.kind }).contains(PostingKind.WITHDRAWAL_UNWIND)
        assertThat(postingSlot.first().amount).isEqualTo(eur("12000.00"))
    }

    @Test
    fun `withdrawal after the cooling-off window is refused`() {
        val loan = loan(LoanStatus.ACTIVE, disbursedAt = OffsetDateTime.parse("2026-07-01T00:00:00Z"))
        stubPack()
        every { loans.findById(loan.id) } returns Uni.createFrom().item(loan)

        assertThatThrownBy { service.withdraw(loan.id, "customer-1").await().indefinitely() }
            .hasMessageContaining("cooling-off")
    }

    @Test
    fun `default below the DPD threshold is refused`() {
        val loan = loan(LoanStatus.DELINQUENT)
        stubPack()
        every { loans.findById(loan.id) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loan.id) } returns Uni.createFrom().item(emptyList())

        assertThatThrownBy { service.markDefaulted(loan.id, "risk-1").await().indefinitely() }
            .hasMessageContaining("CRR")
    }

    @Test
    fun `termination checker must differ from the maker`() {
        val loan = loan(LoanStatus.FORBEARANCE_ASSESSED).copy(terminatedBy = "risk-maker")
        every { loans.findById(loan.id) } returns Uni.createFrom().item(loan)

        assertThatThrownBy { service.decideTermination(loan.id, true, "risk-maker").await().indefinitely() }
            .hasMessageContaining("four-eyes")
    }

    @Test
    fun `decided termination carries the pack notice period`() {
        val loan = loan(LoanStatus.FORBEARANCE_ASSESSED).copy(terminatedBy = "risk-maker")
        stubPack()
        every { loans.findById(loan.id) } returns Uni.createFrom().item(loan)
        val slot = slot<Loan>()
        every { loans.update(capture(slot)) } answers { Uni.createFrom().item(slot.captured) }

        val noticed = service.decideTermination(loan.id, true, "compliance-1").await().indefinitely()

        assertThat(noticed.status).isEqualTo(LoanStatus.TERMINATION_NOTICED)
        assertThat(noticed.noticeEndsOn).isEqualTo(LocalDate.parse("2026-09-09"))
    }

    @Test
    fun `acceleration before the notice elapses is refused`() {
        val loan = loan(LoanStatus.TERMINATION_NOTICED).copy(noticeEndsOn = LocalDate.parse("2026-09-09"))
        every { loans.findById(loan.id) } returns Uni.createFrom().item(loan)

        assertThatThrownBy { service.accelerate(loan.id, "risk-1").await().indefinitely() }
            .hasMessageContaining("notice period")
    }

    // --- #3994/#5256: audit attribution on the two shared-helper event types -------------------
    //
    // `loan.withdrawn` and `loan.accelerated` are the only event types in this module built by a
    // shared, parameterised helper (TerminationService.emitDomainEvent) rather than by a per-event
    // payload builder, which is why the #5399 sweep patched their nine siblings and missed them.
    // Both reach `openbank.lending.events`, which audit-service consumes: without `sourceService`
    // AuditConsumer falls through to its topic table and records the row as TOPIC-attributed rather
    // than as the producer's own claim — a silent, successful default.
    //
    // The assertion reads the emitted payload the PRODUCTION code builds, parsed as JSON, so it
    // cannot pass on a key that is merely present as a substring somewhere else in the string.

    @Test
    fun `loan-withdrawn self-reports lending as its source service`() {
        val loan = loan(LoanStatus.ACTIVE)
        stubPack()
        every { loans.findById(loan.id) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loan.id) } returns Uni.createFrom().item(emptyList())
        val emitted = mutableListOf<LendingOutboxMessage>()
        every { events.emit(capture(emitted)) } returns Uni.createFrom().item(Unit)

        service.withdraw(loan.id, "customer-1").await().indefinitely()

        val withdrawn = emitted.single { it.eventType == "loan.withdrawn" }
        val payload = ObjectMapper().readTree(withdrawn.payload)
        assertThat(payload.get("sourceService")?.asText()).isEqualTo("lending")
        assertThat(payload.get("eventType")?.asText()).isEqualTo("loan.withdrawn")
    }

    @Test
    fun `loan-accelerated self-reports lending as its source service`() {
        val loan = loan(LoanStatus.TERMINATION_NOTICED).copy(noticeEndsOn = LocalDate.parse("2026-01-01"))
        stubPack()
        every { loans.findById(loan.id) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loan.id) } returns Uni.createFrom().item(emptyList())
        val emitted = mutableListOf<LendingOutboxMessage>()
        every { events.emit(capture(emitted)) } returns Uni.createFrom().item(Unit)

        service.accelerate(loan.id, "risk-1").await().indefinitely()

        val accelerated = emitted.single { it.eventType == "loan.accelerated" }
        val payload = ObjectMapper().readTree(accelerated.payload)
        assertThat(payload.get("sourceService")?.asText()).isEqualTo("lending")
        assertThat(payload.get("eventType")?.asText()).isEqualTo("loan.accelerated")
    }

    @Test
    fun `transition policy refuses exits from terminal states`() {
        assertThatThrownBy {
            LoanTerminationPolicy.requireAllowed(LoanStatus.UNWOUND, LoanStatus.ACTIVE)
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(LoanTerminationPolicy.isAllowed(LoanStatus.ACTIVE, LoanStatus.WITHDRAWN)).isTrue()
        assertThat(LoanTerminationPolicy.isAllowed(LoanStatus.SETTLEMENT_QUOTED, LoanStatus.CLOSED)).isFalse()
    }
}
