// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.persistence.mapper

import com.openbank.interest.domain.model.*
import com.openbank.interest.domain.tax.TaxProfile
import com.openbank.interest.domain.tax.WithholdingRemittance
import com.openbank.interest.domain.tax.WithholdingTax
import com.openbank.interest.infrastructure.persistence.entity.InterestAccrualEntity
import com.openbank.interest.infrastructure.persistence.entity.InterestCapitalizationEntity
import com.openbank.interest.infrastructure.persistence.entity.InterestRateConfigEntity
import com.openbank.interest.infrastructure.persistence.entity.WithholdingRemittanceEntity
import com.openbank.interest.infrastructure.persistence.entity.WithholdingTaxEntity
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class InterestMapper {
    fun toEntity(c: InterestRateConfig) = InterestRateConfigEntity().also {
        it.id = c.id
        it.productId = c.productId
        it.accountId = c.accountId
        it.currency = c.currency
        it.rateType = c.rateType
        it.annualRate = c.annualRate
        it.minBalance = c.minBalance
        it.maxBalance = c.maxBalance
        it.dayCount = c.dayCount
        it.effectiveFrom = c.effectiveFrom
        it.effectiveTo = c.effectiveTo
        it.active = c.active
        it.createdAt = c.createdAt
        it.updatedAt = c.updatedAt
    }
    fun toDomain(e: InterestRateConfigEntity) = InterestRateConfig(
        id = e.id, productId = e.productId, accountId = e.accountId, currency = e.currency, rateType = e.rateType,
        annualRate = e.annualRate, minBalance = e.minBalance, maxBalance = e.maxBalance,
        dayCount = e.dayCount, effectiveFrom = e.effectiveFrom, effectiveTo = e.effectiveTo,
        active = e.active, createdAt = e.createdAt, updatedAt = e.updatedAt,
    )
    fun toEntity(a: InterestAccrual) = InterestAccrualEntity().also {
        it.id = a.id
        it.accountId = a.accountId
        it.productId = a.productId
        it.configId = a.configId
        it.accrualDate = a.accrualDate
        it.balance = a.balance
        it.dailyRate = a.dailyRate
        it.accruedAmount = a.accruedAmount
        it.currency = a.currency
        it.status = a.status
        it.claimedPeriodTo = a.claimedPeriodTo
        a.claimedTaxProfile?.let { p ->
            it.claimedTaxpayerType = p.taxpayerType
            it.claimedResidency = p.residency
            it.claimedTreatyRate = p.treatyRate
            it.claimedNonCooperatingState = p.nonCooperatingState
            it.claimedExemptCode = p.exemptCode
        }
        it.capitalizedAt = a.capitalizedAt
        it.createdAt = a.createdAt
    }
    fun toDomain(e: InterestAccrualEntity) = InterestAccrual(
        id = e.id, accountId = e.accountId, productId = e.productId,
        configId = e.configId, accrualDate = e.accrualDate, balance = e.balance,
        dailyRate = e.dailyRate, accruedAmount = e.accruedAmount, currency = e.currency,
        status = e.status, claimedPeriodTo = e.claimedPeriodTo,
        // Reassemble the profile snapshot; taxpayer_type is the discriminator (all five written
        // together at claim, so residency is non-null whenever taxpayer_type is) — #1355.
        claimedTaxProfile = e.claimedTaxpayerType?.let { tt ->
            TaxProfile(
                taxpayerType = tt,
                residency = e.claimedResidency!!,
                treatyRate = e.claimedTreatyRate,
                nonCooperatingState = e.claimedNonCooperatingState ?: false,
                exemptCode = e.claimedExemptCode,
            )
        },
        capitalizedAt = e.capitalizedAt, createdAt = e.createdAt,
    )
    fun toEntity(c: InterestCapitalization) = InterestCapitalizationEntity().also {
        it.id = c.id
        it.accountId = c.accountId
        it.productId = c.productId
        it.periodFrom = c.periodFrom
        it.periodTo = c.periodTo
        it.totalAccrued = c.totalAccrued
        it.capitalizedAmount = c.capitalizedAmount
        it.grossAmount = c.grossAmount
        it.taxAmount = c.taxAmount
        it.netAmount = c.netAmount
        it.currency = c.currency
        it.ledgerEntryId = c.ledgerEntryId
        it.createdAt = c.createdAt
    }
    fun toDomain(e: InterestCapitalizationEntity) = InterestCapitalization(
        id = e.id, accountId = e.accountId, productId = e.productId,
        periodFrom = e.periodFrom, periodTo = e.periodTo, totalAccrued = e.totalAccrued,
        capitalizedAmount = e.capitalizedAmount, grossAmount = e.grossAmount,
        taxAmount = e.taxAmount, netAmount = e.netAmount, currency = e.currency,
        ledgerEntryId = e.ledgerEntryId, createdAt = e.createdAt,
    )
    fun toEntity(w: WithholdingTax) = WithholdingTaxEntity().also {
        it.id = w.id
        it.capitalizationId = w.capitalizationId
        it.accountId = w.accountId
        it.partyRef = w.partyRef
        it.periodFrom = w.periodFrom
        it.periodTo = w.periodTo
        it.taxableBase = w.taxableBase
        it.rate = w.rate
        it.taxAmount = w.taxAmount
        it.currency = w.currency
        it.treatment = w.treatment
        it.exemptCode = w.exemptCode
        it.status = w.status
        it.remittanceId = w.remittanceId
        it.createdAt = w.createdAt
    }
    fun toDomain(e: WithholdingTaxEntity) = WithholdingTax(
        id = e.id, capitalizationId = e.capitalizationId, accountId = e.accountId,
        partyRef = e.partyRef, periodFrom = e.periodFrom, periodTo = e.periodTo,
        taxableBase = e.taxableBase, rate = e.rate, taxAmount = e.taxAmount,
        currency = e.currency, treatment = e.treatment, exemptCode = e.exemptCode,
        status = e.status, remittanceId = e.remittanceId, createdAt = e.createdAt,
    )
    fun toEntity(r: WithholdingRemittance) = WithholdingRemittanceEntity().also {
        it.id = r.id
        it.periodYear = r.periodYear
        it.periodMonth = r.periodMonth
        it.authority = r.authority
        it.currency = r.currency
        it.totalTaxAmount = r.totalTaxAmount
        it.itemCount = r.itemCount
        it.dueDate = r.dueDate
        it.status = r.status
        it.createdAt = r.createdAt
    }
    fun toDomain(e: WithholdingRemittanceEntity) = WithholdingRemittance(
        id = e.id, periodYear = e.periodYear, periodMonth = e.periodMonth,
        authority = e.authority, currency = e.currency, totalTaxAmount = e.totalTaxAmount,
        itemCount = e.itemCount, dueDate = e.dueDate, status = e.status,
        withholdingIds = emptyList(), createdAt = e.createdAt,
    )
}
