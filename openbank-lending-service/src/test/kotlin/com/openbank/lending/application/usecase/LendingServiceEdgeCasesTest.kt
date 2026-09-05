// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.BorrowerAccountLookupPort
import com.openbank.lending.application.port.out.BorrowerCreditPort
import com.openbank.lending.application.port.out.CollateralRepository
import com.openbank.lending.application.port.out.CollateralValuationPort
import com.openbank.lending.application.port.out.InstallmentRepository
import com.openbank.lending.application.port.out.LedgerPostingPort
import com.openbank.lending.application.port.out.LendingOutboxMessage
import com.openbank.lending.application.port.out.LoanApplicationRepository
import com.openbank.lending.application.port.out.LoanEventEmitter
import com.openbank.lending.application.port.out.LoanRepository
import com.openbank.lending.application.port.out.ProvisioningRepository
import com.openbank.lending.application.port.out.RiskParameterSource
import com.openbank.lending.application.port.out.StarterCreditPolicy
import com.openbank.lending.domain.model.Collateral
import com.openbank.lending.domain.model.CollateralRequest
import com.openbank.lending.domain.model.CollateralStatus
import com.openbank.lending.domain.model.CollateralType
import com.openbank.lending.domain.model.DecisionRequest
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanApplicationRequest
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.lending.domain.model.WriteOffRequest
import com.openbank.lending.infrastructure.adapter.NoOpCreditBureauPort
import com.openbank.lending.infrastructure.adapter.NoOpOriginationWorkflowPort
import com.openbank.lending.infrastructure.compliance.CompliancePackGuard
import com.openbank.lending.infrastructure.compliance.OriginationConfig
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.libs.lending.DelinquencyBucket
import com.openbank.libs.lending.EclHorizon
import com.openbank.libs.lending.EclInputs
import com.openbank.libs.lending.Ifrs9Stage
import com.openbank.libs.lending.compliance.CompliancePackRegistry
import com.openbank.libs.lending.origination.OriginationState
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

/**
 * Guard-clause and error-path coverage for [LendingService], complementing the happy-path suite in
 * [LendingServiceTest]: intake validation, decision/disbursement state machine refusals, repayment
 * edge cases, collateral haircut bounds, and the IFRS 9 provisioning read model beyond Stage 1.
 */
class LendingServiceEdgeCasesTest {

    private val applications = mockk<LoanApplicationRepository>()
    private val loans = mockk<LoanRepository>()
    private val installments = mockk<InstallmentRepository>()
    private val collateral = mockk<CollateralRepository>()
    private val ledger = mockk<LedgerPostingPort>()
    private val valuation = mockk<CollateralValuationPort>()
    private val riskParameters = mockk<RiskParameterSource>()
    private val events = mockk<LoanEventEmitter>()

    @org.junit.jupiter.api.BeforeEach
    fun stubEventEmitter() {
        every { events.emit(any<LendingOutboxMessage>()) } returns Uni.createFrom().item(Unit)
    }
    private val clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val provisioning = mockk<ProvisioningRepository>()
    private val borrowerAccounts = mockk<BorrowerAccountLookupPort>()
    private val borrowerCredit = mockk<BorrowerCreditPort>()

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
        CompliancePackGuard(CompliancePackRegistry(), clock, enforced = false),
        OriginationConfig(false),
        NoOpOriginationWorkflowPort(),
        OriginationDecisionService(
            NoOpCreditBureauPort(),
            StarterCreditPolicy(),
            CompliancePackGuard(CompliancePackRegistry(), clock, enforced = false),
            clock,
        ),
        borrowerAccounts,
        borrowerCredit,
    )

    private val partyId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val firstDue = LocalDate.parse("2026-06-30")
    private val fixedNow = OffsetDateTime.ofInstant(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC)

    private fun eur(v: String) = Money.of(v, "EUR")

    private fun request(amount: String = "12000.00", rate: String = "0.12", term: Int = 12) = LoanApplicationRequest(
        partyId = partyId,
        requestedAmount = eur(amount),
        nominalAnnualRate = BigDecimal(rate),
        termPeriods = term,
        firstDueDate = firstDue,
    )

    private fun proposedApplication(proposer: String = "alice") = LoanApplication(
        partyId = partyId,
        requestedAmount = eur("12000.00"),
        nominalAnnualRate = BigDecimal("0.12"),
        termPeriods = 12,
        firstDueDate = firstDue,
        proposedBy = proposer,
        status = OriginationState.FOUR_EYES,
        createdAt = fixedNow,
    )

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

    private fun installment(loanId: LoanId, number: Int, paid: Boolean = false) = LoanInstallment(
        loanId = loanId,
        number = number,
        dueDate = firstDue.plusMonths((number - 1).toLong()),
        openingBalance = eur("12000.00"),
        principal = eur("946.19"),
        interest = eur("120.00"),
        payment = eur("1066.19"),
        closingBalance = eur("11053.81"),
        paid = paid,
        paidAt = if (paid) fixedNow else null,
    )

    // --- Intake validation ---------------------------------------------------------------------

    /**
     * The conditional UPDATE that claims an origination transition (issue #3850). [claimed] is the
     * row count the database returns: `1` won the row, `0` lost the race to another caller.
     */
    private fun stubClaim(claimed: Int = 1) {
        every { applications.compareAndSetStatus(any(), any(), any(), any(), any(), any()) } returns
            Uni.createFrom().item(claimed)
    }

    private fun verifyClaims(times: Int) =
        verify(exactly = times) { applications.compareAndSetStatus(any(), any(), any(), any(), any(), any()) }

    @Test
    fun `apply rejects a non-positive requested amount`() {
        assertThatThrownBy { service.apply(request(amount = "0.00"), "alice") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("positive")

        verify(exactly = 0) { applications.save(any()) }
    }

    @Test
    fun `apply rejects a zero term`() {
        assertThatThrownBy { service.apply(request(term = 0), "alice") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at least one period")

        verify(exactly = 0) { applications.save(any()) }
    }

    @Test
    fun `apply rejects a negative nominal rate`() {
        assertThatThrownBy { service.apply(request(rate = "-0.01"), "alice") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("cannot be negative")

        verify(exactly = 0) { applications.save(any()) }
    }

    @Test
    fun `apply rejects a blank proposer identity`() {
        assertThatThrownBy { service.apply(request(), " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Proposer identity")

        verify(exactly = 0) { applications.save(any()) }
    }

    // --- Decision state machine ----------------------------------------------------------------

    @Test
    fun `decide fails for an unknown application`() {
        val id = LoanApplicationId.random()
        every { applications.findById(id) } returns Uni.createFrom().nullItem()

        assertThatThrownBy {
            service.decide(id, DecisionRequest(approve = true), "bob").await().indefinitely()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not found")
    }

    @Test
    fun `decide refuses an application that is already decided`() {
        val app = proposedApplication().copy(status = OriginationState.READY_TO_DISBURSE, decidedBy = "bob")
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)

        assertThatThrownBy {
            service.decide(app.id, DecisionRequest(approve = false), "carol").await().indefinitely()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not awaiting a four-eyes decision")

        verifyClaims(0)
    }

    @Test
    fun `decide refuses a blank decider identity`() {
        val app = proposedApplication()
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)

        assertThatThrownBy {
            service.decide(app.id, DecisionRequest(approve = true), "").await().indefinitely()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Decider identity")

        verifyClaims(0)
    }

    @Test
    fun `decide records a rejection with the stated reason and decision time`() {
        val app = proposedApplication(proposer = "alice")
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        stubClaim()

        val result = service.decide(
            app.id,
            DecisionRequest(approve = false, reason = "affordability"),
            "bob",
        ).await().indefinitely()

        assertThat(result.status).isEqualTo(OriginationState.DECLINED)
        assertThat(result.decidedBy).isEqualTo("bob")
        assertThat(result.decisionReason).isEqualTo("affordability")
        assertThat(result.decidedAt).isEqualTo(fixedNow)
    }

    @Test
    fun `getApplication and listApplications delegate to the repository`() {
        val app = proposedApplication()
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        every { applications.findByParty(partyId) } returns Uni.createFrom().item(listOf(app))

        assertThat(service.getApplication(app.id).await().indefinitely()).isEqualTo(app)
        assertThat(service.listApplications(partyId).await().indefinitely()).containsExactly(app)
    }

    // --- Disbursement --------------------------------------------------------------------------

    @Test
    fun `disburse fails for an unknown application`() {
        val id = LoanApplicationId.random()
        every { applications.findById(id) } returns Uni.createFrom().nullItem()

        assertThatThrownBy { service.disburse(id, "dave").await().indefinitely() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not found")

        verify(exactly = 0) { loans.save(any()) }
    }

    @Test
    fun `disburse refuses a blank disburser identity`() {
        val app = proposedApplication().copy(status = OriginationState.READY_TO_DISBURSE, decidedBy = "bob")
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)

        assertThatThrownBy { service.disburse(app.id, "").await().indefinitely() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Disburser identity")

        verify(exactly = 0) { loans.save(any()) }
    }

    // --- Servicing reads and repayment edge cases ------------------------------------------------

    @Test
    fun `servicing reads delegate to the repositories`() {
        val loanId = LoanId.random()
        val loan = activeLoan(loanId)
        val schedule = listOf(installment(loanId, 1))
        every { loans.findById(loanId) } returns Uni.createFrom().item(loan)
        every { loans.findByParty(partyId) } returns Uni.createFrom().item(listOf(loan))
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)

        assertThat(service.getLoan(loanId).await().indefinitely()).isEqualTo(loan)
        assertThat(service.listLoans(partyId).await().indefinitely()).containsExactly(loan)
        assertThat(service.getSchedule(loanId).await().indefinitely()).isEqualTo(schedule)
    }

    @Test
    fun `repayment fails when the installment is not on the loan`() {
        val loanId = LoanId.random()
        // recordRepayment now loads the loan first (#1245).
        every { loans.findById(loanId) } returns Uni.createFrom().item(activeLoan(loanId))
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(listOf(installment(loanId, 1)))

        assertThatThrownBy {
            service.recordRepayment(loanId, UUID.randomUUID()).await().indefinitely()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Installment not found")

        verify(exactly = 0) { installments.markPaid(any(), any()) }
        verify(exactly = 0) { ledger.post(any()) }
    }

    @Test
    fun `repayment refuses an installment that is already paid`() {
        val loanId = LoanId.random()
        val paid = installment(loanId, 1, paid = true)
        // recordRepayment now loads the loan first (#1245).
        every { loans.findById(loanId) } returns Uni.createFrom().item(activeLoan(loanId))
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(listOf(paid))

        assertThatThrownBy {
            service.recordRepayment(loanId, paid.id).await().indefinitely()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("already paid")

        verify(exactly = 0) { installments.markPaid(any(), any()) }
        verify(exactly = 0) { ledger.post(any()) }
    }

    // --- Write-off -----------------------------------------------------------------------------

    @Test
    fun `write-off fails for an unknown loan`() {
        val loanId = LoanId.random()
        every { loans.findById(loanId) } returns Uni.createFrom().nullItem()

        assertThatThrownBy {
            service.writeOff(loanId, WriteOffRequest(writtenOffBy = "carol")).await().indefinitely()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not found")

        verify(exactly = 0) { ledger.post(any()) }
    }

    // --- Collateral ----------------------------------------------------------------------------

    @Test
    fun `collateral registration rejects a haircut above one`() {
        val loanId = LoanId.random()
        val request = CollateralRequest(
            type = CollateralType.REAL_ESTATE,
            marketValue = eur("250000.00"),
            haircut = BigDecimal("1.01"),
        )

        assertThatThrownBy { service.register(loanId, request, "officer-1") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Haircut")

        verify(exactly = 0) { collateral.save(any()) }
    }

    @Test
    fun `collateral registration rejects a negative haircut`() {
        val loanId = LoanId.random()
        val request = CollateralRequest(
            type = CollateralType.VEHICLE,
            marketValue = eur("15000.00"),
            haircut = BigDecimal("-0.10"),
        )

        assertThatThrownBy { service.register(loanId, request, "officer-1") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Haircut")

        verify(exactly = 0) { collateral.save(any()) }
    }

    @Test
    fun `collateral registration persists the externally revalued amount, not the declared one`() {
        val loanId = LoanId.random()
        val saved: CapturingSlot<Collateral> = slot()
        every { valuation.revalue("REAL_ESTATE", eur("250000.00")) } returns Uni.createFrom().item(eur("230000.00"))
        every { collateral.save(capture(saved)) } answers { Uni.createFrom().item(saved.captured) }

        val result = service.register(
            loanId,
            CollateralRequest(
                type = CollateralType.REAL_ESTATE,
                description = "apartment",
                marketValue = eur("250000.00"),
                haircut = BigDecimal("0.20"),
            ),
            "officer-1",
        ).await().indefinitely()

        // The valuer's opinion wins over the declared market value.
        assertThat(result.marketValue).isEqualTo(eur("230000.00"))
        assertThat(result.loanId).isEqualTo(loanId)
        assertThat(result.type).isEqualTo(CollateralType.REAL_ESTATE)
        assertThat(result.haircut).isEqualTo(BigDecimal("0.20"))
        assertThat(result.valuedAt).isEqualTo(fixedNow)
        // Four-eyes (ADR-0028 follow-up, issue #621): registration is PENDING and attributed to the maker.
        assertThat(result.status).isEqualTo(CollateralStatus.PENDING)
        assertThat(result.registeredBy).isEqualTo("officer-1")
        verify(exactly = 1) { collateral.save(any()) }
    }

    @Test
    fun `collateral list delegates to the repository`() {
        val loanId = LoanId.random()
        every { collateral.findByLoan(loanId) } returns Uni.createFrom().item(emptyList())

        assertThat(service.list(loanId).await().indefinitely()).isEmpty()
    }

    // --- Provisioning (IFRS 9) beyond Stage 1 ----------------------------------------------------

    @Test
    fun `provisioning fails for an unknown loan`() {
        val loanId = LoanId.random()
        every { loans.findById(loanId) } returns Uni.createFrom().nullItem()

        assertThatThrownBy {
            service.assess(loanId, LocalDate.parse("2026-10-08")).await().indefinitely()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not found")
    }

    @Test
    fun `a loan 100 days past due is credit-impaired Stage 3 with lifetime ECL`() {
        val loanId = LoanId.random()
        val loan = activeLoan(loanId)
        // Oldest unpaid installment fell due on 2026-06-30; asOf is 100 days later.
        val schedule = listOf(installment(loanId, 1))
        every { loans.findById(loanId) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        every { riskParameters.parametersFor(loan, eur("12000.00")) } returns Uni.createFrom().item(
            EclInputs(
                pd12Month = BigDecimal("0.02"),
                pdLifetime = BigDecimal("0.20"),
                lgd = BigDecimal("0.45"),
                exposureAtDefault = eur("12000.00"),
                modelVersion = "test-model-v1",
            ),
        )
        every { collateral.findByLoan(loanId) } returns Uni.createFrom().item(emptyList())

        val snapshot = service.assess(loanId, LocalDate.parse("2026-10-08")).await().indefinitely()

        assertThat(snapshot.daysPastDue).isEqualTo(100)
        assertThat(snapshot.bucket).isEqualTo(DelinquencyBucket.DPD_90_PLUS)
        assertThat(snapshot.stage).isEqualTo(Ifrs9Stage.STAGE_3)
        assertThat(snapshot.horizon).isEqualTo(EclHorizon.LIFETIME)
        // Lifetime ECL = pdLifetime * lgd * EAD = 0.20 * 0.45 * 12000 = 1080.00
        assertThat(snapshot.expectedCreditLoss).isEqualTo(eur("1080.00"))
    }

    @Test
    fun `a fully repaid loan carries zero outstanding exposure and zero ECL`() {
        val loanId = LoanId.random()
        val loan = activeLoan(loanId)
        val schedule = listOf(installment(loanId, 1, paid = true))
        every { loans.findById(loanId) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        every { riskParameters.parametersFor(loan, Money.zero("EUR")) } returns Uni.createFrom().item(
            EclInputs(
                pd12Month = BigDecimal("0.02"),
                pdLifetime = BigDecimal("0.20"),
                lgd = BigDecimal("0.45"),
                exposureAtDefault = Money.zero("EUR"),
                modelVersion = "test-model-v1",
            ),
        )
        every { collateral.findByLoan(loanId) } returns Uni.createFrom().item(emptyList())

        val snapshot = service.assess(loanId, LocalDate.parse("2026-10-08")).await().indefinitely()

        // No unpaid installment: nothing overdue, nothing at risk.
        assertThat(snapshot.daysPastDue).isEqualTo(0)
        assertThat(snapshot.bucket).isEqualTo(DelinquencyBucket.CURRENT)
        assertThat(snapshot.stage).isEqualTo(Ifrs9Stage.STAGE_1)
        assertThat(snapshot.outstandingBalance).isEqualTo(Money.zero("EUR"))
        assertThat(snapshot.expectedCreditLoss.isZero()).isTrue()
    }

    @Test
    fun `the backoffice queues clamp limit into 1_100 and pass the status filter through`() {
        val appLimits = mutableListOf<Int>()
        val loanLimits = mutableListOf<Int>()
        every { applications.findRecent(any(), any()) } answers {
            appLimits += secondArg<Int>()
            Uni.createFrom().item(emptyList<LoanApplication>())
        }
        every { loans.findActive(any()) } answers {
            loanLimits += firstArg<Int>()
            Uni.createFrom().item(emptyList<Loan>())
        }

        service.listRecentApplications(null, 0).await().indefinitely()
        service.listRecentApplications("PROPOSED", 10_000).await().indefinitely()
        service.listActiveLoans(0).await().indefinitely()
        service.listActiveLoans(10_000).await().indefinitely()

        assertThat(appLimits).containsExactly(1, 100)
        assertThat(loanLimits).containsExactly(1, 100)
        verify(exactly = 1) { applications.findRecent("PROPOSED", 100) }
        verify(exactly = 1) { applications.findRecent(null, 1) }
    }
}
