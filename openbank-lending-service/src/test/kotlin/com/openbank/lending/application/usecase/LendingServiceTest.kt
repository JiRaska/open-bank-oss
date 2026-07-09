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
import com.openbank.lending.application.port.out.ProvisioningRepository
import com.openbank.lending.application.port.out.RiskParameterSource
import com.openbank.lending.domain.model.ApplicationStatus
import com.openbank.lending.domain.model.Collateral
import com.openbank.lending.domain.model.CollateralType
import com.openbank.lending.domain.model.DecisionRequest
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanApplicationRequest
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanProvisioningRecord
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
    private val provisioning = mockk<ProvisioningRepository>()

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
        provisioning,
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
            id = loanId,
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
        // No collateral registered: LGD must stay the flat, unadjusted placeholder (no regression).
        every { collateral.findByLoan(loanId) } returns Uni.createFrom().item(emptyList())

        val snapshot = service.assess(loanId, LocalDate.parse("2026-06-01")).await().indefinitely()

        assertThat(snapshot.daysPastDue).isEqualTo(0)
        assertThat(snapshot.stage).isEqualTo(Ifrs9Stage.STAGE_1)
        assertThat(snapshot.outstandingBalance).isEqualTo(eur("12000.00"))
        // Stage 1 ECL = 0.02 * 0.45 * 12000 = 108.00
        assertThat(snapshot.expectedCreditLoss).isEqualTo(eur("108.00"))
    }

    // --- Collateral-adjusted LGD (ADR-0028 D1, first increment) -----------------------------------

    private fun collateralItem(loanId: LoanId, marketValue: Money, haircut: BigDecimal, type: CollateralType) =
        Collateral(
            loanId = loanId,
            type = type,
            marketValue = marketValue,
            haircut = haircut,
            valuedAt = fixedNow,
            createdAt = fixedNow,
        )

    @Test
    fun `registered collateral reduces the ECL proportionally to its haircut-adjusted cover`() {
        val loanId = LoanId.random()
        val loan = Loan(
            id = loanId,
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
        // Real estate, declared 15000.00, 20% haircut -> haircut-adjusted cover = 12000.00.
        // Coverage ratio = 12000 / 12000 = 1.00 -> effective LGD = max(0, 0.45 - 1.00) = 0.
        every { collateral.findByLoan(loanId) } returns Uni.createFrom().item(
            listOf(collateralItem(loanId, eur("15000.00"), BigDecimal("0.20"), CollateralType.REAL_ESTATE)),
        )

        val snapshot = service.assess(loanId, LocalDate.parse("2026-06-01")).await().indefinitely()

        assertThat(snapshot.expectedCreditLoss.isZero()).isTrue()
    }

    @Test
    fun `partial collateral cover reduces but does not zero out the ECL`() {
        val loanId = LoanId.random()
        val loan = Loan(
            id = loanId,
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
        // Vehicle, declared 5000.00, 40% haircut -> haircut-adjusted cover = 3000.00.
        // Coverage ratio = 3000 / 12000 = 0.25 -> effective LGD = 0.45 - 0.25 = 0.20.
        every { collateral.findByLoan(loanId) } returns Uni.createFrom().item(
            listOf(collateralItem(loanId, eur("5000.00"), BigDecimal("0.40"), CollateralType.VEHICLE)),
        )

        val snapshot = service.assess(loanId, LocalDate.parse("2026-06-01")).await().indefinitely()

        // Stage 1 ECL = 0.02 * 0.20 * 12000 = 48.00 (versus the unsecured 108.00).
        assertThat(snapshot.expectedCreditLoss).isEqualTo(eur("48.00"))
    }

    @Test
    fun `multiple collateral items sum their haircut-adjusted value before reducing LGD`() {
        val loanId = LoanId.random()
        val loan = Loan(
            id = loanId,
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
        // Vehicle 5000.00 @ 40% haircut = 3000.00, plus cash deposit 2000.00 @ 0% haircut = 2000.00.
        // Summed haircut-adjusted cover = 5000.00. Coverage ratio = 5000/12000 -> LGD 0.45 - 0.41(6..) ~ 0.0333.
        every { collateral.findByLoan(loanId) } returns Uni.createFrom().item(
            listOf(
                collateralItem(loanId, eur("5000.00"), BigDecimal("0.40"), CollateralType.VEHICLE),
                collateralItem(loanId, eur("2000.00"), BigDecimal.ZERO, CollateralType.CASH_DEPOSIT),
            ),
        )

        val snapshot = service.assess(loanId, LocalDate.parse("2026-06-01")).await().indefinitely()

        // effective LGD = 0.45 - (5000/12000) = 0.45 - 0.416666... = 0.033333...
        // ECL = 0.02 * 0.033333... * 12000 = 8.00
        assertThat(snapshot.expectedCreditLoss).isEqualTo(eur("8.00"))
    }

    @Test
    fun `haircut-adjusted collateral exceeding exposure floors ECL at zero, never negative`() {
        val loanId = LoanId.random()
        val loan = Loan(
            id = loanId,
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
        // Massively over-collateralized: 100000.00 cash, zero haircut, far exceeds the 12000.00 exposure.
        every { collateral.findByLoan(loanId) } returns Uni.createFrom().item(
            listOf(collateralItem(loanId, eur("100000.00"), BigDecimal.ZERO, CollateralType.CASH_DEPOSIT)),
        )

        val snapshot = service.assess(loanId, LocalDate.parse("2026-06-01")).await().indefinitely()

        assertThat(snapshot.expectedCreditLoss.isZero()).isTrue()
        assertThat(snapshot.expectedCreditLoss.isNegative()).isFalse()
    }

    // --- Provisioning cycle: scheduled delta-vs-prior-period posting ---------------------------------

    private fun currentLoanWithSchedule(loanId: LoanId): Pair<Loan, List<LoanInstallment>> {
        val loan = Loan(
            id = loanId,
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
        return loan to schedule
    }

    private fun mockRiskParameters(loan: Loan, pd12m: String) {
        every { riskParameters.parametersFor(loan, eur("12000.00")) } returns Uni.createFrom().item(
            EclInputs(
                pd12Month = BigDecimal(pd12m),
                pdLifetime = BigDecimal("0.20"),
                lgd = BigDecimal("0.45"),
                exposureAtDefault = eur("12000.00"),
            ),
        )
        // No collateral registered on these loans: LGD stays the flat placeholder (no regression).
        every { collateral.findByLoan(loan.id) } returns Uni.createFrom().item(emptyList())
    }

    @Test
    fun `first provisioning cycle for a loan posts the full ECL as the delta`() {
        val loanId = LoanId.random()
        val (loan, schedule) = currentLoanWithSchedule(loanId)
        val postings = mutableListOf<LedgerPosting>()
        val savedRecords = mutableListOf<LoanProvisioningRecord>()
        every { loans.findActive(any()) } returns Uni.createFrom().item(listOf(loan))
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        mockRiskParameters(loan, "0.02")
        every { provisioning.findByLoanAndPeriod(loanId, "2026-06") } returns Uni.createFrom().nullItem()
        every { provisioning.findLatestBefore(loanId, "2026-06") } returns Uni.createFrom().nullItem()
        every { ledger.post(capture(postings)) } returns Uni.createFrom().item(Unit)
        every { provisioning.save(capture(savedRecords)) } answers { Uni.createFrom().item(savedRecords.last()) }
        every { events.emit(any<LendingOutboxMessage>()) } returns Uni.createFrom().item(Unit)

        val outcome = service.runProvisioningCycle("2026-06", LocalDate.parse("2026-06-01"), 500)
            .await().indefinitely()

        assertThat(outcome.loansAssessed).isEqualTo(1)
        assertThat(outcome.journalsPosted).isEqualTo(1)
        // No prior period: the whole Stage 1 ECL (108.00) is the delta, never a partial amount.
        assertThat(postings).hasSize(1)
        assertThat(postings.single().kind).isEqualTo(PostingKind.PROVISIONING)
        assertThat(postings.single().amount).isEqualTo(eur("108.00"))
        assertThat(postings.single().reference).isEqualTo("loan:${loanId.value}:provisioning:2026-06")
        assertThat(savedRecords.single().expectedCreditLoss).isEqualTo(eur("108.00"))
        verify(exactly = 1) { events.emit(any<LendingOutboxMessage>()) }
    }

    @Test
    fun `provisioning cycle posts only the increase since the prior period`() {
        val loanId = LoanId.random()
        val (loan, schedule) = currentLoanWithSchedule(loanId)
        val prior = LoanProvisioningRecord(
            loanId = loanId,
            period = "2026-05",
            asOf = LocalDate.parse("2026-05-01"),
            outstandingBalance = eur("12000.00"),
            daysPastDue = 0,
            bucket = com.openbank.libs.lending.DelinquencyBucket.CURRENT,
            stage = Ifrs9Stage.STAGE_1,
            expectedCreditLoss = eur("108.00"),
            createdAt = fixedNow,
        )
        val postings = mutableListOf<LedgerPosting>()
        every { loans.findActive(any()) } returns Uni.createFrom().item(listOf(loan))
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        // Deteriorated: higher 12m PD this cycle, so the ECL (and thus the delta) increases.
        mockRiskParameters(loan, "0.04")
        every { provisioning.findByLoanAndPeriod(loanId, "2026-06") } returns Uni.createFrom().nullItem()
        every { provisioning.findLatestBefore(loanId, "2026-06") } returns Uni.createFrom().item(prior)
        every { ledger.post(capture(postings)) } returns Uni.createFrom().item(Unit)
        every { provisioning.save(any()) } answers { Uni.createFrom().item(firstArg<LoanProvisioningRecord>()) }
        every { events.emit(any<LendingOutboxMessage>()) } returns Uni.createFrom().item(Unit)

        val outcome = service.runProvisioningCycle("2026-06", LocalDate.parse("2026-06-01"), 500)
            .await().indefinitely()

        // New ECL = 0.04 * 0.45 * 12000 = 216.00; prior = 108.00 -> delta = +108.00, not the full 216.00.
        assertThat(outcome.journalsPosted).isEqualTo(1)
        assertThat(postings.single().amount).isEqualTo(eur("108.00"))
        assertThat(postings.single().amount.isPositive()).isTrue()
    }

    @Test
    fun `provisioning cycle posts a negative delta when risk improves (partial release)`() {
        val loanId = LoanId.random()
        val (loan, schedule) = currentLoanWithSchedule(loanId)
        val prior = LoanProvisioningRecord(
            loanId = loanId,
            period = "2026-05",
            asOf = LocalDate.parse("2026-05-01"),
            outstandingBalance = eur("12000.00"),
            daysPastDue = 0,
            bucket = com.openbank.libs.lending.DelinquencyBucket.CURRENT,
            stage = Ifrs9Stage.STAGE_1,
            expectedCreditLoss = eur("216.00"),
            createdAt = fixedNow,
        )
        val postings = mutableListOf<LedgerPosting>()
        every { loans.findActive(any()) } returns Uni.createFrom().item(listOf(loan))
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        mockRiskParameters(loan, "0.02")
        every { provisioning.findByLoanAndPeriod(loanId, "2026-06") } returns Uni.createFrom().nullItem()
        every { provisioning.findLatestBefore(loanId, "2026-06") } returns Uni.createFrom().item(prior)
        every { ledger.post(capture(postings)) } returns Uni.createFrom().item(Unit)
        every { provisioning.save(any()) } answers { Uni.createFrom().item(firstArg<LoanProvisioningRecord>()) }
        every { events.emit(any<LendingOutboxMessage>()) } returns Uni.createFrom().item(Unit)

        val outcome = service.runProvisioningCycle("2026-06", LocalDate.parse("2026-06-01"), 500)
            .await().indefinitely()

        // New ECL = 108.00; prior = 216.00 -> delta = -108.00 (a release).
        assertThat(outcome.journalsPosted).isEqualTo(1)
        assertThat(postings.single().amount).isEqualTo(eur("-108.00"))
        assertThat(postings.single().amount.isNegative()).isTrue()
    }

    @Test
    fun `provisioning cycle posts nothing and skips the event when the ECL is unchanged`() {
        val loanId = LoanId.random()
        val (loan, schedule) = currentLoanWithSchedule(loanId)
        val prior = LoanProvisioningRecord(
            loanId = loanId,
            period = "2026-05",
            asOf = LocalDate.parse("2026-05-01"),
            outstandingBalance = eur("12000.00"),
            daysPastDue = 0,
            bucket = com.openbank.libs.lending.DelinquencyBucket.CURRENT,
            stage = Ifrs9Stage.STAGE_1,
            expectedCreditLoss = eur("108.00"),
            createdAt = fixedNow,
        )
        every { loans.findActive(any()) } returns Uni.createFrom().item(listOf(loan))
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        mockRiskParameters(loan, "0.02")
        every { provisioning.findByLoanAndPeriod(loanId, "2026-06") } returns Uni.createFrom().nullItem()
        every { provisioning.findLatestBefore(loanId, "2026-06") } returns Uni.createFrom().item(prior)
        every { provisioning.save(any()) } answers { Uni.createFrom().item(firstArg<LoanProvisioningRecord>()) }

        val outcome = service.runProvisioningCycle("2026-06", LocalDate.parse("2026-06-01"), 500)
            .await().indefinitely()

        assertThat(outcome.loansAssessed).isEqualTo(1)
        assertThat(outcome.journalsPosted).isEqualTo(0)
        verify(exactly = 0) { ledger.post(any()) }
        verify(exactly = 0) { events.emit(any<LendingOutboxMessage>()) }
        verify(exactly = 1) { provisioning.save(any()) }
    }

    @Test
    fun `provisioning cycle emits loan stage_changed only on a genuine stage transition`() {
        val loanId = LoanId.random()
        val loan = Loan(
            id = loanId,
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
        // Unpaid installment due 2026-06-30; assessed 40 days later => DPD 40 > the 30-day SICR
        // threshold => Stage 2, whereas the prior period's record was Stage 1.
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
        val asOf = firstDue.plusDays(40)
        val prior = LoanProvisioningRecord(
            loanId = loanId,
            period = "2026-06",
            asOf = firstDue,
            outstandingBalance = eur("12000.00"),
            daysPastDue = 0,
            bucket = com.openbank.libs.lending.DelinquencyBucket.CURRENT,
            stage = Ifrs9Stage.STAGE_1,
            expectedCreditLoss = eur("108.00"),
            createdAt = fixedNow,
        )
        val emitted = mutableListOf<LendingOutboxMessage>()
        every { loans.findActive(any()) } returns Uni.createFrom().item(listOf(loan))
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        mockRiskParameters(loan, "0.02")
        every { provisioning.findByLoanAndPeriod(loanId, "2026-07") } returns Uni.createFrom().nullItem()
        every { provisioning.findLatestBefore(loanId, "2026-07") } returns Uni.createFrom().item(prior)
        every { ledger.post(any()) } returns Uni.createFrom().item(Unit)
        every { provisioning.save(any()) } answers { Uni.createFrom().item(firstArg<LoanProvisioningRecord>()) }
        every { events.emit(capture(emitted)) } returns Uni.createFrom().item(Unit)

        service.runProvisioningCycle("2026-07", asOf, 500).await().indefinitely()

        val stageChangedEvents = emitted.filter { it.eventType == "loan.stage_changed" }
        assertThat(stageChangedEvents).hasSize(1)
        val payload = stageChangedEvents.single().payload
        assertThat(payload).contains(""""loanId":"${loanId.value}"""")
        assertThat(payload).contains(""""previousStage":"STAGE_1"""")
        assertThat(payload).contains(""""newStage":"STAGE_2"""")
        assertThat(payload).contains(""""daysPastDue":40""")
    }

    @Test
    fun `provisioning cycle does not emit loan stage_changed when the stage is unchanged`() {
        val loanId = LoanId.random()
        val (loan, schedule) = currentLoanWithSchedule(loanId)
        val prior = LoanProvisioningRecord(
            loanId = loanId,
            period = "2026-05",
            asOf = LocalDate.parse("2026-05-01"),
            outstandingBalance = eur("12000.00"),
            daysPastDue = 0,
            bucket = com.openbank.libs.lending.DelinquencyBucket.CURRENT,
            stage = Ifrs9Stage.STAGE_1,
            expectedCreditLoss = eur("108.00"),
            createdAt = fixedNow,
        )
        val emitted = mutableListOf<LendingOutboxMessage>()
        every { loans.findActive(any()) } returns Uni.createFrom().item(listOf(loan))
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        // Same PD as the prior period's baseline: Stage 1 -> Stage 1, zero ECL delta.
        mockRiskParameters(loan, "0.02")
        every { provisioning.findByLoanAndPeriod(loanId, "2026-06") } returns Uni.createFrom().nullItem()
        every { provisioning.findLatestBefore(loanId, "2026-06") } returns Uni.createFrom().item(prior)
        every { provisioning.save(any()) } answers { Uni.createFrom().item(firstArg<LoanProvisioningRecord>()) }
        every { events.emit(capture(emitted)) } returns Uni.createFrom().item(Unit)

        service.runProvisioningCycle("2026-06", LocalDate.parse("2026-06-01"), 500).await().indefinitely()

        assertThat(emitted.filter { it.eventType == "loan.stage_changed" }).isEmpty()
    }

    @Test
    fun `first provisioning cycle for a loan never emits loan stage_changed (no prior stage to compare)`() {
        val loanId = LoanId.random()
        val (loan, schedule) = currentLoanWithSchedule(loanId)
        val emitted = mutableListOf<LendingOutboxMessage>()
        every { loans.findActive(any()) } returns Uni.createFrom().item(listOf(loan))
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        mockRiskParameters(loan, "0.02")
        every { provisioning.findByLoanAndPeriod(loanId, "2026-06") } returns Uni.createFrom().nullItem()
        every { provisioning.findLatestBefore(loanId, "2026-06") } returns Uni.createFrom().nullItem()
        every { ledger.post(any()) } returns Uni.createFrom().item(Unit)
        every { provisioning.save(any()) } answers { Uni.createFrom().item(firstArg<LoanProvisioningRecord>()) }
        every { events.emit(capture(emitted)) } returns Uni.createFrom().item(Unit)

        service.runProvisioningCycle("2026-06", LocalDate.parse("2026-06-01"), 500).await().indefinitely()

        assertThat(emitted.filter { it.eventType == "loan.stage_changed" }).isEmpty()
        assertThat(emitted.filter { it.eventType == "loan.provisioned" }).hasSize(1)
    }

    @Test
    fun `provisioning cycle is idempotent - a loan already provisioned this period is skipped`() {
        val loanId = LoanId.random()
        val (loan, _) = currentLoanWithSchedule(loanId)
        val already = LoanProvisioningRecord(
            loanId = loanId,
            period = "2026-06",
            asOf = LocalDate.parse("2026-06-01"),
            outstandingBalance = eur("12000.00"),
            daysPastDue = 0,
            bucket = com.openbank.libs.lending.DelinquencyBucket.CURRENT,
            stage = Ifrs9Stage.STAGE_1,
            expectedCreditLoss = eur("108.00"),
            createdAt = fixedNow,
        )
        every { loans.findActive(any()) } returns Uni.createFrom().item(listOf(loan))
        every { provisioning.findByLoanAndPeriod(loanId, "2026-06") } returns Uni.createFrom().item(already)

        val outcome = service.runProvisioningCycle("2026-06", LocalDate.parse("2026-06-01"), 500)
            .await().indefinitely()

        assertThat(outcome.loansAssessed).isEqualTo(1)
        assertThat(outcome.journalsPosted).isEqualTo(0)
        verify(exactly = 0) { installments.findByLoan(any()) }
        verify(exactly = 0) { ledger.post(any()) }
        verify(exactly = 0) { provisioning.save(any()) }
    }
}
