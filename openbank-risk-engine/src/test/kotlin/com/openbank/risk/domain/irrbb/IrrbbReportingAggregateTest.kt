// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.irrbb

import com.openbank.risk.domain.capital.FxRateUsed
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class IrrbbReportingAggregateTest {

    private val eurCzk = FxRateUsed("EUR", BigDecimal("25"), LocalDate.parse("2026-09-30"), "CNB")

    private fun cs(currency: String, deltaEve: String) = CurrencyScenario(
        currency,
        BigDecimal("1000"),
        BigDecimal("1000").add(BigDecimal(deltaEve)),
        BigDecimal(deltaEve),
        null,
    )

    private fun result(
        up: List<CurrencyScenario>,
        down: List<CurrencyScenario>,
        aggregationCurrency: String? = null,
        shockNotConfigured: List<String> = emptyList(),
    ) = IrrbbResult(
        gaps = emptyList(),
        scenarios = listOf(
            ScenarioResult(ShockScenario.PARALLEL_UP, up, null),
            ScenarioResult(ShockScenario.PARALLEL_DOWN, down, null),
        ),
        shockNotConfigured = shockNotConfigured,
        unpriced = emptyList(),
        aggregationCurrency = aggregationCurrency,
        worstScenario = null,
        worstLoss = null,
        worstByCurrency = emptyMap(),
    )

    @Test
    fun `sums currency losses in CZK at the fixing, drops gains, and takes the worst scenario`() {
        // parallel-up: CZK loses 100, EUR loses 10 (= 250 CZK) -> 350. parallel-down: CZK gains, EUR loses 2 -> 50.
        val r = result(
            up = listOf(cs("CZK", "-100"), cs("EUR", "-10")),
            down = listOf(cs("CZK", "80"), cs("EUR", "-2")),
        )
        val agg = IrrbbReportingAggregate.of(r, mapOf("EUR" to eurCzk))!!
        assertThat(agg.notStated).isNull()
        assertThat(agg.currency).isEqualTo("CZK")
        assertThat(agg.scenarios.map { it.loss }).containsExactly(BigDecimal("350.00"), BigDecimal("50.00"))
        assertThat(agg.worstScenario).isEqualTo(ShockScenario.PARALLEL_UP)
        assertThat(agg.worstLoss).isEqualByComparingTo("350")
        assertThat(agg.fxRates).containsExactly(eurCzk)
    }

    @Test
    fun `a missing fixing leaves the total unstated, never a partial sum`() {
        val agg = IrrbbReportingAggregate.of(
            result(listOf(cs("CZK", "-100"), cs("EUR", "-10")), emptyList()),
            emptyMap(),
        )!!
        assertThat(agg.scenarios).isEmpty()
        assertThat(agg.worstLoss).isNull()
        assertThat(agg.notStated).contains("EUR")
    }

    @Test
    fun `an unevaluated currency leaves the total unstated`() {
        val agg = IrrbbReportingAggregate.of(
            result(listOf(cs("EUR", "-10")), emptyList(), shockNotConfigured = listOf("USD")),
            mapOf("EUR" to eurCzk),
        )!!
        assertThat(agg.notStated).contains("USD")
    }

    @Test
    fun `a single-currency book keeps its own aggregate and gets none here`() {
        assertThat(
            IrrbbReportingAggregate.of(result(listOf(cs("EUR", "-10")), emptyList(), "EUR"), emptyMap()),
        ).isNull()
    }
}
