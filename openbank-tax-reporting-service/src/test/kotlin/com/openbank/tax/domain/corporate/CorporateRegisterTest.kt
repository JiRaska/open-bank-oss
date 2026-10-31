// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.tax.domain.corporate

import com.openbank.tax.domain.model.TaxConflictException
import com.openbank.tax.domain.model.TaxValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CorporateRegisterTest {
    private val at = Instant.parse("2026-01-05T10:00:00Z")

    private fun entry(
        fact: CorporateFact,
        value: String,
        effective: String,
        version: Int = 1,
        approved: Boolean = true,
    ): CorporateRegisterEntry {
        val proposed = CorporateRegisterEntry(
            id = UUID.randomUUID(), entityId = "company", fact = fact, value = BigDecimal(value),
            effectiveFrom = LocalDate.parse(effective), version = version, reason = "r", evidence = "minutes",
            proposedBy = "maker", proposedAt = at,
        )
        return if (approved) proposed.approve("checker", at) else proposed
    }

    private val q4Start = LocalDate.parse("2025-10-01")
    private val q4End = LocalDate.parse("2025-12-31")

    @Test
    fun `a stock fact reads the latest approved entry on or before the period end, highest version first`() {
        val entries = listOf(
            entry(CorporateFact.SHARE_CAPITAL, "50000000", "2020-01-01"),
            entry(CorporateFact.SHARE_CAPITAL, "60000000", "2025-06-30"),
            entry(CorporateFact.SHARE_CAPITAL, "61000000", "2025-06-30", version = 2),
            // A later-dated change is not yet in force at 31 December.
            entry(CorporateFact.SHARE_CAPITAL, "90000000", "2026-03-01"),
            // A proposal never counts.
            entry(CorporateFact.SHARE_CAPITAL, "70000000", "2025-12-01", approved = false),
        )
        assertThat(effectiveEntry(entries, CorporateFact.SHARE_CAPITAL, q4Start, q4End)!!.value)
            .isEqualByComparingTo("61000000")
    }

    @Test
    fun `a period fact counts only inside the reported period — last year's dividend is not this year's`() {
        val entries = listOf(entry(CorporateFact.DIVIDEND_PAID_OR_PLANNED, "1000000", "2024-05-31"))
        val year = LocalDate.parse("2025-01-01") to LocalDate.parse("2025-12-31")
        assertThat(effectiveEntry(entries, CorporateFact.DIVIDEND_PAID_OR_PLANNED, year.first, year.second)).isNull()
        val withThisYear = entries + entry(CorporateFact.DIVIDEND_PAID_OR_PLANNED, "0", "2025-05-30")
        assertThat(
            effectiveEntry(withThisYear, CorporateFact.DIVIDEND_PAID_OR_PLANNED, year.first, year.second)!!.value,
        )
            .isEqualByComparingTo("0")
    }

    @Test
    fun `the proposer cannot decide their own entry, and a decided entry cannot be decided again`() {
        val proposed = entry(CorporateFact.EMPLOYEES_COUNT, "12", "2025-01-01", approved = false)
        assertThatThrownBy { proposed.approve("maker", at) }.isInstanceOf(TaxConflictException::class.java)
            .hasMessageContaining("Four-eyes")
        assertThatThrownBy { proposed.reject("maker", at) }.isInstanceOf(TaxConflictException::class.java)
        val approved = proposed.approve("checker", at)
        assertThatThrownBy { approved.reject("other", at) }.isInstanceOf(TaxConflictException::class.java)
    }

    @Test
    fun `negative figures, fractional counts and an entry without evidence are refused`() {
        assertThatThrownBy { entry(CorporateFact.REGULATORY_CAPITAL, "-1", "2025-01-01") }
            .isInstanceOf(TaxValidationException::class.java)
        assertThatThrownBy { entry(CorporateFact.EMPLOYEES_COUNT, "12.5", "2025-01-01") }
            .isInstanceOf(TaxValidationException::class.java)
        assertThat(entry(CorporateFact.EMPLOYEES_COUNT, "12.000", "2025-01-01").value).isEqualByComparingTo("12")
        assertThatThrownBy {
            CorporateRegisterEntry(
                UUID.randomUUID(), "company", CorporateFact.SHARE_CAPITAL, BigDecimal.ONE, LocalDate.EPOCH, 1,
                "r", " ", "maker", at,
            )
        }.isInstanceOf(TaxValidationException::class.java)
    }
}
