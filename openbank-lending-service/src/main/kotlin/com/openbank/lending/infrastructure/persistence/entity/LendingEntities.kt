// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.entity

import com.openbank.lending.domain.model.ApplicationStatus
import com.openbank.lending.domain.model.CollateralType
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.libs.lending.AmortizationMethod
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
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
    var status: ApplicationStatus = ApplicationStatus.PROPOSED

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

    @Column(name = "created_at")
    var createdAt: OffsetDateTime = OffsetDateTime.MIN
}
