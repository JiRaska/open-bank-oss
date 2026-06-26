// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.lending.application.port.`in`

import com.openbank.lending.domain.model.AccrualOutcome
import com.openbank.lending.domain.model.Collateral
import com.openbank.lending.domain.model.CollateralRequest
import com.openbank.lending.domain.model.DecisionRequest
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanApplicationRequest
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.ProvisioningSnapshot
import com.openbank.lending.domain.model.WriteOffRequest
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import io.smallrye.mutiny.Uni
import java.time.LocalDate
import java.util.UUID

/**
 * Origination: intake + four-eyes decision. [proposedBy] and [decidedBy] are the **trusted** acting
 * principals (the authenticated JWT subject), passed in by the adapter — not read from the request body.
 */
interface ApplyForLoanUseCase {
    fun apply(request: LoanApplicationRequest, proposedBy: String): Uni<LoanApplication>
    fun decide(id: LoanApplicationId, decision: DecisionRequest, decidedBy: String): Uni<LoanApplication>
    fun getApplication(id: LoanApplicationId): Uni<LoanApplication?>
    fun listApplications(partyId: UUID): Uni<List<LoanApplication>>
}

/**
 * Origination → servicing: disburse an approved application, booking the loan + its schedule.
 * [disbursedBy] is the trusted acting principal; segregation of duties requires it to differ from the
 * officer who approved the application (EBA/GL/2020/06).
 */
interface DisburseLoanUseCase {
    fun disburse(applicationId: LoanApplicationId, disbursedBy: String): Uni<Loan>
}

/** Servicing: read the loan and its schedule, record a repayment. */
interface ServicingUseCase {
    fun getLoan(id: LoanId): Uni<Loan?>
    fun getSchedule(id: LoanId): Uni<List<LoanInstallment>>
    fun listLoans(partyId: UUID): Uni<List<Loan>>
    fun recordRepayment(loanId: LoanId, installmentId: UUID): Uni<LoanInstallment>
}

/**
 * Servicing posting loop: recognize interest income on an accrual basis. A scheduled pass walks the
 * live book and accrues the interest of every installment that has fallen due (IAS 1 accrual basis),
 * independent of cash collection. Idempotent — already-accrued installments are skipped.
 */
interface AccrueInterestUseCase {
    fun accrueDueInterest(asOf: LocalDate, limit: Int): Uni<AccrualOutcome>
}

/** Collections terminal step: write off an uncollectible loan's remaining exposure (IFRS 9 Stage 3). */
interface WriteOffLoanUseCase {
    fun writeOff(loanId: LoanId, request: WriteOffRequest): Uni<Loan>
}

/** Collateral management. */
interface CollateralUseCase {
    fun register(loanId: LoanId, request: CollateralRequest): Uni<Collateral>
    fun list(loanId: LoanId): Uni<List<Collateral>>
}

/** Provisioning: IFRS 9 staging + ECL snapshot for a loan as of a date. */
interface ProvisioningUseCase {
    fun assess(loanId: LoanId, asOf: LocalDate): Uni<ProvisioningSnapshot>
}
