// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.domain.model

import com.openbank.libs.domain.identifiers.CollateralId
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.libs.lending.DelinquencyBucket
import com.openbank.libs.lending.EclHorizon
import com.openbank.libs.lending.Ifrs9Stage
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Origination decision state (EBA/GL/2020/06 four-eyes flow). */
enum class ApplicationStatus { PROPOSED, APPROVED, REJECTED, DISBURSED }

/** Servicing lifecycle of a booked loan. */
enum class LoanStatus { ACTIVE, CLOSED, WRITTEN_OFF }

/** AnaCredit protection categories. */
enum class CollateralType { REAL_ESTATE, VEHICLE, SECURITIES, CASH_DEPOSIT, GUARANTEE, OTHER }

/**
 * A credit application moving through the four-eyes decision flow. [proposedBy] and [decidedBy] are the
 * **authenticated principals** (JWT subject) captured server-side at each step — never client-supplied —
 * and the officer who [proposedBy] cannot be the one who decides ([decidedBy]), enforced in the
 * application service (ADR-0028 D5, EBA/GL/2020/06).
 */
data class LoanApplication(
    val id: LoanApplicationId = LoanApplicationId.random(),
    val partyId: UUID,
    val requestedAmount: Money,
    val nominalAnnualRate: BigDecimal,
    val termPeriods: Int,
    val periodsPerYear: Int = 12,
    val method: AmortizationMethod = AmortizationMethod.ANNUITY,
    val firstDueDate: LocalDate,
    val status: ApplicationStatus = ApplicationStatus.PROPOSED,
    val proposedBy: String,
    val decidedBy: String? = null,
    val decisionReason: String? = null,
    val createdAt: OffsetDateTime,
    val decidedAt: OffsetDateTime? = null,
)

/** A live loan booked from an approved, disbursed application. */
data class Loan(
    val id: LoanId = LoanId.random(),
    val applicationId: LoanApplicationId,
    val partyId: UUID,
    val principal: Money,
    val nominalAnnualRate: BigDecimal,
    val termPeriods: Int,
    val periodsPerYear: Int = 12,
    val method: AmortizationMethod,
    val firstDueDate: LocalDate,
    val status: LoanStatus = LoanStatus.ACTIVE,
    val disbursedAt: OffsetDateTime,
    val version: Long = 0,
    val createdAt: OffsetDateTime,
)

/**
 * One row of a loan's contractual repayment schedule (persisted from libs `Amortization`).
 *
 * [interestAccrued] tracks accrual-basis interest recognition: the scheduled servicing pass recognizes
 * each installment's [interest] as income once its [dueDate] has arrived (IAS 1 accrual principle),
 * independent of when the cash repayment actually settles ([paid]). The flag makes that recognition
 * idempotent and keeps the cash repayment from double-counting interest income.
 */
data class LoanInstallment(
    val id: UUID = UUID.randomUUID(),
    val loanId: LoanId,
    val number: Int,
    val dueDate: LocalDate,
    val openingBalance: Money,
    val principal: Money,
    val interest: Money,
    val payment: Money,
    val closingBalance: Money,
    val paid: Boolean = false,
    val paidAt: OffsetDateTime? = null,
    val interestAccrued: Boolean = false,
    val accruedAt: OffsetDateTime? = null,
)

/** Outcome of one scheduled interest-accrual pass over the live book (servicing posting loop). */
data class AccrualOutcome(val asOf: LocalDate, val installmentsAccrued: Int)

/** Security registered against a loan. */
data class Collateral(
    val id: CollateralId = CollateralId.random(),
    val loanId: LoanId,
    val type: CollateralType,
    val description: String? = null,
    val marketValue: Money,
    val haircut: BigDecimal = BigDecimal.ZERO,
    val valuedAt: OffsetDateTime,
    val createdAt: OffsetDateTime,
)

// --- Inbound requests -------------------------------------------------------------------------------

/**
 * Loan-application intake. Note there is **no `proposedBy`**: the maker's identity is taken from the
 * authenticated JWT subject server-side, never trusted from the request body (ADR-0028 D5).
 */
data class LoanApplicationRequest(
    val partyId: UUID,
    val requestedAmount: Money,
    val nominalAnnualRate: BigDecimal,
    val termPeriods: Int,
    val periodsPerYear: Int = 12,
    val method: AmortizationMethod = AmortizationMethod.ANNUITY,
    val firstDueDate: LocalDate,
)

/**
 * Credit decision. Note there is **no `decidedBy`**: the checker's identity is taken from the
 * authenticated JWT subject server-side, so the four-eyes separation cannot be spoofed (ADR-0028 D5).
 */
data class DecisionRequest(val approve: Boolean, val reason: String? = null)

data class CollateralRequest(
    val type: CollateralType,
    val description: String? = null,
    val marketValue: Money,
    val haircut: BigDecimal = BigDecimal.ZERO,
)

/**
 * Terminal credit-loss event: the bank judges the loan's remaining exposure uncollectible and removes
 * it from the books (IFRS 9 Stage 3 → derecognition). [writtenOffBy] and [reason] are recorded for the
 * audit trail; the action is role-gated to credit-risk/compliance (ADR-0028 D5).
 */
data class WriteOffRequest(val writtenOffBy: String, val reason: String? = null)

// --- Provisioning read model (IFRS 9) ---------------------------------------------------------------

/** A point-in-time IFRS 9 / arrears snapshot for one loan, computed from libs primitives. */
data class ProvisioningSnapshot(
    val loanId: LoanId,
    val asOf: LocalDate,
    val outstandingBalance: Money,
    val daysPastDue: Int,
    val bucket: DelinquencyBucket,
    val stage: Ifrs9Stage,
    val horizon: EclHorizon,
    val expectedCreditLoss: Money,
)
