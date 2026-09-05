// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.mapper

import com.openbank.lending.domain.model.CatalogLoanSnapshot
import com.openbank.lending.domain.model.Collateral
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanProvisioningRecord
import com.openbank.lending.infrastructure.persistence.entity.CollateralEntity
import com.openbank.lending.infrastructure.persistence.entity.InstallmentEntity
import com.openbank.lending.infrastructure.persistence.entity.LoanApplicationEntity
import com.openbank.lending.infrastructure.persistence.entity.LoanEntity
import com.openbank.lending.infrastructure.persistence.entity.LoanProvisioningEntity
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
        it.jurisdiction = a.jurisdiction
        it.productType = a.productType
        it.productKind = a.productKind
        it.packVersion = a.packVersion
        it.verifiedIncomeMonthly = a.verifiedIncomeMonthly?.amount
        it.existingDebtServiceMonthly = a.existingDebtServiceMonthly?.amount
        it.ageYears = a.ageYears
        it.residency = a.residency
        it.employmentTenureMonths = a.employmentTenureMonths
        it.decisionOutcome = a.decisionOutcome
        it.decisionPriceBand = a.decisionPriceBand
        it.decisionReasons = a.decisionReasons
        it.decisionMatchedRules = a.decisionMatchedRules
        it.policyVersions = a.policyVersions
        it.decisionInputHash = a.decisionInputHash
        it.decidedEngineAt = a.decidedEngineAt
        it.catalogOfferingId = a.catalogSnapshot?.offeringId
        it.catalogRevisionId = a.catalogSnapshot?.revisionId
        it.catalogContentHash = a.catalogSnapshot?.contentHash
        it.catalogSchemaVersion = a.catalogSnapshot?.schemaVersion
    }

    fun toDomain(e: LoanApplicationEntity) = LoanApplication(
        id = LoanApplicationId(e.id), partyId = e.partyId,
        requestedAmount = Money.of(e.requestedAmount, e.currency),
        nominalAnnualRate = e.nominalAnnualRate, termPeriods = e.termPeriods,
        periodsPerYear = e.periodsPerYear, method = e.method, firstDueDate = e.firstDueDate,
        status = e.status, proposedBy = e.proposedBy, decidedBy = e.decidedBy,
        decisionReason = e.decisionReason, createdAt = e.createdAt, decidedAt = e.decidedAt,
        jurisdiction = e.jurisdiction, productType = e.productType, productKind = e.productKind,
        packVersion = e.packVersion,
        verifiedIncomeMonthly = e.verifiedIncomeMonthly?.let { Money.of(it, e.currency) },
        existingDebtServiceMonthly = e.existingDebtServiceMonthly?.let { Money.of(it, e.currency) },
        ageYears = e.ageYears, residency = e.residency, employmentTenureMonths = e.employmentTenureMonths,
        decisionOutcome = e.decisionOutcome, decisionPriceBand = e.decisionPriceBand,
        decisionReasons = e.decisionReasons, decisionMatchedRules = e.decisionMatchedRules,
        policyVersions = e.policyVersions, decisionInputHash = e.decisionInputHash,
        decidedEngineAt = e.decidedEngineAt,
        catalogSnapshot = e.toCatalogSnapshot(),
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
        it.noticeEndsOn = l.noticeEndsOn
        it.terminatedBy = l.terminatedBy
        it.terminatedAt = l.terminatedAt
        it.version = l.version
        it.createdAt = l.createdAt
    }

    fun toDomain(e: LoanEntity) = Loan(
        id = LoanId(e.id), applicationId = LoanApplicationId(e.applicationId), partyId = e.partyId,
        principal = Money.of(e.principal, e.currency), nominalAnnualRate = e.nominalAnnualRate,
        termPeriods = e.termPeriods, periodsPerYear = e.periodsPerYear, method = e.method,
        firstDueDate = e.firstDueDate, status = e.status, disbursedAt = e.disbursedAt,
        noticeEndsOn = e.noticeEndsOn, terminatedBy = e.terminatedBy, terminatedAt = e.terminatedAt,
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
        it.status = c.status
        it.registeredBy = c.registeredBy
        it.decidedBy = c.decidedBy
        it.decidedAt = c.decidedAt
        it.createdAt = c.createdAt
        it.releasedAt = c.releasedAt
    }

    fun toDomain(e: CollateralEntity) = Collateral(
        id = CollateralId(e.id),
        loanId = LoanId(e.loanId),
        type = e.type,
        description = e.description,
        marketValue = Money.of(e.marketValue, e.currency),
        haircut = e.haircut,
        valuedAt = e.valuedAt,
        status = e.status, releasedAt = e.releasedAt,
        registeredBy = e.registeredBy,
        decidedBy = e.decidedBy,
        decidedAt = e.decidedAt,
        createdAt = e.createdAt,
    )

    fun toEntity(p: LoanProvisioningRecord) = LoanProvisioningEntity().also {
        it.id = p.id
        it.loanId = p.loanId.value
        it.period = p.period
        it.asOf = p.asOf
        it.outstandingBalance = p.outstandingBalance.amount
        it.currency = p.outstandingBalance.currency.code
        it.daysPastDue = p.daysPastDue
        it.bucket = p.bucket
        it.stage = p.stage
        it.expectedCreditLoss = p.expectedCreditLoss.amount
        it.createdAt = p.createdAt
        it.modelVersion = p.modelVersion
    }

    fun toDomain(e: LoanProvisioningEntity) = LoanProvisioningRecord(
        id = e.id,
        loanId = LoanId(e.loanId),
        period = e.period,
        asOf = e.asOf,
        outstandingBalance = Money.of(e.outstandingBalance, e.currency),
        daysPastDue = e.daysPastDue,
        bucket = e.bucket,
        stage = e.stage,
        expectedCreditLoss = Money.of(e.expectedCreditLoss, e.currency),
        createdAt = e.createdAt,
        modelVersion = e.modelVersion,
    )
}

private fun LoanApplicationEntity.toCatalogSnapshot(): CatalogLoanSnapshot? {
    val offeringId = catalogOfferingId ?: return null
    val revisionId = catalogRevisionId ?: return null
    val contentHash = catalogContentHash ?: return null
    val schemaVersion = catalogSchemaVersion ?: return null
    return CatalogLoanSnapshot(offeringId, revisionId, contentHash, schemaVersion)
}
