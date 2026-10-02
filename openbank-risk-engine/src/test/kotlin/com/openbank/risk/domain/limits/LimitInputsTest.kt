// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.limits

import com.openbank.risk.domain.Fixtures
import com.openbank.risk.domain.capital.CapitalTestParameters
import com.openbank.risk.domain.capital.CreditRiskCapital
import com.openbank.risk.domain.irrbb.IrrbbResult
import com.openbank.risk.domain.irrbb.ShockScenario
import com.openbank.risk.domain.liquidity.Liquidity
import com.openbank.risk.domain.liquidity.LiquidityTestParameters
import com.openbank.risk.domain.model.Instrument
import com.openbank.risk.domain.model.InstrumentKind
import com.openbank.risk.domain.model.Position
import com.openbank.risk.domain.model.PositionKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Each limit's input is the engine's OWN figure (asserted equal to the read it comes from), and every
 * gap in that input makes the metric a [MetricInput.Gap] — the NOT_EVALUABLE path of each limit.
 */
class LimitInputsTest {

    private fun gl(code: String, type: String, amount: String, ccy: String = "CZK") =
        Position(PositionKind.GL_ACCOUNT, code, type, ccy, null, BigDecimal(amount))

    private fun deal(id: String, code: String, amount: String) =
        Position(PositionKind.TREASURY_DEAL, code, "ASSET", "CZK", null, BigDecimal(amount), id)

    private fun instrument(id: String, code: String, amount: String, counterparty: String?) = Instrument(
        id, InstrumentKind.MONEY_MARKET_DEAL, code, "CZK",
        BigDecimal(
            amount,
        ),
        null, null, null, counterparty, null, null,
    )

    /** A clean little book: every balance classified, own funds in CZK, bank exposures attributed. */
    private val book = listOf(
        gl("1510", "ASSET", "1000"),
        deal("D1", "1500", "300"),
        deal("D2", "1500", "200"),
        deal("D3", "1501", "100"),
        gl("2300", "LIABILITY", "-500"),
        gl("6000", "EQUITY", "-1100"),
    )
    private val instruments = listOf(
        instrument("D1", "1500", "300", "BANK-A"),
        instrument("D2", "1500", "200", "BANK-A"),
        instrument("D3", "1501", "100", "BANK-B"),
    )

    private fun liquidity(positions: List<Position>) =
        Liquidity.compute(positions, instruments, Fixtures.AS_OF, LiquidityTestParameters.shipped())

    private fun capital(positions: List<Position>, instr: List<Instrument> = instruments) =
        CreditRiskCapital.compute(positions, instr, CapitalTestParameters.shipped(), emptyMap(), Fixtures.AS_OF)

    private fun measured(input: MetricInput): BigDecimal = (input as MetricInput.Measured).value

    private fun gap(input: MetricInput): String = (input as MetricInput.Gap).reason

    @Test
    fun `LCR and NSFR are the liquidity read's own CZK-total ratios`() {
        val result = liquidity(book)
        assertThat(measured(LimitInputs.lcr(result))).isEqualByComparingTo(result.total!!.lcr.ratio)
        assertThat(measured(LimitInputs.nsfr(result))).isEqualByComparingTo(result.total!!.nsfr.ratio)
    }

    @Test
    fun `an unclassified balance makes LCR and NSFR not evaluable, naming the account`() {
        val result = liquidity(book + gl("1000", "ASSET", "50"))
        assertThat(gap(LimitInputs.lcr(result))).contains("not classified for liquidity").contains("GL 1000")
        assertThat(gap(LimitInputs.nsfr(result))).contains("GL 1000")
    }

    @Test
    fun `a currency with no fixing leaves no CZK total, so no liquidity limit is evaluated`() {
        val result = liquidity(book + gl("1510", "ASSET", "10", "EUR"))
        assertThat(gap(LimitInputs.lcr(result))).contains("no CZK liquidity total")
    }

    @Test
    fun `the total capital ratio is the capital read's own ratio`() {
        val result = capital(book)
        assertThat(measured(LimitInputs.totalCapitalRatio(result))).isEqualByComparingTo(result.ratios!!.total.ratio)
        assertThat(measured(LimitInputs.tier1(result))).isEqualByComparingTo("1100")
    }

    @Test
    fun `no own funds - no capital ratio and no Tier 1`() {
        val result = capital(book.filterNot { it.glAccountCode == "6000" })
        assertThat(gap(LimitInputs.totalCapitalRatio(result))).contains("capital ratios not computable")
        assertThat(LimitInputs.tier1(result)).isInstanceOf(MetricInput.Gap::class.java)
    }

    @Test
    fun `an unclassified credit-risk balance makes the capital ratio not evaluable`() {
        val result = capital(book + gl("1000", "ASSET", "50"))
        assertThat(gap(LimitInputs.totalCapitalRatio(result))).contains("not classified for credit risk")
    }

    @Test
    fun `the largest bank exposure sums one counterparty's deals and divides by Tier 1`() {
        val result = capital(book)
        val input = LimitInputs.largeExposureToBank(result, instruments, LimitInputs.tier1(result))
        assertThat(measured(input)).isEqualByComparingTo("0.454545") // (300 + 200) / 1100
        assertThat((input as MetricInput.Measured).basis).contains("BANK-A")
    }

    @Test
    fun `a bank exposure with no counterparty (a GL-level nostro) makes the large-exposure limit not evaluable`() {
        val result = capital(book + gl("1001", "ASSET", "40"))
        val input = LimitInputs.largeExposureToBank(result, instruments, LimitInputs.tier1(result))
        assertThat(gap(input)).contains("carry no counterparty").contains("GL 1001")
    }

    @Test
    fun `a deal whose instrument names no counterparty is a gap too`() {
        val anonymous = instruments.map { if (it.id == "D3") it.copy(counterpartyRef = " ") else it }
        val result = capital(book, anonymous)
        assertThat(LimitInputs.largeExposureToBank(result, anonymous, LimitInputs.tier1(result)))
            .isInstanceOf(MetricInput.Gap::class.java)
    }

    private fun irrbb(
        aggregation: String? = "CZK",
        worstLoss: String? = "15",
        unpriced: List<String> = emptyList(),
        notConfigured: List<String> = emptyList(),
    ) = IrrbbResult(
        gaps = emptyList(),
        scenarios = emptyList(),
        shockNotConfigured = notConfigured,
        unpriced = unpriced,
        aggregationCurrency = aggregation,
        worstScenario = worstLoss?.let { ShockScenario.PARALLEL_UP },
        worstLoss = worstLoss?.let(::BigDecimal),
        worstByCurrency = emptyMap(),
    )

    private val tier1 = MetricInput.Measured(BigDecimal("100"), "t1")

    @Test
    fun `the IRRBB outlier ratio is the worst EVE loss over Tier 1`() {
        assertThat(measured(LimitInputs.irrbbOutlier(irrbb(), null, tier1))).isEqualByComparingTo("0.15")
        assertThat(measured(LimitInputs.irrbbOutlier(irrbb(worstLoss = null), null, tier1))).isEqualByComparingTo("0")
    }

    @Test
    fun `IRRBB is not evaluable without a curve set, with an unpriced or unshocked currency, or a mixed book`() {
        assertThat(gap(LimitInputs.irrbbOutlier(null, "no curve set recorded as of 2026-09-30", tier1)))
            .contains("no curve set")
        assertThat(gap(LimitInputs.irrbbOutlier(irrbb(unpriced = listOf("EUR")), null, tier1))).contains("EUR")
        assertThat(gap(LimitInputs.irrbbOutlier(irrbb(notConfigured = listOf("USD")), null, tier1))).contains("USD")
        assertThat(
            gap(LimitInputs.irrbbOutlier(irrbb(aggregation = null), null, tier1)),
        ).contains("more than one currency")
        assertThat(gap(LimitInputs.irrbbOutlier(irrbb(aggregation = "EUR"), null, tier1))).contains("aggregated in EUR")
        assertThat(gap(LimitInputs.irrbbOutlier(irrbb(), null, MetricInput.Gap("none")))).contains("Tier 1 unavailable")
    }
}
