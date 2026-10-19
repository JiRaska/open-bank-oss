// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.valuation

import com.openbank.risk.domain.curve.ReferenceCurveSet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class HistoricalVarTest {

    @Test
    fun `VaR is the ceil(alpha n)-th smallest loss with no interpolation, and ES averages the tail`() {
        // P&L −1 … −100: losses 1 … 100. α = 0.99, n = 100 → k = 99.
        val pnl = (1..100).map { BigDecimal(-it) }.shuffled(java.util.Random(7))
        val f = HistoricalVar.figures(pnl, BigDecimal("0.99"))
        assertThat(f.valueAtRisk).isEqualByComparingTo("99")
        assertThat(f.expectedShortfall).isEqualByComparingTo("99.5")
        // n = 250 → k = ⌈247.5⌉ = 248; losses 1 … 250 → VaR 248, ES = mean(248, 249, 250) = 249.
        val f250 = HistoricalVar.figures((1..250).map { BigDecimal(-it) }, BigDecimal("0.99"))
        assertThat(f250.valueAtRisk).isEqualByComparingTo("248")
        assertThat(f250.expectedShortfall).isEqualByComparingTo("249")
        assertThat(f250.expectedShortfall).isGreaterThanOrEqualTo(f250.valueAtRisk)
    }

    @Test
    fun `gains only give a negative VaR, ES still at or above it`() {
        val f = HistoricalVar.figures(listOf("5", "7", "9").map(::BigDecimal), BigDecimal("0.95"))
        assertThat(f.valueAtRisk).isEqualByComparingTo("-5")
        assertThat(f.expectedShortfall).isGreaterThanOrEqualTo(f.valueAtRisk)
    }

    @Test
    fun `a scenario moves today's curves by the historical zero-rate change and FX by its ratio`() {
        val d0 = LocalDate.parse("2026-09-28")
        val d1 = LocalDate.parse("2026-09-29")
        val today = LocalDate.parse("2026-09-30")
        val base = MarketState(
            today,
            ReferenceCurveSet.build(today, Instant.EPOCH).curves,
            "CZK",
            mapOf(
                "EUR" to BigDecimal("25"),
            ),
        )
        val a =
            HistoricalMarket(d0, ReferenceCurveSet.build(d0, Instant.EPOCH).curves, mapOf("EUR" to BigDecimal("24")))
        val shifted = ReferenceCurveSet.build(d1, Instant.EPOCH).curves.mapValues {
            it.value.parallelShift(BigDecimal(10))
        }
        val b = HistoricalMarket(d1, shifted, mapOf("EUR" to BigDecimal("24.24")))
        val s = HistoricalVar.shocked(base, a, b)!!
        val date = today.plusYears(3)
        val idx = com.openbank.risk.domain.curve.CurveIndex.CZEONIA
        assertThat(
            s.curves.getValue(idx).zeroRate(date).subtract(base.curves.getValue(idx).zeroRate(date)).abs()
                .subtract(BigDecimal("0.0010")).abs(),
        ).isLessThan(BigDecimal("0.00005"))
        assertThat(s.fxToReporting.getValue("EUR")).isEqualByComparingTo("25.25")
        assertThat(HistoricalVar.shocked(base, a, b.copy(fxToReporting = emptyMap()))).isNull()
        assertThat(
            HistoricalVar.pairs(listOf(a, b, HistoricalMarket(today, base.curves, base.fxToReporting)), 1),
        ).hasSize(2)
        assertThat(HistoricalVar.pairs(listOf(a, b), 2)).isEmpty()
    }
}
