// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.limits

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** ADR-0344 D8: PV01 and VaR as declarative limits — either PV01 sign is risk; a gap is never OK. */
class ValuationLimitInputsTest {
    private val pv01Limit = LimitDefinition("pv01-max", LimitMetric.PV01, BigDecimal(500), BigDecimal(400), "test")
    private val varLimit = LimitDefinition("var-max", LimitMetric.VAR_99_1D, BigDecimal(1000), BigDecimal(800), "test")

    @Test
    fun `PV01 is measured by its absolute value, so a short-duration book breaches like a long one`() {
        assertThat(
            RiskLimits.evaluate(pv01Limit, LimitInputs.pv01(BigDecimal("-600"), null)).status,
        ).isEqualTo(LimitStatus.BREACH)
        assertThat(RiskLimits.evaluate(pv01Limit, LimitInputs.pv01(BigDecimal("450"), null)).status)
            .isEqualTo(LimitStatus.EARLY_WARNING)
        assertThat(
            RiskLimits.evaluate(pv01Limit, LimitInputs.pv01(BigDecimal("10"), null)).status,
        ).isEqualTo(LimitStatus.OK)
        assertThat(RiskLimits.evaluate(pv01Limit, LimitInputs.pv01(null, "2 deals cannot be valued")).status)
            .isEqualTo(LimitStatus.NOT_EVALUABLE)
    }

    @Test
    fun `VaR breaches above its limit, a gain-only VaR is OK, and too little history is NOT_EVALUABLE`() {
        assertThat(RiskLimits.evaluate(varLimit, LimitInputs.valueAtRisk(BigDecimal("1200"), null)).status)
            .isEqualTo(LimitStatus.BREACH)
        assertThat(
            RiskLimits.evaluate(varLimit, LimitInputs.valueAtRisk(BigDecimal("-5"), null)).status,
        ).isEqualTo(LimitStatus.OK)
        val gap = RiskLimits.evaluate(varLimit, LimitInputs.valueAtRisk(null, "4 scenarios, below the minimum"))
        assertThat(gap.status).isEqualTo(LimitStatus.NOT_EVALUABLE)
        assertThat(gap.explanation).contains("below the minimum")
        assertThat(LimitMetric.parse("var-99-1d")).isEqualTo(LimitMetric.VAR_99_1D)
    }
}
