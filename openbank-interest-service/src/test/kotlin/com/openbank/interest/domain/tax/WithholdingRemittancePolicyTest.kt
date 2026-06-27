// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.domain.tax

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Pure unit tests for the ADR-0038 remittance assembly. Verifies the §38d selection (only tax
 * actually withheld, in CZK, RECORDED, for the target month), aggregation, and the due-date rule
 * (end of the following month). No framework, no boot.
 */
class WithholdingRemittancePolicyTest {

    private val fixedNow = OffsetDateTime.parse("2026-02-01T00:00:00Z")

    private fun record(
        taxAmount: BigDecimal,
        treatment: WithholdingTreatment = WithholdingTreatment.WITHHELD,
        currency: String = "CZK",
        status: WithholdingTaxStatus = WithholdingTaxStatus.RECORDED,
        periodTo: LocalDate = LocalDate.of(2026, 1, 31),
    ) = WithholdingTax(
        id = UUID.randomUUID(),
        capitalizationId = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        periodFrom = periodTo.withDayOfMonth(1),
        periodTo = periodTo,
        taxableBase = BigDecimal("100"),
        rate = BigDecimal("0.15"),
        taxAmount = taxAmount,
        currency = currency,
        treatment = treatment,
        status = status,
        createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `aggregates the withheld CZK tax for the target month`() {
        val records = listOf(
            record(BigDecimal("15")),
            record(BigDecimal("30")),
            record(BigDecimal("55")),
        )

        val r = WithholdingRemittancePolicy.assemble(records, 2026, 1, fixedNow)

        assertThat(r.periodYear).isEqualTo(2026)
        assertThat(r.periodMonth).isEqualTo(1)
        assertThat(r.itemCount).isEqualTo(3)
        assertThat(r.totalTaxAmount).isEqualByComparingTo("100")
        assertThat(r.withholdingIds).hasSize(3)
        assertThat(r.currency).isEqualTo("CZK")
        assertThat(r.authority).isEqualTo("CZ-FU")
    }

    @Test
    fun `due date is the end of the month following the withholding month`() {
        // January withholding -> due end of February (2028 is a leap year -> Feb 29).
        assertThat(WithholdingRemittancePolicy.dueDate(2028, 1)).isEqualTo(LocalDate.of(2028, 2, 29))
        // December withholding -> due end of next January.
        assertThat(WithholdingRemittancePolicy.dueDate(2026, 12)).isEqualTo(LocalDate.of(2027, 1, 31))
    }

    @Test
    fun `excludes non-withheld, non-CZK, already-remitted and out-of-period records`() {
        val records = listOf(
            record(BigDecimal("20")), // remittable
            record(BigDecimal("99"), treatment = WithholdingTreatment.NOT_WITHHELD),
            record(BigDecimal("99"), treatment = WithholdingTreatment.EXEMPT),
            record(BigDecimal("99"), treatment = WithholdingTreatment.DEFERRED_FX, currency = "EUR"),
            record(BigDecimal("99"), status = WithholdingTaxStatus.REMITTED),
            record(BigDecimal("99"), status = WithholdingTaxStatus.REVERSED),
            record(BigDecimal("99"), periodTo = LocalDate.of(2026, 2, 28)), // wrong month
        )

        val r = WithholdingRemittancePolicy.assemble(records, 2026, 1, fixedNow)

        assertThat(r.itemCount).isEqualTo(1)
        assertThat(r.totalTaxAmount).isEqualByComparingTo("20")
    }

    @Test
    fun `an empty period yields a zero nil return`() {
        val r = WithholdingRemittancePolicy.assemble(emptyList(), 2026, 3, fixedNow)

        assertThat(r.itemCount).isEqualTo(0)
        assertThat(r.totalTaxAmount).isEqualByComparingTo("0")
        assertThat(r.withholdingIds).isEmpty()
        assertThat(r.dueDate).isEqualTo(LocalDate.of(2026, 4, 30))
    }
}
