// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.entity

import com.openbank.lending.domain.model.CollateralStatus
import com.openbank.lending.domain.model.CollateralType
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.libs.lending.DelinquencyBucket
import com.openbank.libs.lending.Ifrs9Stage
import com.openbank.libs.lending.origination.OriginationState
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "loan_application")
class LoanApplicationEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = UUID.randomUUID()

    @Column(name = "party_id", columnDefinition = "uuid")
    var partyId: UUID = UUID.randomUUID()

    @Column(name = "requested_amount", precision = 20, scale = 2)
    var requestedAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "currency", length = 3)
    var currency: String = "EUR"

    @Column(name = "nominal_annual_rate", precision = 10, scale = 6)
    var nominalAnnualRate: BigDecimal = BigDecimal.ZERO

    @Column(name = "term_periods")
    var termPeriods: Int = 0

    @Column(name = "periods_per_year")
    var periodsPerYear: Int = 12

    @Column(name = "method")
    @Enumerated(EnumType.STRING)
    var method: AmortizationMethod = AmortizationMethod.ANNUITY

    @Column(name = "first_due_date")
    var firstDueDate: LocalDate = LocalDate.EPOCH

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    var status: OriginationState = OriginationState.SUBMITTED

    @Column(name = "jurisdiction", length = 8)
    var jurisdiction: String? = null

    @Column(name = "product_type", length = 32)
    var productType: String? = null

    @Column(name = "pack_version")
    var packVersion: Int? = null

    @Column(name = "proposed_by")
    var proposedBy: String = ""

    @Column(name = "decided_by")
    var decidedBy: String? = null

    @Column(name = "decision_reason")
    var decisionReason: String? = null

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.MIN

    @Column(name = "decided_at")
    var decidedAt: OffsetDateTime? = null
}

@Entity
@Table(name = "loan")
class LoanEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = UUID.randomUUID()

    @Column(name = "application_id", columnDefinition = "uuid")
    var applicationId: UUID = UUID.randomUUID()

    @Column(name = "party_id", columnDefinition = "uuid")
    var partyId: UUID = UUID.randomUUID()

    @Column(name = "principal", precision = 20, scale = 2)
    var principal: BigDecimal = BigDecimal.ZERO

    @Column(name = "currency", length = 3)
    var currency: String = "EUR"

    @Column(name = "nominal_annual_rate", precision = 10, scale = 6)
    var nominalAnnualRate: BigDecimal = BigDecimal.ZERO

    @Column(name = "term_periods")
    var termPeriods: Int = 0

    @Column(name = "periods_per_year")
    var periodsPerYear: Int = 12

    @Column(name = "method")
    @Enumerated(EnumType.STRING)
    var method: AmortizationMethod = AmortizationMethod.ANNUITY

    @Column(name = "first_due_date")
    var firstDueDate: LocalDate = LocalDate.EPOCH

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    var status: LoanStatus = LoanStatus.ACTIVE

    @Column(name = "disbursed_at")
    var disbursedAt: OffsetDateTime = OffsetDateTime.MIN

    @Column(name = "notice_ends_on")
    var noticeEndsOn: LocalDate? = null

    @Column(name = "terminated_by", length = 128)
    var terminatedBy: String? = null

    @Column(name = "terminated_at")
    var terminatedAt: OffsetDateTime? = null

    @Column(name = "version")
    var version: Long = 0

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.MIN
}

@Entity
@Table(name = "installment")
class InstallmentEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = UUID.randomUUID()

    @Column(name = "loan_id", columnDefinition = "uuid")
    var loanId: UUID = UUID.randomUUID()

    @Column(name = "number")
    var number: Int = 0

    @Column(name = "due_date")
    var dueDate: LocalDate = LocalDate.EPOCH

    @Column(name = "currency", length = 3)
    var currency: String = "EUR"

    @Column(name = "opening_balance", precision = 20, scale = 2)
    var openingBalance: BigDecimal = BigDecimal.ZERO

    @Column(name = "principal", precision = 20, scale = 2)
    var principal: BigDecimal = BigDecimal.ZERO

    @Column(name = "interest", precision = 20, scale = 2)
    var interest: BigDecimal = BigDecimal.ZERO

    @Column(name = "payment", precision = 20, scale = 2)
    var payment: BigDecimal = BigDecimal.ZERO

    @Column(name = "closing_balance", precision = 20, scale = 2)
    var closingBalance: BigDecimal = BigDecimal.ZERO

    @Column(name = "paid")
    var paid: Boolean = false

    @Column(name = "paid_at")
    var paidAt: OffsetDateTime? = null

    @Column(name = "interest_accrued")
    var interestAccrued: Boolean = false

    @Column(name = "accrued_at")
    var accruedAt: OffsetDateTime? = null
}

@Entity
@Table(name = "collateral")
class CollateralEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = UUID.randomUUID()

    @Column(name = "loan_id", columnDefinition = "uuid")
    var loanId: UUID = UUID.randomUUID()

    @Column(name = "type")
    @Enumerated(EnumType.STRING)
    var type: CollateralType = CollateralType.OTHER

    @Column(name = "description")
    var description: String? = null

    @Column(name = "market_value", precision = 20, scale = 2)
    var marketValue: BigDecimal = BigDecimal.ZERO

    @Column(name = "currency", length = 3)
    var currency: String = "EUR"

    @Column(name = "haircut", precision = 5, scale = 4)
    var haircut: BigDecimal = BigDecimal.ZERO

    @Column(name = "valued_at")
    var valuedAt: OffsetDateTime = OffsetDateTime.MIN

    @Column(name = "released_at")
    var releasedAt: OffsetDateTime? = null

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    var status: CollateralStatus = CollateralStatus.PENDING

    @Column(name = "registered_by")
    var registeredBy: String = ""

    @Column(name = "decided_by")
    var decidedBy: String? = null

    @Column(name = "decided_at")
    var decidedAt: OffsetDateTime? = null

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.MIN
}

/**
 * One IFRS 9 stage/ECL record per loan per reporting period (ADR-0028 Phase 3). The scheduled
 * provisioning cycle reads the prior period's row as the delta baseline before inserting a new one;
 * `UNIQUE(loan_id, period)` is both the natural key and the pass's idempotency guard (a re-run for a
 * period that already has a row is a no-op re-read, never a duplicate insert — see
 * `ProvisioningRepositoryImpl.findByLoanAndPeriod`).
 */
@Entity
@Table(
    name = "loan_provisioning",
    uniqueConstraints = [UniqueConstraint(columnNames = ["loan_id", "period"])],
)
class LoanProvisioningEntity : PanacheEntityBase() {
    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = Ids.newId()

    @Column(name = "loan_id", columnDefinition = "uuid")
    var loanId: UUID = Ids.newId()

    @Column(name = "period", length = 7)
    var period: String = ""

    @Column(name = "as_of")
    var asOf: LocalDate = LocalDate.EPOCH

    @Column(name = "outstanding_balance", precision = 20, scale = 2)
    var outstandingBalance: BigDecimal = BigDecimal.ZERO

    @Column(name = "currency", length = 3)
    var currency: String = "EUR"

    @Column(name = "days_past_due")
    var daysPastDue: Int = 0

    @Column(name = "bucket")
    @Enumerated(EnumType.STRING)
    var bucket: DelinquencyBucket = DelinquencyBucket.CURRENT

    @Column(name = "stage")
    @Enumerated(EnumType.STRING)
    var stage: Ifrs9Stage = Ifrs9Stage.STAGE_1

    @Column(name = "expected_credit_loss", precision = 20, scale = 2)
    var expectedCreditLoss: BigDecimal = BigDecimal.ZERO

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.MIN
}

@Entity
@Table(name = "settlement_quote")
class SettlementQuoteEntity : io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase {

    @Id
    @Column(columnDefinition = "uuid")
    var id: UUID = UUID.randomUUID()

    @Column(name = "loan_id", columnDefinition = "uuid")
    var loanId: UUID = UUID.randomUUID()

    @Column(name = "as_of_date")
    var asOfDate: LocalDate = LocalDate.EPOCH

    @Column(name = "valid_until")
    var validUntil: LocalDate = LocalDate.EPOCH

    @Column(name = "outstanding_principal", precision = 20, scale = 2)
    var outstandingPrincipal: BigDecimal = BigDecimal.ZERO

    @Column(name = "accrued_interest", precision = 20, scale = 2)
    var accruedInterest: BigDecimal = BigDecimal.ZERO

    @Column(name = "compensation", precision = 20, scale = 2)
    var compensation: BigDecimal = BigDecimal.ZERO

    @Column(name = "unapplied_credit", precision = 20, scale = 2)
    var unappliedCredit: BigDecimal = BigDecimal.ZERO

    @Column(name = "total", precision = 20, scale = 2)
    var total: BigDecimal = BigDecimal.ZERO

    @Column(name = "currency", length = 3)
    var currency: String = "EUR"

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "settled_at")
    var settledAt: OffsetDateTime? = null
}
