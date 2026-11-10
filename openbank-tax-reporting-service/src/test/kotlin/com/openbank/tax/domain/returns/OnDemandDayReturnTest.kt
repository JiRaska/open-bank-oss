// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.domain.returns

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.tax.domain.model.TaxValidationException
import com.openbank.tax.infrastructure.returns.CatalogueParser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** #15356: the GSFT relevant day (Periodicity.DAY) and its working-day deadline. */
class OnDemandDayReturnTest {
    private fun day(s: String) = LocalDate.parse(s)

    private val gsft = CatalogueParser.parse(ObjectMapper(), "statutory-returns/cz/gsft.v1.json")

    @Test
    fun `any calendar day, a weekend included, is a relevant day`() {
        val saturday = ReportingPeriod(Periodicity.DAY, day("2026-11-07"))
        assertThat(saturday.label).isEqualTo("2026-11-07")
        assertThat(saturday.previous().endDate).isEqualTo(day("2026-11-06"))
        assertThat(ReportingPeriod.containing(Periodicity.DAY, day("2026-11-07"))).isEqualTo(saturday)
        assertThat(ReportingPeriod.parse(Periodicity.DAY, "2026-11-07")).isEqualTo(saturday)
        assertThatThrownBy { ReportingPeriod.parse(Periodicity.DAY, "2026-11") }
            .isInstanceOf(TaxValidationException::class.java)
    }

    @Test
    fun `the GSFT deadline is the third Czech working day after the relevant day`() {
        val test = gsft.definition("GSFT-DIW-TEST")!!
        assertThat(test.deadlineBasis).isEqualTo(DeadlineBasis.WORKING_DAYS)
        assertThat(test.onDemand).isTrue()
        // Monday -> Thursday.
        assertThat(test.dueDate(ReportingPeriod(Periodicity.DAY, day("2026-11-02")))).isEqualTo(day("2026-11-05"))
        // Friday 13 Nov: Mon 16, (Tue 17 Nov is a public holiday), Wed 18, Thu 19.
        assertThat(test.dueDate(ReportingPeriod(Periodicity.DAY, day("2026-11-13")))).isEqualTo(day("2026-11-19"))
        // A Saturday relevant day: Mon, Tue, Wed.
        assertThat(test.dueDate(ReportingPeriod(Periodicity.DAY, day("2026-11-07")))).isEqualTo(day("2026-11-11"))
    }

    @Test
    fun `calendar-day deadlines are unchanged`() {
        val monthly = ReturnDefinition(
            "M",
            "m",
            ReturnScope.COMPANY,
            Periodicity.MONTH,
            20,
            listOf("x"),
            emptyList(),
            "t",
        )
        assertThat(monthly.dueDate(ReportingPeriod(Periodicity.MONTH, day("2026-10-31")))).isEqualTo(day("2026-11-20"))
    }

    @Test
    fun `both GSFT returns hold every figure non-negative and carry the package line schema`() {
        gsft.returns.forEach { definition ->
            assertThat(definition.lineSchema).containsExactly("kind", "name", "sha256", "size")
            val figures = definition.datapoints.associateWith { BigDecimal.ONE }
            assertThat(definition.validate(figures)).isEmpty()
            assertThat(definition.validate(figures + ("N_CLIENT" to BigDecimal("-1"))).map { it.ruleId })
                .containsExactly("gsft-figures-non-negative")
        }
        assertThat(gsft.returns.map { it.code }).containsExactly("GSFT-DIW-TEST", "GSFT-DIW-LIVE")
    }
}
