// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.domain.returns

import com.openbank.tax.domain.model.TaxConflictException
import com.openbank.tax.domain.model.TaxValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** ADR-0367: the WORKING_DAY period (CEÚ) and the regulator acknowledgement on the ADR-0336 lifecycle. */
class WorkingDayReturnTest {
    private fun day(s: String) = LocalDate.parse(s)

    @Test
    fun `a working-day window ends the day before a CERTIS working day`() {
        // Sunday 2026-11-08 ends Monday's window; Friday 2026-11-06 ends nothing (Saturday is no working day).
        assertThat(ReportingPeriod(Periodicity.WORKING_DAY, day("2026-11-08")).label).isEqualTo("2026-11-08")
        assertThatThrownBy { ReportingPeriod(Periodicity.WORKING_DAY, day("2026-11-06")) }
            .isInstanceOf(TaxValidationException::class.java)
        // 17 November is a Czech public holiday (Tuesday 2026): Monday 16th ends no window.
        assertThatThrownBy { ReportingPeriod(Periodicity.WORKING_DAY, day("2026-11-16")) }
            .isInstanceOf(TaxValidationException::class.java)
    }

    @Test
    fun `previous window skips weekends and holidays`() {
        val mondayWindow = ReportingPeriod(Periodicity.WORKING_DAY, day("2026-11-08"))
        assertThat(mondayWindow.previous().endDate).isEqualTo(day("2026-11-05"))
        val wednesdayAfterHoliday = ReportingPeriod(Periodicity.WORKING_DAY, day("2026-11-17"))
        assertThat(wednesdayAfterHoliday.previous().endDate).isEqualTo(day("2026-11-15"))
    }

    @Test
    fun `containing, lastEndedBefore and parse agree`() {
        assertThat(ReportingPeriod.containing(Periodicity.WORKING_DAY, day("2026-11-06")).endDate)
            .isEqualTo(day("2026-11-08"))
        assertThat(ReportingPeriod.lastEndedBefore(Periodicity.WORKING_DAY, day("2026-11-09")).endDate)
            .isEqualTo(day("2026-11-08"))
        assertThat(ReportingPeriod.parse(Periodicity.WORKING_DAY, "2026-11-08").endDate).isEqualTo(day("2026-11-08"))
        assertThatThrownBy { ReportingPeriod.parse(Periodicity.WORKING_DAY, "2026-11") }
            .isInstanceOf(TaxValidationException::class.java)
    }

    private val definition = ReturnDefinition(
        code = "CEUSIFE10",
        name = "CEÚ",
        scope = ReturnScope.COMPANY,
        periodicity = Periodicity.WORKING_DAY,
        deadlineDaysAfterPeriodEnd = 1,
        datapoints = listOf("changes_total"),
        rules = emptyList(),
        legalBasis = "300/2016",
    )
    private val catalogue = ReturnCatalogue("cz-ceu", 1, "CZ", false, listOf(definition))
    private val at = Instant.parse("2026-11-09T06:00:00Z")

    private fun submitted(): StatutoryReturn = StatutoryReturn.assemble(
        catalogue,
        definition,
        "company",
        ReportingPeriod(Periodicity.WORKING_DAY, day("2026-11-08")),
        1,
        mapOf("changes_total" to BigDecimal.ONE),
        by = "system:ceu-daily-overview",
        at = at,
    ).approve("alice", at).submit("SDAT-1", "alice", at)

    @Test
    fun `due on the working day itself, and acknowledged returns are filed`() {
        val s = submitted()
        assertThat(s.dueDate).isEqualTo(day("2026-11-09"))
        val ack = s.acknowledge(true, "CNB-ACK-42", "bob", at)
        assertThat(ack.status).isEqualTo(ReturnStatus.ACKNOWLEDGED)
        assertThat(ack.acknowledgement).isEqualTo("CNB-ACK-42")
        assertThat(ack.status.filed).isTrue()
        assertThat(ack.isOverdueAt(day("2026-11-20"))).isFalse()
    }

    @Test
    fun `a rejection is not filed, is overdue past the due date and allows a correction`() {
        val rejected = submitted().acknowledge(false, "XSD validation failed", "bob", at)
        assertThat(rejected.status).isEqualTo(ReturnStatus.REJECTED)
        assertThat(rejected.status.filed).isFalse()
        assertThat(rejected.status.closed).isTrue()
        assertThat(rejected.isOverdueAt(day("2026-11-10"))).isTrue()
    }

    @Test
    fun `only a SUBMITTED revision can be acknowledged, and a reason is required`() {
        val assembled = StatutoryReturn.assemble(
            catalogue,
            definition,
            "company",
            ReportingPeriod(Periodicity.WORKING_DAY, day("2026-11-08")),
            1,
            mapOf("changes_total" to BigDecimal.ONE),
            by = "system:ceu-daily-overview",
            at = at,
        )
        assertThatThrownBy {
            assembled.acknowledge(true, "X", "bob", at)
        }.isInstanceOf(TaxConflictException::class.java)
        assertThatThrownBy { submitted().acknowledge(false, " ", "bob", at) }
            .isInstanceOf(TaxValidationException::class.java)
    }
}
