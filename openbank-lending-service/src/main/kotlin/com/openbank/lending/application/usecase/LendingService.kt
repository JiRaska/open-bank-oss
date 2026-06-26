// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.lending.application.usecase

import com.openbank.lending.application.port.`in`.AccrueInterestUseCase
import com.openbank.lending.application.port.`in`.ApplyForLoanUseCase
import com.openbank.lending.application.port.`in`.CollateralUseCase
import com.openbank.lending.application.port.`in`.DisburseLoanUseCase
import com.openbank.lending.application.port.`in`.ProvisioningUseCase
import com.openbank.lending.application.port.`in`.ServicingUseCase
import com.openbank.lending.application.port.`in`.WriteOffLoanUseCase
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
import com.openbank.lending.domain.model.AccrualOutcome
import com.openbank.lending.domain.model.ApplicationStatus
import com.openbank.lending.domain.model.Collateral
import com.openbank.lending.domain.model.CollateralRequest
import com.openbank.lending.domain.model.DecisionRequest
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanApplicationRequest
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.lending.domain.model.ProvisioningSnapshot
import com.openbank.lending.domain.model.WriteOffRequest
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.Amortization
import com.openbank.libs.lending.Delinquency
import com.openbank.libs.lending.Ifrs9
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The lending bounded-context application service (ADR-0028). Orchestrates the pure `openbank-libs`
 * primitives (`Amortization`, `Ifrs9`, `Delinquency`) over persisted state, emits ledger postings
 * through the outbox, and enforces the four-eyes credit-decision rule server-side. No credit math
 * lives here — it is all in libs.
 */
@ApplicationScoped
@Suppress("LongParameterList")
class LendingService(
    private val applications: LoanApplicationRepository,
    private val loans: LoanRepository,
    private val installments: InstallmentRepository,
    private val collateral: CollateralRepository,
    private val ledger: LedgerPostingPort,
    private val valuation: CollateralValuationPort,
    private val riskParameters: RiskParameterSource,
    private val events: LoanEventEmitter,
    private val clock: Clock,
) : ApplyForLoanUseCase,
    DisburseLoanUseCase,
    ServicingUseCase,
    AccrueInterestUseCase,
    WriteOffLoanUseCase,
    CollateralUseCase,
    ProvisioningUseCase {

    // --- Origination --------------------------------------------------------------------------------

    override fun apply(request: LoanApplicationRequest, proposedBy: String): Uni<LoanApplication> {
        require(request.requestedAmount.isPositive()) { "Requested amount must be positive" }
        require(request.termPeriods > 0) { "Term must be at least one period" }
        require(request.nominalAnnualRate.signum() >= 0) { "Nominal rate cannot be negative" }
        require(proposedBy.isNotBlank()) { "Proposer identity is required" }
        val now = OffsetDateTime.now(clock)
        val application = LoanApplication(
            partyId = request.partyId,
            requestedAmount = request.requestedAmount,
            nominalAnnualRate = request.nominalAnnualRate,
            termPeriods = request.termPeriods,
            periodsPerYear = request.periodsPerYear,
            method = request.method,
            firstDueDate = request.firstDueDate,
            // Trusted: the authenticated maker, captured by the adapter from the JWT subject.
            proposedBy = proposedBy,
            createdAt = now,
        )
        return applications.save(application)
    }

    override fun decide(id: LoanApplicationId, decision: DecisionRequest, decidedBy: String): Uni<LoanApplication> =
        applications.findById(id).flatMap { existing ->
            when {
                existing == null ->
                    Uni.createFrom().failure(IllegalArgumentException("Application not found: $id"))
                existing.status != ApplicationStatus.PROPOSED ->
                    Uni.createFrom().failure(
                        IllegalStateException("Application is not awaiting a decision: ${existing.status}"),
                    )
                decidedBy.isBlank() ->
                    Uni.createFrom().failure(IllegalArgumentException("Decider identity is required"))
                // Four-eyes: the decider must not be the proposer. Both identities are the authenticated
                // JWT subject (never client-supplied), so the separation cannot be spoofed (ADR-0028 D5).
                decidedBy == existing.proposedBy ->
                    Uni.createFrom().failure(
                        IllegalStateException("Four-eyes violation: approver must differ from proposer"),
                    )
                else -> applications.update(
                    existing.copy(
                        status = if (decision.approve) ApplicationStatus.APPROVED else ApplicationStatus.REJECTED,
                        decidedBy = decidedBy,
                        decisionReason = decision.reason,
                        decidedAt = OffsetDateTime.now(clock),
                    ),
                )
            }
        }

    override fun getApplication(id: LoanApplicationId): Uni<LoanApplication?> = applications.findById(id)

    override fun listApplications(partyId: UUID): Uni<List<LoanApplication>> = applications.findByParty(partyId)

    // --- Disbursement (origination → servicing) -----------------------------------------------------

    override fun disburse(applicationId: LoanApplicationId, disbursedBy: String): Uni<Loan> =
        applications.findById(applicationId).flatMap { application ->
            when {
                application == null ->
                    Uni.createFrom().failure(IllegalArgumentException("Application not found: $applicationId"))
                application.status != ApplicationStatus.APPROVED ->
                    Uni.createFrom().failure(
                        IllegalStateException("Only an APPROVED application can be disbursed: ${application.status}"),
                    )
                disbursedBy.isBlank() ->
                    Uni.createFrom().failure(IllegalArgumentException("Disburser identity is required"))
                // Segregation of duties: the officer releasing cash must not be the one who approved it
                // (three-eyes over the money-out step, EBA/GL/2020/06). Identities are trusted JWT subjects.
                disbursedBy == application.decidedBy ->
                    Uni.createFrom().failure(
                        IllegalStateException("Segregation of duties: disburser must differ from approver"),
                    )
                else -> bookLoan(application)
            }
        }

    @Suppress("LongMethod") // ADR-0100: clock-stamp fields push this 3 lines past threshold
    private fun bookLoan(application: LoanApplication): Uni<Loan> {
        val now = OffsetDateTime.now(clock)
        val loan = Loan(
            applicationId = application.id,
            partyId = application.partyId,
            principal = application.requestedAmount,
            nominalAnnualRate = application.nominalAnnualRate,
            termPeriods = application.termPeriods,
            periodsPerYear = application.periodsPerYear,
            method = application.method,
            firstDueDate = application.firstDueDate,
            disbursedAt = now,
            createdAt = now,
        )
        // Generate the contractual schedule from the pure libs primitive.
        val schedule = Amortization.schedule(
            principal = loan.principal,
            nominalAnnualRate = loan.nominalAnnualRate,
            termPeriods = loan.termPeriods,
            firstDueDate = loan.firstDueDate,
            periodsPerYear = loan.periodsPerYear,
            method = loan.method,
        )
        val rows = schedule.installments.map { i ->
            LoanInstallment(
                loanId = loan.id,
                number = i.number,
                dueDate = i.dueDate,
                openingBalance = i.openingBalance,
                principal = i.principal,
                interest = i.interest,
                payment = i.payment,
                closingBalance = i.closingBalance,
            )
        }
        return loans.save(loan)
            .flatMap { saved -> installments.saveAll(rows).map { saved } }
            .flatMap { saved ->
                applications.update(application.copy(status = ApplicationStatus.DISBURSED)).map { saved }
            }
            .flatMap { saved ->
                // Cash leaves the bank: post the disbursement to the ledger (we never mutate balances).
                ledger.post(
                    LedgerPosting(
                        "loan:${saved.id.value}:disbursement",
                        saved.partyId,
                        saved.principal,
                        PostingKind.DISBURSEMENT,
                    ),
                )
                    .flatMap {
                        events.emit(
                            LendingOutboxMessage(
                                aggregateId = saved.id.value,
                                eventType = "loan.disbursed",
                                payload = """{"loanId":"${saved.id.value}","partyId":"${saved.partyId}",""" +
                                    """"principal":"${saved.principal}"}""",
                            ),
                        )
                    }
                    .map { saved }
            }
    }

    // --- Servicing ----------------------------------------------------------------------------------

    override fun getLoan(id: LoanId): Uni<Loan?> = loans.findById(id)

    override fun getSchedule(id: LoanId): Uni<List<LoanInstallment>> = installments.findByLoan(id)

    override fun listLoans(partyId: UUID): Uni<List<Loan>> = loans.findByParty(partyId)

    override fun recordRepayment(loanId: LoanId, installmentId: UUID): Uni<LoanInstallment> =
        installments.findByLoan(loanId).flatMap { schedule ->
            val target = schedule.firstOrNull { it.id == installmentId }
            when {
                target == null ->
                    Uni.createFrom().failure(IllegalArgumentException("Installment not found on loan $loanId"))
                target.paid ->
                    Uni.createFrom().failure(IllegalStateException("Installment already paid"))
                else -> {
                    val paidAt = OffsetDateTime.now(clock)
                    installments.markPaid(installmentId, paidAt)
                        .flatMap {
                            // Split posting: principal repayment + the interest leg. If the scheduled pass already
                            // accrued this installment's interest income, the cash only *settles* the receivable
                            // (INTEREST_SETTLEMENT); otherwise it is repaid early and we recognize income directly
                            // (INTEREST). Either way interest income is booked exactly once.
                            val interestKind =
                                if (target.interestAccrued) PostingKind.INTEREST_SETTLEMENT else PostingKind.INTEREST
                            ledger.post(
                                LedgerPosting(
                                    "loan:$loanId:inst:${target.number}:principal",
                                    target.loanId.value,
                                    target.principal,
                                    PostingKind.PRINCIPAL_REPAYMENT,
                                ),
                            )
                                .flatMap {
                                    ledger.post(
                                        LedgerPosting(
                                            "loan:$loanId:inst:${target.number}:interest",
                                            target.loanId.value,
                                            target.interest,
                                            interestKind,
                                        ),
                                    )
                                }
                                .map { target.copy(paid = true, paidAt = paidAt) }
                        }
                }
            }
        }

    // --- Servicing posting loop: accrual-basis interest recognition ----------------------------------

    /**
     * Recognize interest income for every installment that has fallen due but is not yet accrued
     * (IAS 1 accrual basis), booking each to the ledger as an accrual against a receivable. Idempotent:
     * the `interestAccrued` flag is set per row, so a re-run never double-recognizes. Repayment later
     * settles the receivable rather than re-recognizing income (see [recordRepayment]).
     */
    override fun accrueDueInterest(asOf: LocalDate, limit: Int): Uni<AccrualOutcome> =
        installments.findAccruable(asOf, limit).flatMap { due ->
            Multi.createFrom().iterable(due)
                .onItem().transformToUniAndConcatenate { installment -> accrueOne(installment) }
                .collect().asList()
                .map { AccrualOutcome(asOf = asOf, installmentsAccrued = it.size) }
        }

    private fun accrueOne(installment: LoanInstallment): Uni<LoanInstallment> {
        // A zero-interest row (e.g. a pure-principal or bullet leg) has nothing to recognize: flag it so
        // the pass does not revisit it, but emit no ledger posting. Build the mark-only Uni lazily in this
        // branch — constructing it eagerly would invoke the repository even on the interest-bearing path.
        val accruedAt = OffsetDateTime.now(clock)
        if (!installment.interest.isPositive()) {
            return installments.markAccrued(installment.id, accruedAt).map { installment }
        }
        return ledger.post(
            LedgerPosting(
                "loan:${installment.loanId.value}:inst:${installment.number}:accrual",
                installment.loanId.value,
                installment.interest,
                PostingKind.INTEREST_ACCRUAL,
            ),
        )
            .flatMap { installments.markAccrued(installment.id, accruedAt) }
            .flatMap {
                events.emit(
                    LendingOutboxMessage(
                        aggregateId = installment.loanId.value,
                        eventType = "loan.interest_accrued",
                        payload = """{"loanId":"${installment.loanId.value}","installment":${installment.number},""" +
                            """"interest":"${installment.interest}","dueDate":"${installment.dueDate}"}""",
                    ),
                )
            }
            .map { installment }
    }

    // --- Write-off (collections terminal step, IFRS 9 Stage 3 → derecognition) -----------------------

    override fun writeOff(loanId: LoanId, request: WriteOffRequest): Uni<Loan> =
        loans.findById(loanId).flatMap { loan ->
            when {
                loan == null ->
                    Uni.createFrom().failure(IllegalArgumentException("Loan not found: $loanId"))
                loan.status != LoanStatus.ACTIVE ->
                    Uni.createFrom().failure(
                        IllegalStateException("Only an ACTIVE loan can be written off: ${loan.status}"),
                    )
                else -> installments.findByLoan(loanId).flatMap { schedule ->
                    val outstanding = outstandingBalance(loan, schedule)
                    if (!outstanding.isPositive()) {
                        Uni.createFrom().failure(
                            IllegalStateException("Nothing to write off: outstanding balance is zero"),
                        )
                    } else {
                        // Book the loss and remove the asset from the books; we never mutate balances ourselves.
                        ledger.post(
                            LedgerPosting(
                                "loan:${loanId.value}:writeoff",
                                loan.partyId,
                                outstanding,
                                PostingKind.WRITE_OFF,
                            ),
                        )
                            .flatMap { loans.update(loan.copy(status = LoanStatus.WRITTEN_OFF)) }
                            .flatMap { written ->
                                val wPayload = """{"loanId":"${written.id.value}",""" +
                                    """"partyId":"${written.partyId}",""" +
                                    """"writtenOff":"$outstanding",""" +
                                    """"writtenOffBy":"${request.writtenOffBy}"}"""
                                events.emit(
                                    LendingOutboxMessage(
                                        aggregateId = written.id.value,
                                        eventType = "loan.written_off",
                                        payload = wPayload,
                                    ),
                                ).map { written }
                            }
                    }
                }
            }
        }

    // --- Collateral ---------------------------------------------------------------------------------

    override fun register(loanId: LoanId, request: CollateralRequest): Uni<Collateral> {
        require(request.haircut.signum() >= 0 && request.haircut <= java.math.BigDecimal.ONE) {
            "Haircut must be within [0,1]: ${request.haircut}"
        }
        val now = OffsetDateTime.now(clock)
        return valuation.revalue(request.type.name, request.marketValue).flatMap { valued ->
            collateral.save(
                Collateral(
                    loanId = loanId,
                    type = request.type,
                    description = request.description,
                    marketValue = valued,
                    haircut = request.haircut,
                    valuedAt = now,
                    createdAt = now,
                ),
            )
        }
    }

    override fun list(loanId: LoanId): Uni<List<Collateral>> = collateral.findByLoan(loanId)

    // --- Provisioning (IFRS 9) ----------------------------------------------------------------------

    override fun assess(loanId: LoanId, asOf: LocalDate): Uni<ProvisioningSnapshot> =
        loans.findById(loanId).flatMap { loan ->
            if (loan == null) {
                Uni.createFrom().failure(IllegalArgumentException("Loan not found: $loanId"))
            } else {
                installments.findByLoan(loanId).flatMap { schedule ->
                    val outstanding = outstandingBalance(loan, schedule)
                    val oldestUnpaidDue = schedule.filter { !it.paid }.minByOrNull { it.dueDate }?.dueDate
                    val dpd = Delinquency.daysPastDue(oldestUnpaidDue, asOf)
                    riskParameters.parametersFor(loan, outstanding).map { inputs ->
                        val ecl = Ifrs9.assess(daysPastDue = dpd, inputs = inputs)
                        ProvisioningSnapshot(
                            loanId = loanId,
                            asOf = asOf,
                            outstandingBalance = outstanding,
                            daysPastDue = dpd,
                            bucket = Delinquency.bucket(dpd),
                            stage = ecl.stage,
                            horizon = ecl.horizon,
                            expectedCreditLoss = ecl.expectedCreditLoss,
                        )
                    }
                }
            }
        }

    /** Outstanding principal = opening balance of the first unpaid installment, else fully repaid. */
    private fun outstandingBalance(loan: Loan, schedule: List<LoanInstallment>): Money {
        val firstUnpaid = schedule.filter { !it.paid }.minByOrNull { it.number }
        return firstUnpaid?.openingBalance ?: Money.zero(loan.principal.currency.code)
    }
}
