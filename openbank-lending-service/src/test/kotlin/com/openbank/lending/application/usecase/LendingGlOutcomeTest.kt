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
import com.openbank.lending.application.port.out.ProvisioningRepository
import com.openbank.lending.application.port.out.RiskParameterSource
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.lending.domain.model.RescheduleRequest
import com.openbank.lending.domain.model.WriteOffRequest
import com.openbank.lending.infrastructure.client.LendingGlAccounts
import com.openbank.lending.infrastructure.client.LendingJournalFactory
import com.openbank.lending.infrastructure.compliance.CompliancePackGuard
import com.openbank.lending.infrastructure.compliance.OriginationConfig
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.libs.lending.compliance.CompliancePackRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.smallrye.mutiny.Uni
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * ECONOMIC-OUTCOME tests: assert the GL balances a flow produces, not which journals it emitted.
 *
 * WHY THIS FILE EXISTS. #1236 shipped a reschedule that silently forgave already-earned interest, and
 * every one of its tests passed. They asserted *mechanics* — posting kinds, ordering, reference strings
 * — which the defective code satisfied perfectly. No test asked the only question that matters: after
 * this flow, what does the general ledger say? That question fails the defect immediately (#1245).
 *
 * The harness replays captured postings through the real [LendingJournalFactory] — the same mapping
 * production uses — and sums debits/credits per account. A test asserting `interestIncome == 110.54`
 * cannot be satisfied by emitting the right *kind* of journal; only by getting the economics right.
 *
 * Add a case here for every new [com.openbank.lending.application.port.out.PostingKind], not just a
 * mapping assertion in LendingJournalFactoryTest.
 */
class LendingGlOutcomeTest {

    private val applications = mockk<LoanApplicationRepository>()
    private val loans = mockk<LoanRepository>()
    private val installments = mockk<InstallmentRepository>()
    private val collateral = mockk<CollateralRepository>()
    private val ledger = mockk<LedgerPostingPort>()
    private val valuation = mockk<CollateralValuationPort>()
    private val riskParameters = mockk<RiskParameterSource>()
    private val events = mockk<LoanEventEmitter>()
    private val clock = Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC)
    private val provisioning = mockk<ProvisioningRepository>()

    private val service = LendingService(
        applications, loans, installments, collateral, ledger,
        valuation, riskParameters, events, clock, provisioning,
        CompliancePackGuard(CompliancePackRegistry(), clock, enforced = false),
        OriginationConfig(false),
    )

    private val partyId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private fun eur(v: String) = Money.of(v, "EUR")

    // --- the GL harness --------------------------------------------------------------------------

    private val gl = LendingGlAccounts(
        loansReceivable = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"),
        fundingClearing = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002"),
        interestIncome = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003"),
        interestReceivable = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000004"),
        loanLossExpense = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000005"),
        loanLossAllowance = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000006"),
    )

    /**
     * Replay postings through the production journal factory and net each account.
     * Debit-positive: a debit adds, a credit subtracts — so an asset/expense reads positive when it has
     * a debit balance, and income reads NEGATIVE when it has been earned (credit balance). Stated
     * explicitly so an assertion's sign can never be mistaken for a bug in the harness.
     */
    private fun ledgerBalances(postings: List<LedgerPosting>): Map<UUID, BigDecimal> {
        val balances = mutableMapOf<UUID, BigDecimal>()
        postings.forEach { posting ->
            LendingJournalFactory.buildLines(posting, gl).forEach { line ->
                val signed = if (line.side == "DEBIT") line.amount else line.amount.negate()
                balances.merge(line.glAccountId, signed, BigDecimal::add)
            }
        }
        return balances
    }

    private fun Map<UUID, BigDecimal>.of(account: UUID): BigDecimal = this[account] ?: BigDecimal.ZERO

    /** Every journal the factory builds must self-balance; a flow's postings must net to zero overall. */
    private fun assertDoubleEntryHolds(balances: Map<UUID, BigDecimal>) {
        val net = balances.values.fold(BigDecimal.ZERO, BigDecimal::add)
        assertThat(net.compareTo(BigDecimal.ZERO))
            .describedAs("debits minus credits across all accounts must be zero, was $net")
            .isEqualTo(0)
    }

    // --- fixtures --------------------------------------------------------------------------------

    private val loanId = LoanId(UUID.fromString("22222222-2222-2222-2222-222222222222"))

    private val bookedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z")

    private fun activeLoan() = Loan(
        id = loanId,
        applicationId = LoanApplicationId.random(),
        partyId = partyId,
        principal = eur("12000.00"),
        nominalAnnualRate = BigDecimal("0.12"),
        termPeriods = 12,
        periodsPerYear = 12,
        method = AmortizationMethod.ANNUITY,
        firstDueDate = LocalDate.parse("2026-01-31"),
        status = LoanStatus.ACTIVE,
        disbursedAt = bookedAt,
        version = 0,
        createdAt = bookedAt,
    )

    /**
     * #1 paid; #2 fell due 2026-02-28 and its interest was ACCRUED but never collected; #3.. still future.
     * This is a delinquent loan — the normal forbearance and write-off case, and the exact shape the
     * accrued-interest defects live in.
     */
    private fun delinquentSchedule() = listOf(
        LoanInstallment(
            loanId = loanId, number = 1, dueDate = LocalDate.parse("2026-01-31"),
            openingBalance = eur("12000.00"), principal = eur("946.19"), interest = eur("120.00"),
            payment = eur("1066.19"), closingBalance = eur("11053.81"),
            paid = true, interestAccrued = true,
        ),
        LoanInstallment(
            loanId = loanId, number = 2, dueDate = LocalDate.parse("2026-02-28"),
            openingBalance = eur("11053.81"), principal = eur("955.65"), interest = eur("110.54"),
            payment = eur("1066.19"), closingBalance = eur("10098.16"),
            paid = false, interestAccrued = true,
        ),
        LoanInstallment(
            loanId = loanId, number = 3, dueDate = LocalDate.parse("2026-03-31"),
            openingBalance = eur("10098.16"), principal = eur("965.21"), interest = eur("100.98"),
            payment = eur("1066.19"), closingBalance = eur("9132.95"),
            paid = false, interestAccrued = false,
        ),
    )

    private fun captureLedger(): MutableList<LedgerPosting> {
        val captured = mutableListOf<LedgerPosting>()
        val s = slot<LedgerPosting>()
        every { ledger.post(capture(s)) } answers {
            captured += s.captured
            Uni.createFrom().item(Unit)
        }
        return captured
    }

    // --- write-off ------------------------------------------------------------------------------

    @Test
    fun `write-off derecognizes principal AND the accrued receivable, and keeps the earned income`() {
        val loan = activeLoan()
        val schedule = delinquentSchedule()
        every { loans.findById(loanId) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        every { loans.update(any()) } answers { Uni.createFrom().item(firstArg<Loan>()) }
        every { events.emit(any<LendingOutboxMessage>()) } returns Uni.createFrom().item(Unit)
        val postings = captureLedger()

        service.writeOff(loanId, WriteOffRequest(writtenOffBy = "risk-officer")).await().indefinitely()

        val b = ledgerBalances(postings)
        assertDoubleEntryHolds(b)

        // Principal (opening balance of the first unpaid installment) + the accrued interest both leave.
        assertThat(b.of(gl.loansReceivable)).isEqualByComparingTo("-11053.81")
        assertThat(b.of(gl.interestReceivable))
            .describedAs("the accrued receivable must not be orphaned on a written-off loan")
            .isEqualByComparingTo("-110.54")

        // The loss carries BOTH components.
        assertThat(b.of(gl.loanLossExpense)).isEqualByComparingTo("11164.35")

        // The income stays earned: an installment can only accrue once it has fallen due.
        assertThat(b.of(gl.interestIncome))
            .describedAs("write-off is a collection failure, not a revenue reversal")
            .isEqualByComparingTo("0.00")
    }

    @Test
    fun `write-off posts one interest journal per accrued installment, not one aggregate`() {
        val loan = activeLoan()
        every { loans.findById(loanId) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(delinquentSchedule())
        every { loans.update(any()) } answers { Uni.createFrom().item(firstArg<Loan>()) }
        every { events.emit(any<LendingOutboxMessage>()) } returns Uni.createFrom().item(Unit)
        val postings = captureLedger()

        service.writeOff(loanId, WriteOffRequest(writtenOffBy = "risk-officer")).await().indefinitely()

        // An aggregate key over a summed amount is unsafe: the loan stays ACTIVE until loans.update, so
        // the accrual scheduler can add a row between a crash and the retry, and the ledger — which
        // dedupes on the key without comparing payloads — would silently keep the smaller sum.
        assertThat(postings.map { it.reference })
            .contains("loan:${loanId.value}:inst:2:writeoff-interest")
            .doesNotContain("loan:${loanId.value}:writeoff:interest")
    }

    @Test
    fun `a recovery on a written-off loan is refused, not booked against the derecognized asset`() {
        val schedule = delinquentSchedule()
        val writtenOff = activeLoan().copy(status = LoanStatus.WRITTEN_OFF)
        every { loans.findById(loanId) } returns Uni.createFrom().item(writtenOff)
        // Stub the whole downstream path ON PURPOSE, even though the guard should short-circuit before
        // reaching any of it. An earlier version stubbed only findById, so removing the guard made the
        // flow die on an unstubbed mock — the test went red, but for the WRONG reason, and
        // `postings.isEmpty()` passed vacuously without ever demonstrating the harm. With these stubs
        // the unguarded flow RUNS to completion and the assertions below fail on the real GL damage.
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        every { installments.markPaid(any(), any()) } returns Uni.createFrom().item(1)
        val postings = captureLedger()

        val failure = runCatching {
            service.recordRepayment(loanId, schedule[1].id).await().indefinitely()
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(failure?.message).contains("WRITTEN_OFF")
        assertThat(postings).describedAs("nothing may be posted for a refused recovery").isEmpty()
    }

    @Test
    fun `write-off then recovery must not drive Interest Receivable negative`() {
        val schedule = delinquentSchedule()
        val loan = activeLoan()
        every { loans.findById(loanId) } returnsMany listOf(
            Uni.createFrom().item(loan), // writeOff sees ACTIVE
            Uni.createFrom().item(loan.copy(status = LoanStatus.WRITTEN_OFF)), // the recovery attempt
        )
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        every { installments.markPaid(any(), any()) } returns Uni.createFrom().item(1)
        every { loans.update(any()) } answers { Uni.createFrom().item(firstArg<Loan>()) }
        every { events.emit(any<LendingOutboxMessage>()) } returns Uni.createFrom().item(Unit)
        val postings = captureLedger()

        // Both flows against ONE captured ledger: write off the loan, then attempt a recovery on the
        // installment whose receivable WRITE_OFF_INTEREST just derecognized. writeOff mutates no
        // installment rows, so #2 still reads paid=false/interestAccrued=true and recordRepayment would
        // post INTEREST_SETTLEMENT against a receivable that is already gone.
        service.writeOff(loanId, WriteOffRequest(writtenOffBy = "risk-officer")).await().indefinitely()
        runCatching { service.recordRepayment(loanId, schedule[1].id).await().indefinitely() }

        val b = ledgerBalances(postings)
        // THE ECONOMIC ASSERTION. Unguarded this reads -221.08: WRITE_OFF_INTEREST credits 110.54 and
        // INTEREST_SETTLEMENT credits it again against a receivable of 110.54. The idempotency keys
        // differ (`inst:2:writeoff-interest` vs `inst:2:interest`), so the ledger cannot collapse them.
        assertThat(b.of(gl.interestReceivable))
            .describedAs("a receivable may be cleared once, never twice — it cannot go below -110.54")
            .isEqualByComparingTo("-110.54")
        assertThat(b.of(gl.loansReceivable))
            .describedAs("principal is derecognized once; a recovery must not reduce it again")
            .isEqualByComparingTo("-11053.81")
    }

    // --- reschedule -----------------------------------------------------------------------------

    @Test
    fun `reschedule capitalizes accrued interest and never un-earns it`() {
        val loan = activeLoan()
        val schedule = delinquentSchedule()
        every { loans.findById(loanId) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        every { installments.deleteUnpaid(loanId) } returns Uni.createFrom().item(2)
        every { installments.saveAll(any()) } answers { Uni.createFrom().item(firstArg<List<LoanInstallment>>()) }
        every { loans.update(any()) } answers { Uni.createFrom().item(firstArg<Loan>()) }
        every { events.emit(any<LendingOutboxMessage>()) } returns Uni.createFrom().item(Unit)
        val postings = captureLedger()

        service.reschedule(
            loanId,
            RescheduleRequest(
                newNominalAnnualRate = BigDecimal("0.10"),
                newTermPeriods = 12,
                newFirstDueDate = LocalDate.parse("2026-05-31"),
                principalForgiveness = eur("0.00"),
            ),
            rescheduledBy = "risk-officer",
        ).await().indefinitely()

        val b = ledgerBalances(postings)
        assertDoubleEntryHolds(b)

        // THE REGRESSION GUARD. #1236 reversed the accrual here (Dr Interest Income), which would make
        // this -110.54: the February interest owed by nobody and earned by no one — debt relief granted
        // as a side effect, bypassing RESCHEDULE_FORGIVENESS — the one mechanism ADR-0028 gives relief,
        // so that it stays explicit and auditable.
        assertThat(b.of(gl.interestIncome))
            .describedAs("a reschedule must never un-earn interest that already fell due")
            .isEqualByComparingTo("0.00")

        // The receivable is not stranded — it moves into the restructured asset the borrower still owes.
        assertThat(b.of(gl.interestReceivable)).isEqualByComparingTo("-110.54")
        assertThat(b.of(gl.loansReceivable)).isEqualByComparingTo("110.54")

        // And no relief was granted: none was asked for.
        assertThat(b.of(gl.loanLossExpense))
            .describedAs("no forgiveness was requested, so none may be booked")
            .isEqualByComparingTo("0.00")
    }

    @Test
    fun `reschedule rolls the capitalized interest into the new principal`() {
        val loan = activeLoan()
        every { loans.findById(loanId) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(delinquentSchedule())
        every { installments.deleteUnpaid(loanId) } returns Uni.createFrom().item(2)
        val rows = slot<List<LoanInstallment>>()
        every { installments.saveAll(capture(rows)) } answers { Uni.createFrom().item(rows.captured) }
        every { loans.update(any()) } answers { Uni.createFrom().item(firstArg<Loan>()) }
        every { events.emit(any<LendingOutboxMessage>()) } returns Uni.createFrom().item(Unit)
        every { ledger.post(any()) } returns Uni.createFrom().item(Unit)

        service.reschedule(
            loanId,
            RescheduleRequest(
                newNominalAnnualRate = BigDecimal("0.10"),
                newTermPeriods = 12,
                newFirstDueDate = LocalDate.parse("2026-05-31"),
                principalForgiveness = eur("0.00"),
            ),
            rescheduledBy = "risk-officer",
        ).await().indefinitely()

        // outstanding 11053.81 (opening balance of first unpaid) + 110.54 accrued = 11164.35
        assertThat(rows.captured.first().openingBalance)
            .describedAs("the capitalized interest must be part of the restructured principal")
            .isEqualTo(eur("11164.35"))

        // Numbering continues after the HIGHEST existing number (3), not the paid count (1): recycling a
        // discarded row's number collapses the replacement's accrual into the discarded row's journal.
        assertThat(rows.captured.first().number).isEqualTo(4)
    }

    @Test
    fun `reschedule with forgiveness books relief explicitly and still capitalizes the interest`() {
        val loan = activeLoan()
        every { loans.findById(loanId) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(delinquentSchedule())
        every { installments.deleteUnpaid(loanId) } returns Uni.createFrom().item(2)
        every { installments.saveAll(any()) } answers { Uni.createFrom().item(firstArg<List<LoanInstallment>>()) }
        every { loans.update(any()) } answers { Uni.createFrom().item(firstArg<Loan>()) }
        every { events.emit(any<LendingOutboxMessage>()) } returns Uni.createFrom().item(Unit)
        val postings = captureLedger()

        service.reschedule(
            loanId,
            RescheduleRequest(
                newNominalAnnualRate = BigDecimal("0.10"),
                newTermPeriods = 12,
                newFirstDueDate = LocalDate.parse("2026-05-31"),
                principalForgiveness = eur("1000.00"),
            ),
            rescheduledBy = "risk-officer",
        ).await().indefinitely()

        val b = ledgerBalances(postings)
        assertDoubleEntryHolds(b)

        // Relief is visible and lands where relief belongs — not silently in a revenue line.
        assertThat(b.of(gl.loanLossExpense)).isEqualByComparingTo("1000.00")
        assertThat(b.of(gl.interestIncome)).isEqualByComparingTo("0.00")
        // Loans receivable: -1000.00 forgiven, +110.54 capitalized.
        assertThat(b.of(gl.loansReceivable)).isEqualByComparingTo("-889.46")
    }

    @Test
    fun `a reschedule may not backdate newFirstDueDate onto an already-accrued period`() {
        val schedule = delinquentSchedule() // #2 accrued, due 2026-02-28
        every { loans.findById(loanId) } returns Uni.createFrom().item(activeLoan())
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(schedule)
        val postings = captureLedger()

        // Without the guard the new schedule's first installment covers a period the old accrual
        // already recognized: 110.54 capitalized into newPrincipal AND ~111.64 charged again by the
        // new row #1 — 222.18 of interest for one month. Nothing rejected this before; the
        // pre-capitalization code double-charged here too, just by a smaller amount.
        val failure = runCatching {
            service.reschedule(
                loanId,
                RescheduleRequest(
                    newNominalAnnualRate = BigDecimal("0.10"),
                    newTermPeriods = 12,
                    newFirstDueDate = LocalDate.parse("2026-02-28"), // == accrued #2's dueDate
                    principalForgiveness = eur("0.00"),
                ),
                rescheduledBy = "risk-officer",
            ).await().indefinitely()
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(failure?.message).contains("already recognized")
        assertThat(postings).describedAs("a rejected reschedule posts nothing").isEmpty()
    }

    @Test
    fun `a reschedule with nothing accrued posts no capitalization`() {
        val loan = activeLoan()
        val cleanSchedule = delinquentSchedule().map { it.copy(interestAccrued = false) }
        every { loans.findById(loanId) } returns Uni.createFrom().item(loan)
        every { installments.findByLoan(loanId) } returns Uni.createFrom().item(cleanSchedule)
        every { installments.deleteUnpaid(loanId) } returns Uni.createFrom().item(2)
        every { installments.saveAll(any()) } answers { Uni.createFrom().item(firstArg<List<LoanInstallment>>()) }
        every { loans.update(any()) } answers { Uni.createFrom().item(firstArg<Loan>()) }
        every { events.emit(any<LendingOutboxMessage>()) } returns Uni.createFrom().item(Unit)
        val postings = captureLedger()

        service.reschedule(
            loanId,
            RescheduleRequest(
                newNominalAnnualRate = BigDecimal("0.10"),
                newTermPeriods = 12,
                newFirstDueDate = LocalDate.parse("2026-05-31"),
                principalForgiveness = eur("0.00"),
            ),
            rescheduledBy = "risk-officer",
        ).await().indefinitely()

        assertThat(postings).describedAs("nothing accrued, so nothing to capitalize").isEmpty()
    }
}
