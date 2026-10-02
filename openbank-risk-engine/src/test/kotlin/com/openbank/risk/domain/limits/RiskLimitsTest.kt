// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.domain.limits

import com.openbank.risk.infrastructure.toLimitSet
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class RiskLimitsTest {

    private fun d(metric: LimitMetric, limit: String, ew: String, id: String = "l-1") =
        LimitDefinition(id, metric, BigDecimal(limit), BigDecimal(ew), "citation")

    private fun status(def: LimitDefinition, value: String) = RiskLimits.status(def, BigDecimal(value))

    @Test
    fun `a floor - below the limit is a breach, inside the band a warning, above it OK`() {
        val lcr = d(LimitMetric.LCR, "1.00", "1.10")
        assertThat(status(lcr, "0.999999")).isEqualTo(LimitStatus.BREACH)
        assertThat(status(lcr, "1.00")).isEqualTo(LimitStatus.EARLY_WARNING) // at the minimum meets it
        assertThat(status(lcr, "1.10")).isEqualTo(LimitStatus.EARLY_WARNING)
        assertThat(status(lcr, "1.100001")).isEqualTo(LimitStatus.OK)
    }

    @Test
    fun `a ceiling - above the limit is a breach, inside the band a warning, below it OK`() {
        val outlier = d(LimitMetric.IRRBB_EVE_OUTLIER, "0.15", "0.12")
        assertThat(status(outlier, "0.150001")).isEqualTo(LimitStatus.BREACH)
        assertThat(status(outlier, "0.15")).isEqualTo(LimitStatus.EARLY_WARNING)
        assertThat(status(outlier, "0.12")).isEqualTo(LimitStatus.EARLY_WARNING)
        assertThat(status(outlier, "0.119999")).isEqualTo(LimitStatus.OK)
        assertThat(status(outlier, "0")).isEqualTo(LimitStatus.OK)
    }

    @Test
    fun `an early warning equal to the limit means no warning band`() {
        val floor = d(LimitMetric.NSFR, "1.00", "1.00")
        assertThat(status(floor, "1.00")).isEqualTo(LimitStatus.OK)
        assertThat(status(floor, "0.99")).isEqualTo(LimitStatus.BREACH)
        val ceiling = d(LimitMetric.LARGE_EXPOSURE_BANK, "0.25", "0.25")
        assertThat(status(ceiling, "0.25")).isEqualTo(LimitStatus.OK)
        assertThat(status(ceiling, "0.2501")).isEqualTo(LimitStatus.BREACH)
    }

    @Test
    fun `a gap is NOT_EVALUABLE with its reason and no value - never OK`() {
        val def = d(LimitMetric.TOTAL_CAPITAL_RATIO, "0.08", "0.105")
        val e = RiskLimits.evaluate(def, MetricInput.Gap("no own funds"))
        assertThat(e.status).isEqualTo(LimitStatus.NOT_EVALUABLE)
        assertThat(e.value).isNull()
        assertThat(e.explanation).isEqualTo("no own funds")
    }

    @Test
    fun `a limit whose metric was not computed at all is NOT_EVALUABLE, not silently skipped`() {
        val set =
            LimitSet(
                "s",
                "1",
                "src",
                listOf(d(LimitMetric.LCR, "1", "1.1", "a"), d(LimitMetric.NSFR, "1", "1.05", "b")),
            )
        val out = RiskLimits.evaluate(set, mapOf(LimitMetric.LCR to MetricInput.Measured(BigDecimal("2"), "x")))
        assertThat(out.map { it.definition.id to it.status }).containsExactly(
            "a" to LimitStatus.OK,
            "b" to LimitStatus.NOT_EVALUABLE,
        )
    }

    @Test
    fun `an early warning on the wrong side of its limit is refused`() {
        assertThatThrownBy { d(LimitMetric.LCR, "1.00", "0.90") }.hasMessageContaining("floor")
        assertThatThrownBy { d(LimitMetric.IRRBB_EVE_OUTLIER, "0.15", "0.20") }.hasMessageContaining("ceiling")
        assertThatThrownBy { d(LimitMetric.LCR, "0", "0") }.hasMessageContaining("positive")
    }

    @Test
    fun `duplicate limit ids and unknown metrics are refused`() {
        assertThatThrownBy {
            LimitSet("s", "1", "src", listOf(d(LimitMetric.LCR, "1", "1", "x"), d(LimitMetric.NSFR, "1", "1", "x")))
        }.hasMessageContaining("duplicate")
        assertThatThrownBy { LimitMetric.parse("open-fx-position") }.hasMessageContaining("unknown limit metric")
    }

    @Test
    fun `the shipped limit set parses, names id and version, and declares the five limits`() {
        val set = LimitTestSet.shipped()
        assertThat(set.id).isEqualTo("openbank-risk-appetite")
        assertThat(set.version).isEqualTo("1")
        assertThat(set.limits.associate { it.id to it.metric }).isEqualTo(
            mapOf(
                "irrbb-eve-outlier" to LimitMetric.IRRBB_EVE_OUTLIER,
                "large-exposure-bank-max" to LimitMetric.LARGE_EXPOSURE_BANK,
                "lcr-min" to LimitMetric.LCR,
                "nsfr-min" to LimitMetric.NSFR,
                "total-capital-ratio-min" to LimitMetric.TOTAL_CAPITAL_RATIO,
            ),
        )
        val byId = set.limits.associateBy { it.id }
        assertThat(byId.getValue("lcr-min").limit).isEqualByComparingTo("1.00")
        assertThat(byId.getValue("irrbb-eve-outlier").limit).isEqualByComparingTo("0.15")
        assertThat(byId.getValue("total-capital-ratio-min").limit).isEqualByComparingTo("0.08")
        // Every DECLARED set is valid, not only the selected one (what the startup observer checks).
        val config = LimitTestSet.config()
        config.parameterSets().keys.forEach { assertThat(config.toLimitSet(it).limits).isNotEmpty() }
    }
}
