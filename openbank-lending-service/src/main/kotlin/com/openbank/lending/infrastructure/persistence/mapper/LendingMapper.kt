// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.lending.infrastructure.persistence.mapper

import com.openbank.lending.domain.model.Collateral
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.infrastructure.persistence.entity.CollateralEntity
import com.openbank.lending.infrastructure.persistence.entity.InstallmentEntity
import com.openbank.lending.infrastructure.persistence.entity.LoanApplicationEntity
import com.openbank.lending.infrastructure.persistence.entity.LoanEntity
import com.openbank.libs.domain.identifiers.CollateralId
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class LendingMapper {

    fun toEntity(a: LoanApplication) = LoanApplicationEntity().also {
        it.id = a.id.value
        it.partyId = a.partyId
        it.requestedAmount = a.requestedAmount.amount
        it.currency = a.requestedAmount.currency.code
        it.nominalAnnualRate = a.nominalAnnualRate
        it.termPeriods = a.termPeriods
        it.periodsPerYear = a.periodsPerYear
        it.method = a.method
        it.firstDueDate = a.firstDueDate
        it.status = a.status
        it.proposedBy = a.proposedBy
        it.decidedBy = a.decidedBy
        it.decisionReason = a.decisionReason
        it.createdAt = a.createdAt
        it.decidedAt = a.decidedAt
    }

    fun toDomain(e: LoanApplicationEntity) = LoanApplication(
        id = LoanApplicationId(e.id), partyId = e.partyId,
        requestedAmount = Money.of(e.requestedAmount, e.currency),
        nominalAnnualRate = e.nominalAnnualRate, termPeriods = e.termPeriods,
        periodsPerYear = e.periodsPerYear, method = e.method, firstDueDate = e.firstDueDate,
        status = e.status, proposedBy = e.proposedBy, decidedBy = e.decidedBy,
        decisionReason = e.decisionReason, createdAt = e.createdAt, decidedAt = e.decidedAt,
    )

    fun toEntity(l: Loan) = LoanEntity().also {
        it.id = l.id.value
        it.applicationId = l.applicationId.value
        it.partyId = l.partyId
        it.principal = l.principal.amount
        it.currency = l.principal.currency.code
        it.nominalAnnualRate = l.nominalAnnualRate
        it.termPeriods = l.termPeriods
        it.periodsPerYear = l.periodsPerYear
        it.method = l.method
        it.firstDueDate = l.firstDueDate
        it.status = l.status
        it.disbursedAt = l.disbursedAt
        it.version = l.version
        it.createdAt = l.createdAt
    }

    fun toDomain(e: LoanEntity) = Loan(
        id = LoanId(e.id), applicationId = LoanApplicationId(e.applicationId), partyId = e.partyId,
        principal = Money.of(e.principal, e.currency), nominalAnnualRate = e.nominalAnnualRate,
        termPeriods = e.termPeriods, periodsPerYear = e.periodsPerYear, method = e.method,
        firstDueDate = e.firstDueDate, status = e.status, disbursedAt = e.disbursedAt,
        version = e.version, createdAt = e.createdAt,
    )

    fun toEntity(i: LoanInstallment) = InstallmentEntity().also {
        it.id = i.id
        it.loanId = i.loanId.value
        it.number = i.number
        it.dueDate = i.dueDate
        it.currency = i.payment.currency.code
        it.openingBalance = i.openingBalance.amount
        it.principal = i.principal.amount
        it.interest = i.interest.amount
        it.payment = i.payment.amount
        it.closingBalance = i.closingBalance.amount
        it.paid = i.paid
        it.paidAt = i.paidAt
        it.interestAccrued = i.interestAccrued
        it.accruedAt = i.accruedAt
    }

    fun toDomain(e: InstallmentEntity) = LoanInstallment(
        id = e.id, loanId = LoanId(e.loanId), number = e.number, dueDate = e.dueDate,
        openingBalance = Money.of(e.openingBalance, e.currency), principal = Money.of(e.principal, e.currency),
        interest = Money.of(e.interest, e.currency), payment = Money.of(e.payment, e.currency),
        closingBalance = Money.of(e.closingBalance, e.currency), paid = e.paid, paidAt = e.paidAt,
        interestAccrued = e.interestAccrued, accruedAt = e.accruedAt,
    )

    fun toEntity(c: Collateral) = CollateralEntity().also {
        it.id = c.id.value
        it.loanId = c.loanId.value
        it.type = c.type
        it.description = c.description
        it.marketValue = c.marketValue.amount
        it.currency = c.marketValue.currency.code
        it.haircut = c.haircut
        it.valuedAt = c.valuedAt
        it.createdAt = c.createdAt
    }

    fun toDomain(e: CollateralEntity) = Collateral(
        id = CollateralId(e.id),
        loanId = LoanId(e.loanId),
        type = e.type,
        description = e.description,
        marketValue = Money.of(e.marketValue, e.currency),
        haircut = e.haircut,
        valuedAt = e.valuedAt,
        createdAt = e.createdAt,
    )
}
