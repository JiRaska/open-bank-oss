// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.lending

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * ADR-0269 / APP-ADR-0001 rule 5.
 *
 * Most of these tests are about what the view refuses to say: no aggregate score, no verdict
 * without data, no flattering default. The pleasant readings are the easy half.
 */
class FinancialHealthTest {

    private fun inputs(
        income: String? = "50000",
        outflow: String? = "40000",
        net: String? = "10000",
        volatility: String? = "0.10",
        balance: String? = "150000",
        debt: String? = "5000",
        arrears: Boolean? = false,
        months: Int? = 12,
    ) = FinancialHealthInputs(
        monthlyIncome = income?.let { BigDecimal(it) },
        monthlyOutflow = outflow?.let { BigDecimal(it) },
        monthlyNet = net?.let { BigDecimal(it) },
        volatilityRatio = volatility?.let { BigDecimal(it) },
        liquidBalance = balance?.let { BigDecimal(it) },
        monthlyDebtService = debt?.let { BigDecimal(it) },
        hasArrears = arrears,
        monthsObserved = months,
    )

    private fun zoneOf(i: FinancialHealthInputs, code: String) = FinancialHealth.assess(i).pillar(code)!!.zone

    // ── It is not a score ─────────────────────────────────────────────────────

    @Test
    fun `the view has no aggregate — there is no single number to optimise or to act on`() {
        val fields = FinancialHealthView::class.java.declaredFields.map { it.name.lowercase() }
        assertThat(fields).noneMatch { it.contains("score") || it.contains("rating") || it.contains("grade") }
    }

    @Test
    fun `paying your bills is HEALTHY and nothing better — doing the expected thing is not a grade`() {
        assertThat(zoneOf(inputs(arrears = false), FinancialHealth.PILLAR_HABITS)).isEqualTo(PillarZone.HEALTHY)
        assertThat(PillarZone.entries.filter { it != PillarZone.UNKNOWN }).hasSize(3)
    }

    // ── Unknown is never a verdict ────────────────────────────────────────────

    @Test
    fun `a missing input greys out ONE pillar, not the screen`() {
        val view = FinancialHealth.assess(inputs(debt = null))
        assertThat(view.pillar(FinancialHealth.PILLAR_OBLIGATIONS)!!.zone).isEqualTo(PillarZone.UNKNOWN)
        // The customer opened this to look at their reserve; a slow loan book must not blank that.
        assertThat(view.pillar(FinancialHealth.PILLAR_RESERVE)!!.zone).isNotEqualTo(PillarZone.UNKNOWN)
    }

    @Test
    fun `an unknown pillar carries no numbers to be mistaken for a measurement`() {
        val pillar = FinancialHealth.assess(inputs(arrears = null)).pillar(FinancialHealth.PILLAR_HABITS)!!
        assertThat(pillar.zone).isEqualTo(PillarZone.UNKNOWN)
        assertThat(pillar.value).isNull()
        assertThat(pillar.target).isNull()
    }

    @Test
    fun `too few observed months makes cashflow unknown rather than confident`() {
        assertThat(zoneOf(inputs(months = 2), FinancialHealth.PILLAR_CASHFLOW)).isEqualTo(PillarZone.UNKNOWN)
    }

    @Test
    fun `an unknown volatility is stretched, not healthy — steadiness is not assumed`() {
        assertThat(zoneOf(inputs(volatility = null), FinancialHealth.PILLAR_CASHFLOW))
            .isEqualTo(PillarZone.STRETCHED)
    }

    // ── Reserve ───────────────────────────────────────────────────────────────

    @Test
    fun `three months of outgoings is the reserve target`() {
        assertThat(zoneOf(inputs(balance = "120000", outflow = "40000"), FinancialHealth.PILLAR_RESERVE))
            .isEqualTo(PillarZone.HEALTHY)
        assertThat(zoneOf(inputs(balance = "119999", outflow = "40000"), FinancialHealth.PILLAR_RESERVE))
            .isEqualTo(PillarZone.STRETCHED)
        assertThat(zoneOf(inputs(balance = "20000", outflow = "40000"), FinancialHealth.PILLAR_RESERVE))
            .isEqualTo(PillarZone.AT_RISK)
    }

    @Test
    fun `the reserve shows its working in months, rounded DOWN`() {
        val pillar = FinancialHealth.assess(inputs(balance = "119999", outflow = "40000"))
            .pillar(FinancialHealth.PILLAR_RESERVE)!!
        // Down, not nearest: telling someone they have 3 months of cover when they have 2.99 is the
        // rounding that matters here.
        assertThat(pillar.value).isEqualByComparingTo("2.9")
    }

    // ── Cashflow ──────────────────────────────────────────────────────────────

    @Test
    fun `a stable shortfall is still a shortfall`() {
        // Negative net with the calmest possible volatility. Reading that as "steady" would be the
        // most misleading answer available.
        assertThat(zoneOf(inputs(net = "-2000", volatility = "0.01"), FinancialHealth.PILLAR_CASHFLOW))
            .isEqualTo(PillarZone.AT_RISK)
    }

    @Test
    fun `a swinging income is at risk even when it averages out`() {
        assertThat(zoneOf(inputs(net = "10000", volatility = "0.80"), FinancialHealth.PILLAR_CASHFLOW))
            .isEqualTo(PillarZone.AT_RISK)
    }

    // ── Obligations ───────────────────────────────────────────────────────────

    @Test
    fun `debt service is judged as a share of income, not as an amount`() {
        assertThat(zoneOf(inputs(income = "50000", debt = "5000"), FinancialHealth.PILLAR_OBLIGATIONS))
            .isEqualTo(PillarZone.HEALTHY)
        // The same 25,000 is comfortable on 100,000 and at risk on 50,000.
        assertThat(zoneOf(inputs(income = "50000", debt = "25000"), FinancialHealth.PILLAR_OBLIGATIONS))
            .isEqualTo(PillarZone.AT_RISK)
        assertThat(zoneOf(inputs(income = "100000", debt = "25000"), FinancialHealth.PILLAR_OBLIGATIONS))
            .isEqualTo(PillarZone.HEALTHY)
    }

    @Test
    fun `a zero income makes obligations unknown rather than infinitely bad`() {
        assertThat(zoneOf(inputs(income = "0"), FinancialHealth.PILLAR_OBLIGATIONS)).isEqualTo(PillarZone.UNKNOWN)
    }

    // ── Shape ─────────────────────────────────────────────────────────────────

    @Test
    fun `every assessment returns all four pillars, in a stable order`() {
        val codes = FinancialHealth.assess(inputs()).pillars.map { it.code }
        assertThat(codes).containsExactly(
            FinancialHealth.PILLAR_RESERVE,
            FinancialHealth.PILLAR_CASHFLOW,
            FinancialHealth.PILLAR_OBLIGATIONS,
            FinancialHealth.PILLAR_HABITS,
        )
    }

    @Test
    fun `an assessment with nothing known is four unknowns, not an empty screen`() {
        val view = FinancialHealth.assess(
            FinancialHealthInputs(null, null, null, null, null, null, null, null),
        )
        assertThat(view.pillars).hasSize(4)
        assertThat(view.pillars.map { it.zone }).containsOnly(PillarZone.UNKNOWN)
    }
}
