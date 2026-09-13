// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.mapper

import com.openbank.lending.domain.model.CatalogLoanSnapshot
import com.openbank.lending.domain.model.Collateral
import com.openbank.lending.domain.model.CollateralStatus
import com.openbank.lending.domain.model.CollateralType
import com.openbank.lending.domain.model.Loan
import com.openbank.lending.domain.model.LoanApplication
import com.openbank.lending.domain.model.LoanInstallment
import com.openbank.lending.domain.model.LoanProvisioningRecord
import com.openbank.lending.domain.model.LoanStatus
import com.openbank.libs.domain.identifiers.CollateralId
import com.openbank.libs.domain.identifiers.LoanApplicationId
import com.openbank.libs.domain.identifiers.LoanId
import com.openbank.libs.domain.money.Money
import com.openbank.libs.lending.AmortizationMethod
import com.openbank.libs.lending.DelinquencyBucket
import com.openbank.libs.lending.Ifrs9Stage
import com.openbank.libs.lending.origination.OriginationState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Entity <-> domain mapping is where money amounts and their currency are split into separate columns
 * and re-joined; a silent field swap here corrupts the loan book. Round-trips must be lossless.
 */
class LendingMapperTest {

    private val mapper = LendingMapper()
    private val partyId = UUID.fromString("33333333-3333-3333-3333-333333333333")
    private val createdAt = OffsetDateTime.parse("2026-01-15T10:00:00Z")
    private val firstDue = LocalDate.parse("2026-06-30")

    private fun eur(v: String) = Money.of(v, "EUR")

    @Test
    fun `loan application round-trips losslessly, including the decision fields`() {
        val application = LoanApplication(
            id = LoanApplicationId.random(),
            partyId = partyId,
            requestedAmount = eur("12000.00"),
            nominalAnnualRate = BigDecimal("0.115000"),
            termPeriods = 24,
            periodsPerYear = 12,
            method = AmortizationMethod.ANNUITY,
            firstDueDate = firstDue,
            status = OriginationState.DECLINED,
            proposedBy = "alice",
            decidedBy = "bob",
            decisionReason = "affordability",
            createdAt = createdAt,
            decidedAt = createdAt.plusDays(1),
            catalogSnapshot = CatalogLoanSnapshot(
                UUID.fromString("10000000-0000-0000-0000-000000000013"),
                UUID.fromString("20000000-0000-0000-0000-000000000013"),
                "c".repeat(64),
                2,
            ),
        )

        val entity = mapper.toEntity(application)
        assertThat(entity.id).isEqualTo(application.id.value)
        assertThat(entity.requestedAmount).isEqualTo(BigDecimal("12000.00"))
        assertThat(entity.currency).isEqualTo("EUR")

        assertThat(mapper.toDomain(entity)).isEqualTo(application)
    }

    @Test
    fun `loan round-trips losslessly, including status and optimistic-lock version`() {
        val loan = Loan(
            id = LoanId.random(),
            applicationId = LoanApplicationId.random(),
            partyId = partyId,
            principal = eur("12000.00"),
            nominalAnnualRate = BigDecimal("0.115000"),
            termPeriods = 24,
            periodsPerYear = 12,
            method = AmortizationMethod.EQUAL_PRINCIPAL,
            firstDueDate = firstDue,
            status = LoanStatus.WRITTEN_OFF,
            disbursedAt = createdAt,
            version = 7,
            createdAt = createdAt,
        )

        val entity = mapper.toEntity(loan)
        assertThat(entity.principal).isEqualTo(BigDecimal("12000.00"))
        assertThat(entity.currency).isEqualTo("EUR")
        assertThat(entity.version).isEqualTo(7)

        assertThat(mapper.toDomain(entity)).isEqualTo(loan)
    }

    @Test
    fun `installment round-trips losslessly, including the accrual and payment flags`() {
        val installment = LoanInstallment(
            id = UUID.randomUUID(),
            loanId = LoanId.random(),
            number = 3,
            dueDate = firstDue.plusMonths(2),
            openingBalance = eur("10098.16"),
            principal = eur("965.21"),
            interest = eur("100.98"),
            payment = eur("1066.19"),
            closingBalance = eur("9132.95"),
            paid = true,
            paidAt = createdAt.plusMonths(2),
            interestAccrued = true,
            accruedAt = createdAt.plusMonths(2).minusDays(3),
        )

        val entity = mapper.toEntity(installment)
        // All five money columns share the installment's (single) currency.
        assertThat(entity.currency).isEqualTo("EUR")
        assertThat(entity.openingBalance).isEqualTo(BigDecimal("10098.16"))
        assertThat(entity.interest).isEqualTo(BigDecimal("100.98"))
        assertThat(entity.paid).isTrue()
        assertThat(entity.interestAccrued).isTrue()

        assertThat(mapper.toDomain(entity)).isEqualTo(installment)
    }

    @Test
    fun `unpaid unaccrued installment round-trips with null timestamps`() {
        val installment = LoanInstallment(
            id = UUID.randomUUID(),
            loanId = LoanId.random(),
            number = 1,
            dueDate = firstDue,
            openingBalance = eur("12000.00"),
            principal = eur("946.19"),
            interest = eur("120.00"),
            payment = eur("1066.19"),
            closingBalance = eur("11053.81"),
        )

        val roundTripped = mapper.toDomain(mapper.toEntity(installment))

        assertThat(roundTripped).isEqualTo(installment)
        assertThat(roundTripped.paidAt).isNull()
        assertThat(roundTripped.accruedAt).isNull()
    }

    @Test
    fun `collateral round-trips losslessly, including type, haircut and valuation time`() {
        val item = Collateral(
            id = CollateralId.random(),
            loanId = LoanId.random(),
            type = CollateralType.REAL_ESTATE,
            description = "apartment, Prague 7",
            marketValue = eur("250000.00"),
            haircut = BigDecimal("0.20"),
            valuedAt = createdAt,
            registeredBy = "officer-1",
            createdAt = createdAt,
        )

        val entity = mapper.toEntity(item)
        assertThat(entity.marketValue).isEqualTo(BigDecimal("250000.00"))
        assertThat(entity.currency).isEqualTo("EUR")
        assertThat(entity.type).isEqualTo(CollateralType.REAL_ESTATE)

        assertThat(mapper.toDomain(entity)).isEqualTo(item)
    }

    @Test
    fun `collateral with no description round-trips the null`() {
        val item = Collateral(
            id = CollateralId.random(),
            loanId = LoanId.random(),
            type = CollateralType.GUARANTEE,
            description = null,
            marketValue = eur("5000.00"),
            haircut = BigDecimal.ZERO,
            valuedAt = createdAt,
            registeredBy = "officer-1",
            createdAt = createdAt,
        )

        assertThat(mapper.toDomain(mapper.toEntity(item))).isEqualTo(item)
    }

    @Test
    fun `collateral four-eyes decision fields round-trip losslessly (ADR-0028 follow-up, issue #621)`() {
        val item = Collateral(
            id = CollateralId.random(),
            loanId = LoanId.random(),
            type = CollateralType.VEHICLE,
            marketValue = eur("5000.00"),
            haircut = BigDecimal("0.40"),
            valuedAt = createdAt,
            status = CollateralStatus.APPROVED,
            registeredBy = "officer-1",
            decidedBy = "risk-1",
            decidedAt = createdAt,
            createdAt = createdAt,
        )

        val entity = mapper.toEntity(item)
        assertThat(entity.status).isEqualTo(CollateralStatus.APPROVED)
        assertThat(entity.registeredBy).isEqualTo("officer-1")
        assertThat(entity.decidedBy).isEqualTo("risk-1")

        assertThat(mapper.toDomain(entity)).isEqualTo(item)
    }

    @Test
    fun `provisioning record round-trips losslessly, including stage, bucket and period`() {
        val record = LoanProvisioningRecord(
            id = UUID.randomUUID(),
            loanId = LoanId.random(),
            period = "2026-06",
            asOf = LocalDate.parse("2026-06-01"),
            outstandingBalance = eur("11053.81"),
            daysPastDue = 45,
            bucket = DelinquencyBucket.DPD_31_60,
            stage = Ifrs9Stage.STAGE_2,
            expectedCreditLoss = eur("221.08"),
            createdAt = createdAt,
            modelVersion = "test-model-v1",
        )

        val entity = mapper.toEntity(record)
        assertThat(entity.outstandingBalance).isEqualTo(BigDecimal("11053.81"))
        assertThat(entity.currency).isEqualTo("EUR")
        assertThat(entity.daysPastDue).isEqualTo(45)
        assertThat(entity.bucket).isEqualTo(DelinquencyBucket.DPD_31_60)
        assertThat(entity.stage).isEqualTo(Ifrs9Stage.STAGE_2)
        assertThat(entity.expectedCreditLoss).isEqualTo(BigDecimal("221.08"))

        assertThat(mapper.toDomain(entity)).isEqualTo(record)
    }
}
