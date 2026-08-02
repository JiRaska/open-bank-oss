// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.repository

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The SQL is only half the answer (issue #3294). This is the other half: the fold that turns
 * `(status, currency, count, …)` tuples into one entry per status.
 *
 * It is tested directly rather than only through the container IT because the failure mode it
 * guards is silent and arithmetic — adding CZK to EUR produces a number that looks authoritative,
 * passes every type check, and is wrong. A container test would happily agree with it as long as
 * the fixture used one currency, which is exactly what a sandbox fixture tends to do.
 */
class LendingSummaryFoldTest {

    private fun ts(day: Int) = OffsetDateTime.of(2026, 8, day, 12, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun `keeps money separate per currency instead of summing across them`() {
        val rows: List<Array<Any?>> = listOf(
            arrayOf("ASSESSMENT", "CZK", 2L, ts(1), BigDecimal("500000.00")),
            arrayOf("ASSESSMENT", "EUR", 1L, ts(2), BigDecimal("20000.00")),
        )

        val out = foldApplicationSummaries(rows)

        assertThat(out).hasSize(1)
        assertThat(out[0].status).isEqualTo("ASSESSMENT")
        assertThat(out[0].count).isEqualTo(3L)
        // Two totals, not 520000 of nothing.
        assertThat(out[0].requested.map { it.currency }).containsExactly("CZK", "EUR")
        assertThat(out[0].requested.first { it.currency == "CZK" }.amount).isEqualByComparingTo("500000.00")
        assertThat(out[0].requested.first { it.currency == "EUR" }.amount).isEqualByComparingTo("20000.00")
    }

    @Test
    fun `takes the OLDEST timestamp across a state's currency groups`() {
        val rows: List<Array<Any?>> = listOf(
            arrayOf("FOUR_EYES", "CZK", 1L, ts(9), BigDecimal.ONE),
            arrayOf("FOUR_EYES", "EUR", 1L, ts(3), BigDecimal.ONE),
        )

        // The desk acts on the item that has waited longest. Taking the first group's timestamp —
        // or the newest — would hide it behind whichever currency happened to sort first.
        assertThat(foldApplicationSummaries(rows)[0].oldestCreatedAt).isEqualTo(ts(3))
    }

    @Test
    fun `a CHAR(3) currency column is trimmed, so CZK and 'CZK ' are one bucket`() {
        // loan_application.currency is CHAR(3): the driver can hand back a space-padded value, and
        // an untrimmed key would split one currency into two totals that each look too small.
        val rows: List<Array<Any?>> = listOf(
            arrayOf("SUBMITTED", "CZK", 1L, ts(1), BigDecimal("10.00")),
            arrayOf("SUBMITTED", "CZK ", 1L, ts(1), BigDecimal("5.00")),
        )

        val requested = foldApplicationSummaries(rows)[0].requested
        assertThat(requested.map { it.currency }.distinct()).containsExactly("CZK")
    }

    @Test
    fun `an empty result is an empty summary, not a fabricated zero row`() {
        assertThat(foldApplicationSummaries(emptyList())).isEmpty()
        assertThat(foldLoanSummaries(emptyList())).isEmpty()
    }

    @Test
    fun `folds loan rows the same way`() {
        val rows: List<Array<Any?>> = listOf(
            arrayOf("ACTIVE", "CZK", 4L, BigDecimal("1000000.00")),
            arrayOf("DELINQUENT", "CZK", 1L, BigDecimal("250000.00")),
        )

        val out = foldLoanSummaries(rows)

        assertThat(out.map { it.status }).containsExactly("ACTIVE", "DELINQUENT")
        assertThat(out.first { it.status == "DELINQUENT" }.count).isEqualTo(1L)
        assertThat(out.first { it.status == "ACTIVE" }.principal[0].amount).isEqualByComparingTo("1000000.00")
    }
}
