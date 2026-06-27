// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.CollateralRepository
import com.openbank.lending.application.port.out.CollateralValuationPort
import com.openbank.lending.application.port.out.InstallmentRepository
import com.openbank.lending.application.port.out.LedgerPosting
import com.openbank.lending.application.port.out.LedgerPostingPort
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LoanApplicationRepository
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.application.port.out.PostingKind
import com.openbank.lending.application.port.out.RiskParameterSource
import com.openbank.lending.domain.model.ApplicationStatus
import com.openbank.lending.domain.model.DecisionRequest
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanApplicationRequest
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.lending.domain.model.WriteOffRequest
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.libs.lending.EclInputs
import com.openbank.libs.lending.Ifrs9Stage
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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

class LendingServiceTest {

    private val applications = mockk<LoanApplicationRepository>()
    private val loans = mockk<LoanRepository>()
    private val installments = mockk<InstallmentRepository>()
    private val collateral = mockk<CollateralRepository>()
    private val ledger = mockk<LedgerPostingPort>()
    private val valuation = mockk<CollateralValuationPort>()
    private val riskParameters = mockk<RiskParameterSource>()
    private val events = mockk<LoanEventEmitter>()
    private val clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)

    private val service = LendingService(
        applications,
        loans,
        installments,
        collateral,
        ledger,
        valuation,
        riskParameters,
        events,
        clock,
    )

    private val partyId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val firstDue = LocalDate.parse("2026-06-30")

    private fun eur(v: String) = Money.of(v, "EUR")

    private fun sampleRequest() = LoanApplicationRequest(
        partyId = partyId,
        requestedAmount = eur("12000.00"),
        nominalAnnualRate = BigDecimal("0.12"),
        termPeriods = 12,
        firstDueDate = firstDue,
    )

    private val fixedNow = OffsetDateTime.ofInstant(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)

    private fun proposedApplication(proposer: String = "alice") = LoanApplication(
        partyId = partyId,
        requestedAmount = eur("12000.00"),
        nominalAnnualRate = BigDecimal("0.12"),
        termPeriods = 12,
        firstDueDate = firstDue,
        proposedBy = proposer,
        status = ApplicationStatus.PROPOSED,
        createdAt = fixedNow,
    )

    @Test
    fun `apply saves a PROPOSED application`() {
        val slot: CapturingSlot<LoanApplication> = slot()
        every { applications.save(capture(slot)) } answers { Uni.createFrom().item(slot.captured) }

        val result = service.apply(sampleRequest(), "alice").await().indefinitely()

        assertThat(result.status).isEqualTo(ApplicationStatus.PROPOSED)
        assertThat(result.proposedBy).isEqualTo("alice")
        assertThat(result.decidedBy).isNull()
        verify(exactly = 1) { applications.save(any()) }
    }

    @Test
    fun `decide rejects a four-eyes violation when approver equals proposer`() {
        val app = proposedApplication(proposer = "alice")
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)

        assertThatThrownBy {
            service.decide(app.id, DecisionRequest(approve = true), "alice").await().indefinitely()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Four-eyes")

        verify(exactly = 0) { applications.update(any()) }
    }

    @Test
    fun `decide approves when a different officer signs off`() {
        val app = proposedApplication(proposer = "alice")
        val slot: CapturingSlot<LoanApplication> = slot()
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        every { applications.update(capture(slot)) } answers { Uni.createFrom().item(slot.captured) }

        val result = service.decide(app.id, DecisionRequest(approve = true), "bob").await().indefinitely()

        assertThat(result.status).isEqualTo(ApplicationStatus.APPROVED)
        assertThat(result.decidedBy).isEqualTo("bob")
        verify(exactly = 1) { applications.update(any()) }
    }

    @Test
    fun `disburse books the loan, persists a 12-row schedule and posts the disbursement`() {
        val app = proposedApplication().copy(status = ApplicationStatus.APPROVED, decidedBy = "bob")
        val rowsSlot: CapturingSlot<List<LoanInstallment>> = slot()
        val postingSlot: CapturingSlot<LedgerPosting> = slot()

        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        every { loans.save(any()) } answers { Uni.createFrom().item(firstArg<Loan>()) }
        every { installments.saveAll(capture(rowsSlot)) } answers { Uni.createFrom().item(rowsSlot.captured) }
        every { applications.update(any()) } answers { Uni.createFrom().item(firstArg<LoanApplication>()) }
        every { ledger.post(capture(postingSlot)) } returns Uni.createFrom().item(Unit)
        every { events.emit(any<LendingOutboxMessage>()) } returns Uni.createFrom().item(Unit)

        val loan = service.disburse(app.id, "dave").await().indefinitely()

        assertThat(loan.principal).isEqualTo(eur("12000.00"))
        assertThat(loan.method).isEqualTo(AmortizationMethod.ANNUITY)
        // The whole contractual schedule is persisted and closes to zero.
        assertThat(rowsSlot.captured).hasSize(12)
        assertThat(rowsSlot.captured.last().closingBalance).isEqualTo(eur("0.00"))
        // Cash leaves the bank exactly once, for the full principal.
        assertThat(postingSlot.captured.amount).isEqualTo(eur("12000.00"))
        verify(exactly = 1) { ledger.post(any()) }
        verify(exactly = 1) { events.emit(any<LendingOutboxMessage>()) }
    }

    @Test
    fun `disburse refuses an application that is not APPROVED`() {
        val app = proposedApplication() // still PROPOSED
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)

        assertThatThrownBy { service.disburse(app.id, "dave").await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("APPROVED")

        verify(exactly = 0) { loans.save(any()) }
    }

    @Test
    fun `disburse refuses when the disburser is the approver (segregation of duties)`() {
        val app = proposedApplication().copy(status = ApplicationStatus.APPROVED, decidedBy = "bob")
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)

        assertThatThrownBy { service.disburse(app.id, "bob").await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Segregation of duties")

        verify(exactly = 0) { loans.save(any()) }
    }

    // --- Servicing posting loop: interest accrual ---------------------------------------------------

    @Test
    fun `accrual posts an INTEREST_ACCRUAL per due installment and flags each as accrued`() {
        val loanId = LoanId.random()
        val due = listOf(
            LoanInstallment(
                loanId = loanId,
                number = 1,
                dueDate = firstDue,
                openingBalance = eur("12000.00"),
                principal = eur("946.19"),
                interest = eur("120.00"),
                payment = eur("1066.19"),
                closingBalance = eur("11053.81"),
            ),
            LoanInstallment(
                loanId = loanId,
                number = 2,
                dueDate = firstDue.plusMonths(1),
                openingBalance = eur("11053.81"),
                principal = eur("955.65"),
                interest = eur("110.54"),
                payment = eur("1066.19"),
                closingBalance = eur("10098.16"),
            ),
        )
        val postings = mutableListOf<LedgerPosting>()
        every { installments.findAccruable(any(), any()) } returns Uni.createFrom().item(due)
        every { installments.markAccrued(any(), any()) } returns Uni.createFrom().item(1)
        every { ledger.post(capture(postings)) } returns Uni.createFrom().item(Unit)
        every { events.emit(any<LendingOutboxMessage>()) } returns Uni.createFrom().item(Unit)

        val outcome = service.accrueDueInterest(LocalDate.parse("2026-08-01"), 500).await().indefinitely()

        assertThat(outcome.installmentsAccrued).isEqualTo(2)
        // Income is recognized at due date as an accrual (no cash leg), for each installment's interest.
        assertThat(postings.map { it.kind }.toSet()).containsExactly(PostingKind.INTEREST_ACCRUAL)
        assertThat(postings.map { it.amount }).containsExactly(eur("120.00"), eur("110.54"))
        verify(exactly = 2) { installments.markAccrued(any(), any()) }
        verify(exactly = 2) { events.emit(any<LendingOutboxMessage>()) }
    }

    @Test
    fun `accrual flags a zero-interest installment without posting to the ledger`() {
        val loanId = LoanId.random()
        val due = listOf(
            LoanInstallment(
                loanId = loanId,
                number = 1,
                dueDate = firstDue,
                openingBalance = eur("12000.00"),
                principal = eur("12000.00"),
                interest = eur("0.00"),
                payment = eur("12000.00"),
                closingBalance = eur("0.00"),
            ),
        )
        every { installments.findAccruable(any(), any()) } returns Uni.createFrom().item(due)
        every { installments.markAccrued(any(), any()) } returns Uni.createFrom().item(1)

        val outcome = service.accrueDueInterest(LocalDate.parse("2026-08-01"), 500).await().indefinitely()

        assertThat(outcome.installmentsAccrued).isEqualTo(1)
        verify(exactly = 0) { ledger.post(any()) }
        verify(exactly = 1) { installments.markAccrued(any(), any()) }
        verify(exactly = 0) { events.emit(any<LendingOutboxMessage>()) }
    }

    @Test
    fun `repayment settles the receivable when interest was already accrued`() {
        val loanId = LoanId.random()
        val instId = UUID.randomUUID()
        val installment = LoanInstallment(
            id = instId, loanId = loanId, number = 2, dueDate = firstDue,
            openingBalance = eur("11053.81"), principal = eur("955.65"),
            interest = eur("110.54"), payment = eur("1066.19"), closingBalance = eur("10098.16"),
            interestAccrued = true,
        )
        val postings = mutableListOf<LedgerPosting>()
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(listOf(installment))
        every { installments.markPaid(instId, any()) } returns Uni.createFrom().item(1)
        every { ledger.post(capture(postings)) } returns Uni.createFrom().item(Unit)

        service.recordRepayment(loanId, instId).await().indefinitely()

        // Interest income was already recognized at accrual: the cash only clears the receivable.
        assertThat(postings.map { it.kind })
            .containsExactly(PostingKind.PRINCIPAL_REPAYMENT, PostingKind.INTEREST_SETTLEMENT)
    }

    @Test
    fun `repayment recognizes interest income directly when not yet accrued`() {
        val loanId = LoanId.random()
        val instId = UUID.randomUUID()
        val installment = LoanInstallment(
            id = instId, loanId = loanId, number = 1, dueDate = firstDue,
            openingBalance = eur("12000.00"), principal = eur("946.19"),
            interest = eur("120.00"), payment = eur("1066.19"), closingBalance = eur("11053.81"),
            interestAccrued = false,
        )
        val postings = mutableListOf<LedgerPosting>()
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(listOf(installment))
        every { installments.markPaid(instId, any()) } returns Uni.createFrom().item(1)
        every { ledger.post(capture(postings)) } returns Uni.createFrom().item(Unit)

        service.recordRepayment(loanId, instId).await().indefinitely()

        // Paid before the accrual pass ran: recognize income directly at cash time.
        assertThat(postings.map { it.kind })
            .containsExactly(PostingKind.PRINCIPAL_REPAYMENT, PostingKind.INTEREST)
    }

    private fun activeLoan(loanId: LoanId) = Loan(
        id = loanId,
        applicationId = LoanApplicationId.random(),
        partyId = partyId,
        principal = eur("12000.00"),
        nominalAnnualRate = BigDecimal("0.12"),
        termPeriods = 12,
        method = AmortizationMethod.ANNUITY,
        firstDueDate = firstDue,
        status = LoanStatus.ACTIVE,
        disbursedAt = fixedNow,
        createdAt = fixedNow,
    )

    @Test
    fun `write-off posts the outstanding exposure, marks the loan WRITTEN_OFF and emits an event`() {
        val loanId = LoanId.random()
        val loan = activeLoan(loanId)
        // Outstanding = opening balance of the first unpaid installment.
        val schedule = listOf(
            LoanInstallment(
                loanId = loanId, number = 1, dueDate = firstDue,
                openingBalance = eur("12000.00"), principal = eur("946.19"),
                interest = eur("120.00"), payment = eur("1066.19"), closingBalance = eur("11053.81"),
                paid = true, paidAt = fixedNow,
            ),
            LoanInstallment(
                loanId = loanId,
                number = 2,
                dueDate = firstDue.plusMonths(1),
                openingBalance = eur("11053.81"),
                principal = eur("955.65"),
                interest = eur("110.54"),
                payment = eur("1066.19"),
                closingBalance = eur("10098.16"),
            ),
        )
        val postingSlot: CapturingSlot<LedgerPosting> = slot()
        every { loans.findById(loanId) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        every { ledger.post(capture(postingSlot)) } returns Uni.createFrom().item(Unit)
        every { loans.update(any()) } answers { Uni.createFrom().item(firstArg<Loan>()) }
        every { events.emit(any<LendingOutboxMessage>()) } returns Uni.createFrom().item(Unit)

        val written = service.writeOff(
            loanId,
            WriteOffRequest(writtenOffBy = "carol", reason = "insolvency"),
        ).await().indefinitely()

        assertThat(written.status).isEqualTo(LoanStatus.WRITTEN_OFF)
        // The loss is booked for the remaining exposure, not the original principal.
        assertThat(postingSlot.captured.amount).isEqualTo(eur("11053.81"))
        assertThat(postingSlot.captured.kind).isEqualTo(PostingKind.WRITE_OFF)
        verify(exactly = 1) { ledger.post(any()) }
        verify(exactly = 1) { events.emit(any<LendingOutboxMessage>()) }
    }

    @Test
    fun `write-off refuses a loan that is not ACTIVE`() {
        val loanId = LoanId.random()
        every { loans.findById(loanId) } returns
            Uni.createFrom().item(activeLoan(loanId).copy(status = LoanStatus.WRITTEN_OFF))

        assertThatThrownBy {
            service.writeOff(loanId, WriteOffRequest(writtenOffBy = "carol")).await().indefinitely()
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("ACTIVE")

        verify(exactly = 0) { ledger.post(any()) }
        verify(exactly = 0) { loans.update(any()) }
    }

    @Test
    fun `write-off refuses a fully-repaid loan with nothing outstanding`() {
        val loanId = LoanId.random()
        val loan = activeLoan(loanId)
        val schedule = listOf(
            LoanInstallment(
                loanId = loanId, number = 1, dueDate = firstDue,
                openingBalance = eur("12000.00"), principal = eur("12000.00"),
                interest = eur("0.00"), payment = eur("12000.00"), closingBalance = eur("0.00"),
                paid = true, paidAt = fixedNow,
            ),
        )
        every { loans.findById(loanId) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)

        assertThatThrownBy {
            service.writeOff(loanId, WriteOffRequest(writtenOffBy = "carol")).await().indefinitely()
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("Nothing to write off")

        verify(exactly = 0) { ledger.post(any()) }
        verify(exactly = 0) { loans.update(any()) }
    }

    @Test
    fun `provisioning of a current loan is Stage 1 on the full outstanding balance`() {
        val loanId = LoanId.random()
        val loan = Loan(
            applicationId = LoanApplicationId.random(),
            partyId = partyId,
            principal = eur("12000.00"),
            nominalAnnualRate = BigDecimal("0.12"),
            termPeriods = 12,
            method = AmortizationMethod.ANNUITY,
            firstDueDate = firstDue,
            disbursedAt = fixedNow,
            createdAt = fixedNow,
        )
        // One unpaid installment due in the future relative to asOf → DPD 0.
        val schedule = listOf(
            LoanInstallment(
                loanId = loanId,
                number = 1,
                dueDate = firstDue,
                openingBalance = eur("12000.00"),
                principal = eur("946.19"),
                interest = eur("120.00"),
                payment = eur("1066.19"),
                closingBalance = eur("11053.81"),
            ),
        )
        every { loans.findById(loanId) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        every { riskParameters.parametersFor(loan, eur("12000.00")) } returns Uni.createFrom().item(
            EclInputs(
                pd12Month = BigDecimal("0.02"),
                pdLifetime = BigDecimal("0.20"),
                lgd = BigDecimal("0.45"),
                exposureAtDefault = eur("12000.00"),
            ),
        )

        val snapshot = service.assess(loanId, LocalDate.parse("2026-06-01")).await().indefinitely()

        assertThat(snapshot.daysPastDue).isEqualTo(0)
        assertThat(snapshot.stage).isEqualTo(Ifrs9Stage.STAGE_1)
        assertThat(snapshot.outstandingBalance).isEqualTo(eur("12000.00"))
        // Stage 1 ECL = 0.02 * 0.45 * 12000 = 108.00
        assertThat(snapshot.expectedCreditLoss).isEqualTo(eur("108.00"))
    }
}
