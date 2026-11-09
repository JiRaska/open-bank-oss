// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.domain

import com.openbank.pensionfund.domain.model.ManagementFeeAccrual
import com.openbank.pensionfund.domain.model.NavFigures
import com.openbank.pensionfund.domain.model.NavRecord
import com.openbank.pensionfund.domain.model.NavStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ManagementFeeAccrualTest {
    private fun nav(fee: String = "12.34") = NavRecord(
        id = UUID.randomUUID(),
        fundId = UUID.randomUUID(),
        valuationDate = LocalDate.of(2026, 11, 1),
        figures = NavFigures(
            grossAssets = BigDecimal("1000.00"),
            accruedManagementFee = BigDecimal(fee),
            otherLiabilities = BigDecimal.ZERO,
            netAssets = BigDecimal("1000.00") - BigDecimal(fee),
            unitsOutstanding = BigDecimal("1000.00"),
            navPerUnit = BigDecimal("1.00"),
        ),
        status = NavStatus.PUBLISHED,
        calculatedBy = "nav-maker",
        calculatedAt = Instant.parse("2026-11-02T05:00:00Z"),
        approvedBy = "nav-checker",
        publishedAt = Instant.parse("2026-11-02T06:00:00Z"),
    )

    @Test
    fun `ordinary publication retains the approved fee and stable source identity`() {
        val source = nav()
        val accrual = ManagementFeeAccrual.from(source, "CZK")
        assertThat(accrual.navId).isEqualTo(source.id)
        assertThat(accrual.fundId).isEqualTo(source.fundId)
        assertThat(accrual.valuationDate).isEqualTo(source.valuationDate)
        assertThat(accrual.amount).isEqualByComparingTo("12.34")
        assertThat(accrual.currency).isEqualTo("CZK")
        assertThat(accrual.approvedBy).isEqualTo("nav-checker")
        assertThat(accrual.correctsNavId).isNull()
        assertThat(accrual.idempotencyKey).isEqualTo("pension-management-fee:${source.id}")
        assertThat(ManagementFeeAccrual.from(source, "CZK").idempotencyKey).isEqualTo(accrual.idempotencyKey)
    }

    @Test
    fun `a decreased correction accrues only the negative difference`() {
        val original = nav("12.34")
        val correction = original.copy(
            id = UUID.randomUUID(),
            correctsNavId = original.id,
            figures = original.figures.copy(accruedManagementFee = BigDecimal("10.00")),
        )
        val accrual = ManagementFeeAccrual.from(correction, "CZK", original)
        assertThat(accrual.amount).isEqualByComparingTo("-2.34")
        assertThat(accrual.correctsNavId).isEqualTo(original.id)
        assertThat(accrual.idempotencyKey).isEqualTo("pension-management-fee:${correction.id}")
    }

    @Test
    fun `zero fees and unchanged corrections remain explicit zero accruals`() {
        assertThat(ManagementFeeAccrual.from(nav("0.00"), "CZK").amount).isZero()
        val original = nav()
        val correction = original.copy(id = UUID.randomUUID(), correctsNavId = original.id)
        assertThat(ManagementFeeAccrual.from(correction, "CZK", original).amount).isZero()
    }

    @Test
    fun `correction requires the referenced original and ordinary publication refuses one`() {
        val original = nav()
        val correction = original.copy(id = UUID.randomUUID(), correctsNavId = original.id)
        assertThatThrownBy { ManagementFeeAccrual.from(correction, "CZK") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ManagementFeeAccrual.from(original, "CZK", original) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `correction refuses a different original id fund or valuation date`() {
        val original = nav()
        val correction = original.copy(id = UUID.randomUUID(), correctsNavId = original.id)
        for (wrong in listOf(
            original.copy(id = UUID.randomUUID()),
            original.copy(fundId = UUID.randomUUID()),
            original.copy(valuationDate = original.valuationDate.minusDays(1)),
        )) {
            assertThatThrownBy { ManagementFeeAccrual.from(correction, "CZK", wrong) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `unpublished NAVs and missing or self approval cannot create fee evidence`() {
        val source = nav()
        val invalid = NavStatus.entries.filter { it != NavStatus.PUBLISHED }.map { source.copy(status = it) } +
            listOf(
                source.copy(approvedBy = null),
                source.copy(approvedBy = " "),
                source.copy(approvedBy = source.calculatedBy),
            )
        invalid.forEach {
            assertThatThrownBy { ManagementFeeAccrual.from(it, "CZK") }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `a foreign currency cannot reuse the initial CZK accounting policy`() {
        assertThatThrownBy { ManagementFeeAccrual.from(nav(), "invalid") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
