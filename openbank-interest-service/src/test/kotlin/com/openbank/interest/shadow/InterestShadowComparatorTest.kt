// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.shadow

import com.openbank.interest.application.usecase.InterestShadowComparator
import com.openbank.interest.application.usecase.InterestShadowResult
import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.ProductInterpreter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The pure mapping from a legacy interest row to an IR decision (ADR-0352 phase 3). Negative cases first. */
class InterestShadowComparatorTest {

    private val comparator = InterestShadowComparator(ProductInterpreter(FinanceProductArithmetic))

    @Test
    fun `a revision with a deliberately different rate DIVERGES from the legacy accrual`() {
        // Legacy accrued 250000.00 at 3.00 % ACT/365 = 20.547950. The IR says 3.10 %.
        val results = comparator.compare(
            ShadowFixtures.parse(ShadowFixtures.withRate("0.0310000000")),
            ShadowFixtures.accrual("250000.00", "20.547950"),
        )
        val compared = results.single() as InterestShadowResult.Compared
        assertThat(compared.diverged).isTrue()
        assertThat(compared.ruleId).isEqualTo("interest.base")
        assertThat(compared.irAmount).isEqualByComparingTo("21.232875")
    }

    @Test
    fun `one unit in the sixth decimal is a divergence - there is no tolerance`() {
        val compared = comparator.compare(ShadowFixtures.parse(), ShadowFixtures.accrual("250000.00", "20.547951"))
            .single() as InterestShadowResult.Compared
        assertThat(compared.diverged).isTrue()
    }

    @Test
    fun `the reference product agrees with the legacy accrual and capitalisation`() {
        val accrual = comparator.compare(ShadowFixtures.parse(), ShadowFixtures.accrual("250000.00", "20.547950"))
        assertThat(accrual).containsExactly(
            InterestShadowResult.Compared("interest.base", false, BigDecimal("20.547950"), BigDecimal("20.547950")),
        )
        // Gross only: 41.095900 rounds to 41.10; the 6 CZK withheld is outside IR v1 and not compared.
        val cap = comparator.compare(ShadowFixtures.parse(), ShadowFixtures.capitalization("41.095900", "41.10", "6"))
            .single() as InterestShadowResult.Compared
        assertThat(cap.ruleId).isEqualTo("scheduled.capitalise")
        assertThat(cap.diverged).isFalse()
        assertThat(cap.irAmount).isEqualByComparingTo("41.10")
    }

    @Test
    fun `a capitalisation whose gross differs is a divergence`() {
        val cap = comparator.compare(ShadowFixtures.parse(), ShadowFixtures.capitalization("41.095900", "41.09"))
            .single() as InterestShadowResult.Compared
        assertThat(cap.diverged).isTrue()
    }

    @Test
    fun `an operator override rate is not the revision's decision and is never compared`() {
        assertThat(
            comparator.compare(
                ShadowFixtures.parse(),
                ShadowFixtures.accrual("1.00", "9.999999", decidedByPin = false),
            ),
        )
            .containsExactly(InterestShadowResult.Unmapped("override-rate"))
    }

    @Test
    fun `constructs the legacy path never applies are unmapped, not agreement`() {
        val tiered = ShadowFixtures.SAVINGS_CZK.replace(
            "\"rate\":\"0.0300000000\",\"rateType\":\"FIXED\"",
            "\"rateType\":\"TIERED\",\"tiers\":[" +
                "{\"from\":\"0.0000\",\"rate\":\"0.0200000000\",\"to\":\"10000.0000\"}," +
                "{\"from\":\"10000.0000\",\"rate\":\"0.0300000000\"}]",
        )
        assertThat(comparator.compare(ShadowFixtures.parse(tiered), ShadowFixtures.accrual("250000.00", "20.547950")))
            .containsExactly(InterestShadowResult.Unmapped("tiered-rate"))
        assertThat(
            comparator.compare(ShadowFixtures.parse(), ShadowFixtures.accrual("1.00", "0.000082", currency = "EUR")),
        )
            .containsExactly(InterestShadowResult.Unmapped("currency"))
    }
}
