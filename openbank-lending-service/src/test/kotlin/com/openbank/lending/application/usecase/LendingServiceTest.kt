// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.out.BorrowerAccountLookupPort
import com.openbank.lending.application.port.out.BorrowerCreditPort
import com.openbank.lending.application.port.out.CatalogLoanProfile
import com.openbank.lending.application.port.out.CatalogLoanProfilePort
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
import com.openbank.lending.application.port.out.StarterCreditPolicy
import com.openbank.lending.domain.model.CatalogLoanSnapshot
import com.openbank.lending.domain.model.Collateral
import com.openbank.lending.domain.model.CollateralDecisionRequest
import com.openbank.lending.domain.model.CollateralRequest
import com.openbank.lending.domain.model.CollateralStatus
import com.openbank.lending.domain.model.CollateralType
import com.openbank.lending.domain.model.DecisionRequest
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanApplicationRequest
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanProvisioningRecord
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.lending.domain.model.RescheduleRequest
import com.openbank.lending.domain.model.WriteOffRequest
import com.openbank.lending.infrastructure.adapter.NoOpCreditBureauPort
import com.openbank.lending.infrastructure.adapter.NoOpOriginationWorkflowPort
import com.openbank.lending.infrastructure.compliance.CompliancePackGuard
import com.openbank.lending.infrastructure.compliance.OriginationConfig
import com.openbank.libs.domain.identifiers.CollateralId
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.AmortizationMethod
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

class LendingServiceTest {

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
    private val catalogLoanProfiles = mockk<CatalogLoanProfilePort>()

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
        catalogLoanProfiles,
    )

    private val partyId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val firstDue = LocalDate.parse("2026-06-30")

    private fun eur(v: String) = Money.of(v, "EUR")

    /**
     * The conditional UPDATE that claims an origination transition (issue #3850). [claimed] is the
     * row count the database returns: `1` won the row, `0` lost the race to another caller.
     */
    private fun stubClaim(claimed: Int = 1) {
        every { applications.compareAndSetStatus(any(), any(), any(), any(), any(), any()) } returns
            Uni.createFrom().item(claimed)
        // The ASSESSMENT leg claims through the decision-carrying overload instead, so both must be
        // stubbed for a walk that crosses that state.
        every { applications.compareAndSetDecision(any(), any()) } returns Uni.createFrom().item(claimed)
    }

    private fun verifyClaims(times: Int) =
        verify(exactly = times) { applications.compareAndSetStatus(any(), any(), any(), any(), any(), any()) }

    @Test
    fun `advance walks the canonical path skipping optional states by default`() {
        val app = proposedApplication().copy(status = OriginationState.SUBMITTED)
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        stubClaim()

        val result = service.advance(app.id, "officer-1").await().indefinitely()

        assertThat(result.status).isEqualTo(OriginationState.KYC_PENDING)
        verifyClaims(1)
    }

    /**
     * The half `OriginationConcurrentAdvanceIT` can only observe indirectly (issue #3850). The
     * service computes the transition BEFORE it writes, so when the conditional UPDATE claims no
     * row it is already holding a fully-advanced application — it must refuse, and it must not emit
     * the evidence or signal the workflow for a step it did not take. `events.emit` is stubbed here,
     * so a regression that emitted anyway would be caught by the verify rather than by an error.
     */
    @Test
    fun `losing the advance race refuses and emits no transition evidence`() {
        val app = proposedApplication().copy(status = OriginationState.SUBMITTED)
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        stubClaim(claimed = 0)

        assertThatThrownBy { service.advance(app.id, "officer-1").await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no longer in SUBMITTED")

        verify(exactly = 0) { events.emit(any<LendingOutboxMessage>()) }
    }

    @Test
    fun `advance from ASSESSMENT reaches DECISION_PENDING and cannot skip four-eyes`() {
        val app = proposedApplication().copy(status = OriginationState.ASSESSMENT)
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        stubClaim()

        val first = service.advance(app.id, "officer-1").await().indefinitely()
        assertThat(first.status).isEqualTo(OriginationState.DECISION_PENDING)

        every { applications.findById(app.id) } returns Uni.createFrom().item(first)
        val second = service.advance(app.id, "officer-1").await().indefinitely()
        assertThat(second.status).isEqualTo(OriginationState.FOUR_EYES)
    }

    @Test
    fun `advance refuses a terminal state`() {
        val app = proposedApplication().copy(status = OriginationState.DISBURSED)
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)

        assertThatThrownBy { service.advance(app.id, "officer-1").await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `advance emits the canonical transition evidence into the outbox (ADR-0214)`() {
        val app = proposedApplication().copy(status = OriginationState.SUBMITTED, packVersion = 1)
        val evidenceSlot: CapturingSlot<LendingOutboxMessage> = slot()
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        stubClaim()
        every { events.emit(capture(evidenceSlot)) } returns Uni.createFrom().item(Unit)

        service.advance(app.id, "officer-1").await().indefinitely()

        assertThat(evidenceSlot.captured.eventType).isEqualTo("credit.application.transition")
        assertThat(evidenceSlot.captured.payload).contains("\"fromState\":\"SUBMITTED\"")
            .contains("\"toState\":\"KYC_PENDING\"")
            .contains("\"actorId\":\"officer-1\"")
            .contains("\"packVersion\":1")
            .contains("\"correlationId\":\"${app.id.value}\"")
            .contains("\"sourceService\":\"lending\"")
    }

    @Test
    fun `decide emits four-eyes evidence with the checker identity`() {
        val app = proposedApplication(proposer = "alice")
        val evidenceSlot: CapturingSlot<LendingOutboxMessage> = slot()
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        stubClaim()
        every { events.emit(capture(evidenceSlot)) } returns Uni.createFrom().item(Unit)

        service.decide(app.id, DecisionRequest(approve = true, reason = "solid affordability"), "bob")
            .await().indefinitely()

        assertThat(evidenceSlot.captured.payload).contains("\"toState\":\"OFFERED\"")
            .contains("\"actorId\":\"bob\"")
            .contains("solid affordability")
    }

    @Test
    fun `advance from ASSESSMENT runs the deterministic engine and records an APPROVE with band`() {
        val app = proposedApplication().copy(
            status = OriginationState.ASSESSMENT,
            verifiedIncomeMonthly = eur("50000.00"),
            ageYears = 35,
            residency = "CZ",
            jurisdiction = "CZ",
            productType = "CONSUMER_CREDIT",
            packVersion = 1,
        )
        val evidenceSlot = mutableListOf<LendingOutboxMessage>()
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        stubClaim()
        every { events.emit(capture(evidenceSlot)) } returns Uni.createFrom().item(Unit)

        val result = service.advance(app.id, "officer-1").await().indefinitely()

        assertThat(result.status).isEqualTo(OriginationState.DECISION_PENDING)
        assertThat(result.decisionOutcome).isEqualTo("APPROVE")
        assertThat(result.decisionPriceBand).isEqualTo("PRIME")
        assertThat(result.decisionInputHash).hasSize(64)
        assertThat(evidenceSlot.map { it.eventType }).contains("credit.decision.evaluated")

        // The claim must carry the evidence into the database, not just into the response. Until
        // `compareAndSetDecision` existed the ASSESSMENT leg claimed through `compareAndSetStatus`,
        // which writes neither outcome nor price band, so every engine column stayed NULL while
        // these very assertions passed against the in-memory copy.
        val claimed = slot<LoanApplication>()
        verify(exactly = 1) { applications.compareAndSetDecision(capture(claimed), OriginationState.ASSESSMENT) }
        assertThat(claimed.captured.decisionOutcome).isEqualTo("APPROVE")
        assertThat(claimed.captured.decisionPriceBand).isEqualTo("PRIME")
        assertThat(claimed.captured.decisionInputHash).isEqualTo(result.decisionInputHash)
        assertThat(claimed.captured.decidedEngineAt).isNotNull()
        verify(exactly = 0) { applications.compareAndSetStatus(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `advance from ASSESSMENT declines an unaffordable application (fail-closed floor)`() {
        val app = proposedApplication().copy(
            status = OriginationState.ASSESSMENT,
            verifiedIncomeMonthly = eur("2000.00"),
            ageYears = 35,
            residency = "CZ",
        )
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        stubClaim()

        val result = service.advance(app.id, "officer-1").await().indefinitely()

        assertThat(result.status).isEqualTo(OriginationState.DECLINED)
        assertThat(result.decisionOutcome).isEqualTo("DECLINE")
        assertThat(result.decisionReasons).contains("AFFORDABILITY_FAILED")
    }

    @Test
    fun `advance from ASSESSMENT with a missing input refers, never silently approves`() {
        val app = proposedApplication().copy(status = OriginationState.ASSESSMENT)
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        stubClaim()

        val result = service.advance(app.id, "officer-1").await().indefinitely()

        assertThat(result.status).isEqualTo(OriginationState.DECISION_PENDING)
        assertThat(result.decisionOutcome).isEqualTo("REFER")
        assertThat(result.decisionReasons).contains("INPUT_MISSING")
    }

    @Test
    fun `sandbox straight-through drives a fresh application to READY_TO_DISBURSE`() {
        val stp = LendingService(
            applications, loans, installments, collateral, ledger,
            valuation, riskParameters, events, clock, provisioning,
            CompliancePackGuard(CompliancePackRegistry(), clock, enforced = false),
            OriginationConfig(true),
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
        val slot: CapturingSlot<LoanApplication> = slot()
        every { applications.save(capture(slot)) } answers { Uni.createFrom().item(slot.captured) }

        val result = stp.apply(sampleRequest(), "alice").await().indefinitely()

        assertThat(result.status).isEqualTo(OriginationState.READY_TO_DISBURSE)
        assertThat(result.decidedBy).isEqualTo(OriginationConfig.SANDBOX_ACTOR)
    }

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
        status = OriginationState.FOUR_EYES,
        createdAt = fixedNow,
    )

    @Test
    fun `apply saves a SUBMITTED application`() {
        val slot: CapturingSlot<LoanApplication> = slot()
        every { applications.save(capture(slot)) } answers { Uni.createFrom().item(slot.captured) }

        val result = service.apply(sampleRequest(), "alice").await().indefinitely()

        assertThat(result.status).isEqualTo(OriginationState.SUBMITTED)
        assertThat(result.proposedBy).isEqualTo("alice")
        assertThat(result.decidedBy).isNull()
        verify(exactly = 1) { applications.save(any()) }
    }

    @Test
    fun `catalog offering overrides client price and persists immutable snapshot`() {
        val slot: CapturingSlot<LoanApplication> = slot()
        val offeringId = UUID.fromString("10000000-0000-0000-0000-000000000012")
        val snapshot = CatalogLoanSnapshot(
            offeringId,
            UUID.fromString("20000000-0000-0000-0000-000000000012"),
            "b".repeat(64),
            2,
        )
        every { catalogLoanProfiles.resolvePublished(offeringId) } returns Uni.createFrom().item(
            CatalogLoanProfile(snapshot, "EUR", 12, AmortizationMethod.ANNUITY, BigDecimal("0.0699"), null, null),
        )
        every { applications.save(capture(slot)) } answers { Uni.createFrom().item(slot.captured) }

        val result = service.apply(sampleRequest().copy(catalogOfferingId = offeringId), "alice").await().indefinitely()

        assertThat(result.nominalAnnualRate).isEqualByComparingTo("0.0699")
        assertThat(result.catalogSnapshot).isEqualTo(snapshot)
        verify(exactly = 1) { catalogLoanProfiles.resolvePublished(offeringId) }
    }

    @Test
    fun `decide rejects a four-eyes violation when approver equals proposer`() {
        val app = proposedApplication(proposer = "alice")
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)

        assertThatThrownBy {
            service.decide(app.id, DecisionRequest(approve = true), "alice").await().indefinitely()
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Four-eyes")

        verifyClaims(0)
    }

    @Test
    fun `decide approves when a different officer signs off`() {
        val app = proposedApplication(proposer = "alice")
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        stubClaim()

        val result = service.decide(app.id, DecisionRequest(approve = true), "bob").await().indefinitely()

        assertThat(result.status).isEqualTo(OriginationState.OFFERED)
        assertThat(result.decidedBy).isEqualTo("bob")
        verifyClaims(1)
    }

    private val borrowerAccountId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    /** The disbursement's happy-path customer-credit leg: an EUR CURRENT account is found and paid. */
    private fun stubBorrowerCreditSucceeds() {
        every { borrowerAccounts.findCurrentAccount(partyId, "EUR") } returns
            Uni.createFrom().item(borrowerAccountId)
        every { borrowerCredit.credit(any(), any(), any()) } returns Uni.createFrom().item(Unit)
    }

    @Test
    fun `disburse books the loan, persists a 12-row schedule and posts the disbursement`() {
        val app = proposedApplication().copy(status = OriginationState.READY_TO_DISBURSE, decidedBy = "bob")
        val rowsSlot: CapturingSlot<List<LoanInstallment>> = slot()
        val postingSlot: CapturingSlot<LedgerPosting> = slot()

        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        every { loans.save(any()) } answers { Uni.createFrom().item(firstArg<Loan>()) }
        every { installments.saveAll(capture(rowsSlot)) } answers { Uni.createFrom().item(rowsSlot.captured) }
        stubClaim()
        every { ledger.post(capture(postingSlot)) } returns Uni.createFrom().item(Unit)
        every { events.emit(any<LendingOutboxMessage>()) } returns Uni.createFrom().item(Unit)
        stubBorrowerCreditSucceeds()

        val loan = service.disburse(app.id, "dave").await().indefinitely()

        assertThat(loan.principal).isEqualTo(eur("12000.00"))
        assertThat(loan.method).isEqualTo(AmortizationMethod.ANNUITY)
        // The whole contractual schedule is persisted and closes to zero.
        assertThat(rowsSlot.captured).hasSize(12)
        assertThat(rowsSlot.captured.last().closingBalance).isEqualTo(eur("0.00"))
        // Cash leaves the bank exactly once, for the full principal — booked to the loan's own
        // internal GL accounts.
        assertThat(postingSlot.captured.amount).isEqualTo(eur("12000.00"))
        verify(exactly = 1) { ledger.post(any()) }
        // ...and separately, the borrower is actually paid: this is the fix for #3931, where the
        // ledger journal above used to be the ONLY booking a disbursement made — an asset for the
        // bank, and nothing for the customer, who ended up owing a loan they never received.
        verify(exactly = 1) { borrowerAccounts.findCurrentAccount(partyId, "EUR") }
        verify(exactly = 1) { borrowerCredit.credit(any(), borrowerAccountId, eur("12000.00")) }
        verify(exactly = 2) { events.emit(any<LendingOutboxMessage>()) }
    }

    /**
     * The ledger books the loan asset (money never mutates via `ledger.post`, so this half cannot
     * be "undone" by this fix — #3850 already tracks making the origination claim and the money
     * movement atomic together), but if the borrower has nowhere to receive the money, disbursement
     * must fail loud rather than quietly leave a loan booked with the customer unpaid.
     */
    @Test
    fun `disburse fails when the borrower has no CURRENT account to credit`() {
        val app = proposedApplication().copy(status = OriginationState.READY_TO_DISBURSE, decidedBy = "bob")
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        every { loans.save(any()) } answers { Uni.createFrom().item(firstArg<Loan>()) }
        every { installments.saveAll(any()) } answers { Uni.createFrom().item(firstArg<List<LoanInstallment>>()) }
        stubClaim()
        every { ledger.post(any()) } returns Uni.createFrom().item(Unit)
        every { borrowerAccounts.findCurrentAccount(partyId, "EUR") } returns Uni.createFrom().nullItem()

        assertThatThrownBy { service.disburse(app.id, "dave").await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no active")
            .hasMessageContaining("was not paid")

        verify(exactly = 0) { borrowerCredit.credit(any(), any(), any()) }
        // Exactly one emit, not zero: the origination-transition event ("disbursement booked")
        // already committed before the ledger post/credit even run. Only the SECOND event —
        // "loan.disbursed" — is conditional on the borrower actually getting paid.
        verify(exactly = 1) { events.emit(any<LendingOutboxMessage>()) }
        verify(exactly = 0) { events.emit(match { it.eventType == "loan.disbursed" }) }
    }

    /** The transaction-service credit call itself failing must surface, not be swallowed. */
    @Test
    fun `disburse fails when the customer-credit call itself fails`() {
        val app = proposedApplication().copy(status = OriginationState.READY_TO_DISBURSE, decidedBy = "bob")
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        every { loans.save(any()) } answers { Uni.createFrom().item(firstArg<Loan>()) }
        every { installments.saveAll(any()) } answers { Uni.createFrom().item(firstArg<List<LoanInstallment>>()) }
        stubClaim()
        every { ledger.post(any()) } returns Uni.createFrom().item(Unit)
        every { borrowerAccounts.findCurrentAccount(partyId, "EUR") } returns
            Uni.createFrom().item(borrowerAccountId)
        every { borrowerCredit.credit(any(), any(), any()) } returns
            Uni.createFrom().failure(IllegalStateException("transaction-service unavailable"))

        assertThatThrownBy { service.disburse(app.id, "dave").await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)

        // Same shape as the no-account case above: one emit (the transition already committed),
        // and specifically no "loan.disbursed" — that event is the promise the money moved.
        verify(exactly = 1) { events.emit(any<LendingOutboxMessage>()) }
        verify(exactly = 0) { events.emit(match { it.eventType == "loan.disbursed" }) }
    }

    @Test
    fun `disburse refuses an application that is not APPROVED`() {
        val app = proposedApplication() // still PROPOSED
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)

        assertThatThrownBy { service.disburse(app.id, "dave").await().indefinitely() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("READY_TO_DISBURSE")

        verify(exactly = 0) { loans.save(any()) }
    }

    @Test
    fun `disburse refuses when the disburser is the approver (segregation of duties)`() {
        val app = proposedApplication().copy(status = OriginationState.READY_TO_DISBURSE, decidedBy = "bob")
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
        // recordRepayment now loads the loan first — a WRITTEN_OFF loan must refuse a recovery (#1245).
        every { loans.findById(loanId) } returns Uni.createFrom().item(activeLoan(loanId))
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
        // recordRepayment now loads the loan first — a WRITTEN_OFF loan must refuse a recovery (#1245).
        every { loans.findById(loanId) } returns Uni.createFrom().item(activeLoan(loanId))
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

    // --- Reschedule / restructuring (issue #667/#668) ------------------------------------------------

    private fun twoInstallmentSchedule(loanId: LoanId) = listOf(
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

    private fun rescheduleRequest(forgiveness: Money = eur("0.00")) = RescheduleRequest(
        newNominalAnnualRate = BigDecimal("0.08"),
        newTermPeriods = 24,
        newFirstDueDate = firstDue.plusMonths(2),
        principalForgiveness = forgiveness,
        reason = "hardship forbearance",
    )

    private fun mockRescheduleHappyPath(loanId: LoanId, loan: Loan, schedule: List<LoanInstallment>) {
        every { loans.findById(loanId) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        every { installments.deleteUnpaid(loanId) } returns Uni.createFrom().item(1)
        every { installments.saveAll(any()) } answers {
            @Suppress("UNCHECKED_CAST")
            Uni.createFrom().item(firstArg<List<LoanInstallment>>())
        }
        every { loans.update(any()) } answers { Uni.createFrom().item(firstArg<Loan>()) }
        every { events.emit(any<LendingOutboxMessage>()) } returns Uni.createFrom().item(Unit)
    }

    @Test
    fun `reschedule replaces the unpaid tail, numbering new rows after the highest existing number`() {
        val loanId = LoanId.random()
        val loan = activeLoan(loanId)
        val schedule = twoInstallmentSchedule(loanId)
        mockRescheduleHappyPath(loanId, loan, schedule)
        val rowsSlot: CapturingSlot<List<LoanInstallment>> = slot()
        every { installments.saveAll(capture(rowsSlot)) } answers { Uni.createFrom().item(rowsSlot.captured) }

        val result = service.reschedule(loanId, rescheduleRequest(), "carol").await().indefinitely()

        assertThat(result.status).isEqualTo(LoanStatus.ACTIVE)
        verify(exactly = 1) { installments.deleteUnpaid(loanId) }
        // Continue after the HIGHEST existing number (#2), not the paid count (1). This expectation was
        // `2` and that pinned a weaker rule than the code needs: it only protected against colliding with
        // a PAID row, while an unpaid row that was already accrued has likewise posted
        // "loan:<id>:inst:<n>:accrual" — and deleteUnpaid frees its number, so UNIQUE(loan_id, number)
        // does not catch the recycle. The replacement row's own accrual then collapses into the discarded
        // row's journal and the income is never posted (#1245). Never recycling any number is the only
        // rule that holds regardless of accrual state; the cost is gaps, and numbers are identifiers,
        // not a count.
        assertThat(rowsSlot.captured.first().number).isEqualTo(3)
        assertThat(rowsSlot.captured.map { it.number }).isSorted()
        assertThat(rowsSlot.captured).hasSize(24)
        verify(exactly = 0) { ledger.post(match { it.kind == PostingKind.RESCHEDULE_FORGIVENESS }) }
        verify(exactly = 1) { events.emit(match<LendingOutboxMessage> { it.eventType == "loan.rescheduled" }) }
    }

    @Test
    fun `reschedule with principal forgiveness books a RESCHEDULE_FORGIVENESS journal for exactly that amount`() {
        val loanId = LoanId.random()
        val loan = activeLoan(loanId)
        val schedule = twoInstallmentSchedule(loanId)
        mockRescheduleHappyPath(loanId, loan, schedule)
        val postingSlot: CapturingSlot<LedgerPosting> = slot()
        every { ledger.post(capture(postingSlot)) } returns Uni.createFrom().item(Unit)

        service.reschedule(loanId, rescheduleRequest(forgiveness = eur("1000.00")), "carol").await().indefinitely()

        assertThat(postingSlot.captured.kind).isEqualTo(PostingKind.RESCHEDULE_FORGIVENESS)
        assertThat(postingSlot.captured.amount).isEqualTo(eur("1000.00"))
        verify(exactly = 1) { ledger.post(any()) }
    }

    @Test
    fun `reschedule persists a bumped loan version so a repeat reschedule never reuses the same idempotency key`() {
        val loanId = LoanId.random()
        val loan = activeLoan(loanId)
        val schedule = twoInstallmentSchedule(loanId)
        mockRescheduleHappyPath(loanId, loan, schedule)
        every { ledger.post(any()) } returns Uni.createFrom().item(Unit)
        val loanSlot: CapturingSlot<Loan> = slot()
        every { loans.update(capture(loanSlot)) } answers { Uni.createFrom().item(loanSlot.captured) }

        service.reschedule(loanId, rescheduleRequest(forgiveness = eur("1000.00")), "carol").await().indefinitely()

        assertThat(loanSlot.captured.version).isEqualTo(loan.version + 1)
    }

    @Test
    fun `reschedule refuses a loan that is not ACTIVE`() {
        val loanId = LoanId.random()
        every { loans.findById(loanId) } returns
            Uni.createFrom().item(activeLoan(loanId).copy(status = LoanStatus.WRITTEN_OFF))

        assertThatThrownBy {
            service.reschedule(loanId, rescheduleRequest(), "carol").await().indefinitely()
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("ACTIVE")

        verify(exactly = 0) { installments.deleteUnpaid(any()) }
    }

    @Test
    fun `reschedule refuses a fully-repaid loan with nothing outstanding`() {
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
            service.reschedule(loanId, rescheduleRequest(), "carol").await().indefinitely()
        }.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("Nothing to reschedule")

        verify(exactly = 0) { installments.deleteUnpaid(any()) }
    }

    @Test
    fun `reschedule refuses forgiveness that exceeds the outstanding balance`() {
        val loanId = LoanId.random()
        val loan = activeLoan(loanId)
        val schedule = twoInstallmentSchedule(loanId)
        every { loans.findById(loanId) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)

        assertThatThrownBy {
            service.reschedule(loanId, rescheduleRequest(forgiveness = eur("99999.00")), "carol")
                .await().indefinitely()
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("exceeds outstanding")

        verify(exactly = 0) { ledger.post(any()) }
        verify(exactly = 0) { installments.deleteUnpaid(any()) }
    }

    @Test
    fun `reschedule refuses a blank rescheduler identity`() {
        val loanId = LoanId.random()
        every { loans.findById(loanId) } returns Uni.createFrom().item(activeLoan(loanId))

        assertThatThrownBy {
            service.reschedule(loanId, rescheduleRequest(), "").await().indefinitely()
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("identity is required")
    }

    @Test
    fun `reschedule refuses a non-positive new term`() {
        val loanId = LoanId.random()
        every { loans.findById(loanId) } returns Uni.createFrom().item(activeLoan(loanId))

        assertThatThrownBy {
            service.reschedule(loanId, rescheduleRequest().copy(newTermPeriods = 0), "carol").await().indefinitely()
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("at least one period")
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
                modelVersion = "test-model-v1",
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

    // APPROVED by default: these tests validate the LGD math, not the four-eyes gate (that is covered
    // separately below, "Collateral four-eyes" — ADR-0028 follow-up, issue #621).
    private fun collateralItem(
        loanId: LoanId,
        marketValue: Money,
        haircut: BigDecimal,
        type: CollateralType,
        status: CollateralStatus = CollateralStatus.APPROVED,
    ) = Collateral(
        loanId = loanId,
        type = type,
        marketValue = marketValue,
        haircut = haircut,
        valuedAt = fixedNow,
        status = status,
        registeredBy = "officer-1",
        decidedBy = if (status == CollateralStatus.PENDING) null else "risk-1",
        decidedAt = if (status == CollateralStatus.PENDING) null else fixedNow,
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
                modelVersion = "test-model-v1",
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
                modelVersion = "test-model-v1",
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
                modelVersion = "test-model-v1",
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
                modelVersion = "test-model-v1",
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

    @Test
    fun `PENDING collateral is not consulted by the ECL calc`() {
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
                modelVersion = "test-model-v1",
            ),
        )
        // Fully-covering collateral, but still PENDING (maker registered it, no checker decided yet):
        // must NOT reduce LGD -- same ECL as if no collateral were registered at all (108.00).
        every { collateral.findByLoan(loanId) } returns Uni.createFrom().item(
            listOf(
                collateralItem(
                    loanId,
                    eur("15000.00"),
                    BigDecimal("0.20"),
                    CollateralType.REAL_ESTATE,
                    status = CollateralStatus.PENDING,
                ),
            ),
        )

        val snapshot = service.assess(loanId, LocalDate.parse("2026-06-01")).await().indefinitely()

        assertThat(snapshot.expectedCreditLoss).isEqualTo(eur("108.00"))
    }

    @Test
    fun `REJECTED collateral is not consulted by the ECL calc`() {
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
                modelVersion = "test-model-v1",
            ),
        )
        every { collateral.findByLoan(loanId) } returns Uni.createFrom().item(
            listOf(
                collateralItem(
                    loanId,
                    eur("15000.00"),
                    BigDecimal("0.20"),
                    CollateralType.REAL_ESTATE,
                    status = CollateralStatus.REJECTED,
                ),
            ),
        )

        val snapshot = service.assess(loanId, LocalDate.parse("2026-06-01")).await().indefinitely()

        assertThat(snapshot.expectedCreditLoss).isEqualTo(eur("108.00"))
    }

    @Test
    fun `a mix of APPROVED and PENDING collateral only sums the APPROVED item`() {
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
                modelVersion = "test-model-v1",
            ),
        )
        // APPROVED vehicle (3000.00 cover) + a PENDING real-estate item that would fully cover the
        // exposure on its own -- only the approved 3000.00 must count.
        every { collateral.findByLoan(loanId) } returns Uni.createFrom().item(
            listOf(
                collateralItem(loanId, eur("5000.00"), BigDecimal("0.40"), CollateralType.VEHICLE),
                collateralItem(
                    loanId,
                    eur("15000.00"),
                    BigDecimal("0.20"),
                    CollateralType.REAL_ESTATE,
                    status = CollateralStatus.PENDING,
                ),
            ),
        )

        val snapshot = service.assess(loanId, LocalDate.parse("2026-06-01")).await().indefinitely()

        // Coverage ratio = 3000/12000 = 0.25 -> effective LGD = 0.45 - 0.25 = 0.20.
        // ECL = 0.02 * 0.20 * 12000 = 48.00.
        assertThat(snapshot.expectedCreditLoss).isEqualTo(eur("48.00"))
    }

    // --- Collateral four-eyes (ADR-0028 follow-up, issue #621) --------------------------------------

    @Test
    fun `register captures the maker and defaults to PENDING`() {
        val loanId = LoanId.random()
        val request = CollateralRequest(
            type = CollateralType.VEHICLE,
            marketValue = eur("5000.00"),
            haircut = BigDecimal("0.40"),
        )
        every { valuation.revalue("VEHICLE", eur("5000.00")) } returns Uni.createFrom().item(eur("5000.00"))
        val saved = slot<Collateral>()
        every { collateral.save(capture(saved)) } answers { Uni.createFrom().item(saved.captured) }

        val result = service.register(loanId, request, "officer-1").await().indefinitely()

        assertThat(result.status).isEqualTo(CollateralStatus.PENDING)
        assertThat(result.registeredBy).isEqualTo("officer-1")
        assertThat(result.decidedBy).isNull()
        assertThat(saved.captured.status).isEqualTo(CollateralStatus.PENDING)
        assertThat(saved.captured.registeredBy).isEqualTo("officer-1")
    }

    @Test
    fun `register rejects a blank registrant identity`() {
        val loanId = LoanId.random()
        val request = CollateralRequest(type = CollateralType.VEHICLE, marketValue = eur("5000.00"))

        assertThatThrownBy { service.register(loanId, request, "").await().indefinitely() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a checker distinct from the maker can approve a pending collateral registration`() {
        val id = CollateralId.random()
        val loanId = LoanId.random()
        val pending = collateralItem(loanId, eur("5000.00"), BigDecimal("0.40"), CollateralType.VEHICLE)
            .copy(id = id, status = CollateralStatus.PENDING, registeredBy = "officer-1", decidedBy = null)
        every { collateral.findById(id) } returns Uni.createFrom().item(pending)
        val updated = slot<Collateral>()
        every { collateral.update(capture(updated)) } answers { Uni.createFrom().item(updated.captured) }

        val result = service.decide(id, CollateralDecisionRequest(approve = true), "risk-1").await().indefinitely()

        assertThat(result.status).isEqualTo(CollateralStatus.APPROVED)
        assertThat(result.decidedBy).isEqualTo("risk-1")
    }

    @Test
    fun `a checker can reject a pending collateral registration`() {
        val id = CollateralId.random()
        val loanId = LoanId.random()
        val pending = collateralItem(loanId, eur("5000.00"), BigDecimal("0.40"), CollateralType.VEHICLE)
            .copy(id = id, status = CollateralStatus.PENDING, registeredBy = "officer-1", decidedBy = null)
        every { collateral.findById(id) } returns Uni.createFrom().item(pending)
        val updated = slot<Collateral>()
        every { collateral.update(capture(updated)) } answers { Uni.createFrom().item(updated.captured) }

        val result = service.decide(id, CollateralDecisionRequest(approve = false), "risk-1").await().indefinitely()

        assertThat(result.status).isEqualTo(CollateralStatus.REJECTED)
    }

    @Test
    fun `a maker cannot approve their own collateral registration (four-eyes)`() {
        val id = CollateralId.random()
        val loanId = LoanId.random()
        val pending = collateralItem(loanId, eur("5000.00"), BigDecimal("0.40"), CollateralType.VEHICLE)
            .copy(id = id, status = CollateralStatus.PENDING, registeredBy = "officer-1", decidedBy = null)
        every { collateral.findById(id) } returns Uni.createFrom().item(pending)

        assertThatThrownBy {
            service.decide(id, CollateralDecisionRequest(approve = true), "officer-1").await().indefinitely()
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Four-eyes violation")
        verify(exactly = 0) { collateral.update(any()) }
    }

    @Test
    fun `decide fails for an unknown collateral id`() {
        val id = CollateralId.random()
        every { collateral.findById(id) } returns Uni.createFrom().nullItem()

        assertThatThrownBy {
            service.decide(id, CollateralDecisionRequest(approve = true), "risk-1").await().indefinitely()
        }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `decide fails for a collateral registration that already has a decision`() {
        val id = CollateralId.random()
        val loanId = LoanId.random()
        val alreadyApproved = collateralItem(loanId, eur("5000.00"), BigDecimal("0.40"), CollateralType.VEHICLE)
            .copy(id = id, status = CollateralStatus.APPROVED, registeredBy = "officer-1", decidedBy = "risk-1")
        every { collateral.findById(id) } returns Uni.createFrom().item(alreadyApproved)

        assertThatThrownBy {
            service.decide(id, CollateralDecisionRequest(approve = true), "risk-2").await().indefinitely()
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not awaiting a decision")
    }

    @Test
    fun `decide rejects a blank decider identity`() {
        val id = CollateralId.random()
        val loanId = LoanId.random()
        val pending = collateralItem(loanId, eur("5000.00"), BigDecimal("0.40"), CollateralType.VEHICLE)
            .copy(id = id, status = CollateralStatus.PENDING, registeredBy = "officer-1", decidedBy = null)
        every { collateral.findById(id) } returns Uni.createFrom().item(pending)

        assertThatThrownBy {
            service.decide(id, CollateralDecisionRequest(approve = true), "").await().indefinitely()
        }
            .isInstanceOf(IllegalArgumentException::class.java)
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
                modelVersion = "test-model-v1",
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
            modelVersion = "test-model-v1",
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
            modelVersion = "test-model-v1",
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
            modelVersion = "test-model-v1",
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
        // Due 2026-06-30, assessed 40 days later => DPD 40 > 30-day SICR threshold => Stage 2 (prior: Stage 1).
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
            modelVersion = "test-model-v1",
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

        // single{} asserts exactly one stage_changed event was emitted.
        val payload = emitted.single { it.eventType == "loan.stage_changed" }.payload
        assertThat(payload).contains(""""loanId":"${loanId.value}"""")
        assertThat(payload).contains(""""previousStage":"STAGE_1"""")
        assertThat(payload).contains(""""newStage":"STAGE_2"""")
        assertThat(payload).contains(""""daysPastDue":40""")
        // ADR-0220 D1's vulnerable-customer exclusion needs WHOSE loan moved stage: without a
        // partyId a consumer must call back on the app-open hot path, so a dropped partyId makes
        // the arrears feed unusable.
        assertThat(payload)
            .describedAs("adverse-state consumers key on partyId; without it this event is unusable to them")
            .contains(""""partyId":"${loan.partyId}"""")
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
            modelVersion = "test-model-v1",
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
            modelVersion = "test-model-v1",
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

    // --- #3914: business event time on the six payloads that carried none -------------------------
    //
    // Without `occurredAt`, `AuditConsumer.eventTime()` returns null and `audit_entries.occurred_at`
    // stores the CONSUMER's ingest time as the business time (GDPR Art. 30 "when", DORA Art. 17
    // evidence) — arbitrarily wrong under consumer lag or a replay.
    //
    // Every assertion is an EXACT expected instant, parsed back out of the JSON. Never
    // `isNotNull()`: that passes against `Instant.EPOCH`. Never a substring/emptiness check on the
    // raw text: Jackson's `asText()` yields the four-character string "null" for a JSON null, which
    // passes every existence check. The `Instant.parse` round-trip also proves the value is in the
    // Z-normalised form the consumer can actually parse.

    private val expectedEventTime: Instant = Instant.parse("2024-01-01T00:00:00Z")

    private fun occurredAtOf(message: LendingOutboxMessage): Instant = Instant.parse(
        com.fasterxml.jackson.databind.ObjectMapper().readTree(message.payload).get("occurredAt").asText(),
    )

    @Test
    fun `loan disbursed carries the disbursement instant as occurredAt`() {
        val app = proposedApplication().copy(status = OriginationState.READY_TO_DISBURSE, decidedBy = "bob")
        val emitted = mutableListOf<LendingOutboxMessage>()
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        every { loans.save(any()) } answers { Uni.createFrom().item(firstArg<Loan>()) }
        every { installments.saveAll(any()) } answers { Uni.createFrom().item(firstArg<List<LoanInstallment>>()) }
        stubClaim()
        every { ledger.post(any()) } returns Uni.createFrom().item(Unit)
        every { events.emit(capture(emitted)) } returns Uni.createFrom().item(Unit)
        stubBorrowerCreditSucceeds()

        val loan = service.disburse(app.id, "dave").await().indefinitely()

        val disbursed = emitted.single { it.eventType == "loan.disbursed" }
        assertThat(occurredAtOf(disbursed)).isEqualTo(loan.disbursedAt.toInstant())
        assertThat(occurredAtOf(disbursed)).isEqualTo(expectedEventTime)
    }

    @Test
    fun `loan interest_accrued carries the accrual instant as occurredAt`() {
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
        )
        val emitted = mutableListOf<LendingOutboxMessage>()
        every { installments.findAccruable(any(), any()) } returns Uni.createFrom().item(due)
        every { installments.markAccrued(any(), any()) } returns Uni.createFrom().item(1)
        every { ledger.post(any()) } returns Uni.createFrom().item(Unit)
        every { events.emit(capture(emitted)) } returns Uni.createFrom().item(Unit)

        service.accrueDueInterest(LocalDate.parse("2026-08-01"), 500).await().indefinitely()

        assertThat(occurredAtOf(emitted.single { it.eventType == "loan.interest_accrued" }))
            .isEqualTo(expectedEventTime)
    }

    @Test
    fun `loan written_off carries the derecognition instant as occurredAt`() {
        val loanId = LoanId.random()
        val loan = activeLoan(loanId)
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
        val emitted = mutableListOf<LendingOutboxMessage>()
        every { loans.findById(loanId) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        every { ledger.post(any()) } returns Uni.createFrom().item(Unit)
        every { loans.update(any()) } answers { Uni.createFrom().item(firstArg<Loan>()) }
        every { events.emit(capture(emitted)) } returns Uni.createFrom().item(Unit)

        service.writeOff(loanId, WriteOffRequest(writtenOffBy = "carol", reason = "insolvency"))
            .await().indefinitely()

        assertThat(occurredAtOf(emitted.single { it.eventType == "loan.written_off" }))
            .isEqualTo(expectedEventTime)
    }

    @Test
    fun `loan rescheduled carries the reschedule instant as occurredAt`() {
        val loanId = LoanId.random()
        val loan = activeLoan(loanId)
        val schedule = twoInstallmentSchedule(loanId)
        mockRescheduleHappyPath(loanId, loan, schedule)
        val emitted = mutableListOf<LendingOutboxMessage>()
        every { installments.saveAll(any()) } answers { Uni.createFrom().item(firstArg<List<LoanInstallment>>()) }
        every { events.emit(capture(emitted)) } returns Uni.createFrom().item(Unit)

        service.reschedule(loanId, rescheduleRequest(), "carol").await().indefinitely()

        assertThat(occurredAtOf(emitted.single { it.eventType == "loan.rescheduled" }))
            .isEqualTo(expectedEventTime)
    }

    @Test
    fun `loan stage_changed and loan provisioned carry the provisioning-cycle instant as occurredAt`() {
        val loanId = LoanId.random()
        val loan = activeLoan(loanId)
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
            modelVersion = "test-model-v1",
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

        // Both events describe the same provisioning cycle, so both carry that cycle's instant —
        // NOT `asOf`, which is the accounting DATE and a different fact.
        assertThat(occurredAtOf(emitted.single { it.eventType == "loan.stage_changed" }))
            .isEqualTo(expectedEventTime)
        assertThat(occurredAtOf(emitted.single { it.eventType == "loan.provisioned" }))
            .isEqualTo(expectedEventTime)
    }

    // --- sourceService (issue #3994/#5256, fleet follow-up to #5255 and the eighteen prior slices) ---
    //
    // `sourceService` is the strongest (EVENT-sourced) attribution `AuditConsumer.resolveSourceService`
    // reads. `EventAttribution.TopicAttribution` already maps `openbank.lending.events` ->
    // `lending-service` correctly, but only as TOPIC-sourced — and audit-service subscribes to that
    // topic today (`openbank-audit-service`'s `application.yaml` consumed-topics list; all nine lending
    // event types share this single outbox channel/topic, `KafkaLendingOutboxEventPublisher`'s
    // `lending-events-out` -> `openbank.lending.events`), so this is a live attribution upgrade for
    // every event type below, not a forward-looking one. lending-service is a money-path service
    // (`rules.yaml: money_path_services`).
    //
    // The literal value is `"lending"`, matching the 3 event types that already carried it before this
    // PR (`credit.application.transition`, `credit.decision.evaluated`, `credit.loan.transition`) —
    // not `"lending-service"`, the string `EventAttribution`'s topic-fallback table uses for this
    // producer. That is a pre-existing inconsistency this PR preserves rather than introduces: every
    // lending-service event now agrees with every OTHER lending-service event, which is a strictly
    // better state than adding a second, different self-reported string for these six.

    private fun sourceServiceOf(message: LendingOutboxMessage): String =
        com.fasterxml.jackson.databind.ObjectMapper().readTree(message.payload).get("sourceService").asText()

    @Test
    fun `loan disbursed carries sourceService on the wire`() {
        val app = proposedApplication().copy(status = OriginationState.READY_TO_DISBURSE, decidedBy = "bob")
        val emitted = mutableListOf<LendingOutboxMessage>()
        every { applications.findById(app.id) } returns Uni.createFrom().item(app)
        every { loans.save(any()) } answers { Uni.createFrom().item(firstArg<Loan>()) }
        every { installments.saveAll(any()) } answers { Uni.createFrom().item(firstArg<List<LoanInstallment>>()) }
        stubClaim()
        every { ledger.post(any()) } returns Uni.createFrom().item(Unit)
        every { events.emit(capture(emitted)) } returns Uni.createFrom().item(Unit)
        stubBorrowerCreditSucceeds()

        service.disburse(app.id, "dave").await().indefinitely()

        val disbursed = emitted.single { it.eventType == "loan.disbursed" }
        assertThat(sourceServiceOf(disbursed)).isEqualTo("lending")
        assertThat(disbursed.payload).contains("\"sourceService\":\"lending\"")
    }

    @Test
    fun `loan interest_accrued carries sourceService on the wire`() {
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
        )
        val emitted = mutableListOf<LendingOutboxMessage>()
        every { installments.findAccruable(any(), any()) } returns Uni.createFrom().item(due)
        every { installments.markAccrued(any(), any()) } returns Uni.createFrom().item(1)
        every { ledger.post(any()) } returns Uni.createFrom().item(Unit)
        every { events.emit(capture(emitted)) } returns Uni.createFrom().item(Unit)

        service.accrueDueInterest(LocalDate.parse("2026-08-01"), 500).await().indefinitely()

        assertThat(sourceServiceOf(emitted.single { it.eventType == "loan.interest_accrued" }))
            .isEqualTo("lending")
    }

    @Test
    fun `loan written_off carries sourceService on the wire`() {
        val loanId = LoanId.random()
        val loan = activeLoan(loanId)
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
        val emitted = mutableListOf<LendingOutboxMessage>()
        every { loans.findById(loanId) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        every { ledger.post(any()) } returns Uni.createFrom().item(Unit)
        every { loans.update(any()) } answers { Uni.createFrom().item(firstArg<Loan>()) }
        every { events.emit(capture(emitted)) } returns Uni.createFrom().item(Unit)

        service.writeOff(loanId, WriteOffRequest(writtenOffBy = "carol", reason = "insolvency"))
            .await().indefinitely()

        assertThat(sourceServiceOf(emitted.single { it.eventType == "loan.written_off" }))
            .isEqualTo("lending")
    }

    @Test
    fun `loan rescheduled carries sourceService on the wire`() {
        val loanId = LoanId.random()
        val loan = activeLoan(loanId)
        val schedule = twoInstallmentSchedule(loanId)
        mockRescheduleHappyPath(loanId, loan, schedule)
        val emitted = mutableListOf<LendingOutboxMessage>()
        every { installments.saveAll(any()) } answers { Uni.createFrom().item(firstArg<List<LoanInstallment>>()) }
        every { events.emit(capture(emitted)) } returns Uni.createFrom().item(Unit)

        service.reschedule(loanId, rescheduleRequest(), "carol").await().indefinitely()

        assertThat(sourceServiceOf(emitted.single { it.eventType == "loan.rescheduled" }))
            .isEqualTo("lending")
    }

    @Test
    fun `loan stage_changed and loan provisioned carry sourceService on the wire`() {
        val loanId = LoanId.random()
        val loan = activeLoan(loanId)
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
            modelVersion = "test-model-v1",
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

        assertThat(sourceServiceOf(emitted.single { it.eventType == "loan.stage_changed" }))
            .isEqualTo("lending")
        assertThat(sourceServiceOf(emitted.single { it.eventType == "loan.provisioned" }))
            .isEqualTo("lending")
    }
}
