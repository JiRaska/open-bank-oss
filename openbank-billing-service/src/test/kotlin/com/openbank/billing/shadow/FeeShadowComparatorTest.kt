// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.shadow

import com.openbank.billing.application.usecase.FeeShadowComparator
import com.openbank.billing.application.usecase.FeeShadowResult
import com.openbank.billing.domain.ShadowLegacyFee
import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductIrParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class FeeShadowComparatorTest {

    private val comparator = FeeShadowComparator(ProductInterpreter(FinanceProductArithmetic))
    private val revision = ProductIrParser.parse(ShadowFixtures.IR)
    private val at = Instant.parse("2026-10-31T23:00:00Z")

    private fun single(balance: String?, legacy: ShadowLegacyFee): FeeShadowResult =
        comparator.compare(revision, ShadowFixtures.input(balance, listOf(legacy)), at).single()

    @Test
    fun `legacy charge below the waiver threshold agrees with the IR charge`() {
        val r = single("500.00", ShadowFixtures.charged()) as FeeShadowResult.Compared
        assertThat(r.diverged).isFalse()
        assertThat(r.ruleId).isEqualTo("fee.maintenance")
        assertThat(r.irCharged).isEqualByComparingTo("4.90")
    }

    @Test
    fun `legacy waiver above the threshold agrees with the IR waiver`() {
        val r = single("5000.00", ShadowFixtures.waived()) as FeeShadowResult.Compared
        assertThat(r.diverged).isFalse()
        assertThat(r.irCharged).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `charge-versus-waiver and a different amount are both divergence, and scale alone is not`() {
        assertThat((single("5000.00", ShadowFixtures.charged()) as FeeShadowResult.Compared).diverged).isTrue()
        assertThat((single("500.00", ShadowFixtures.waived()) as FeeShadowResult.Compared).diverged).isTrue()
        assertThat((single("500.00", ShadowFixtures.charged("5.00")) as FeeShadowResult.Compared).diverged).isTrue()
        assertThat((single("500.00", ShadowFixtures.charged("4.9000")) as FeeShadowResult.Compared).diverged).isFalse()
    }

    @Test
    fun `an unknown balance is charged by both sides, as the legacy evaluator fails closed`() {
        val r = single(null, ShadowFixtures.charged()) as FeeShadowResult.Compared
        assertThat(r.diverged).isFalse()
    }

    @Test
    fun `a fee on only one side is unmapped, never agreed`() {
        val results = comparator.compare(
            revision,
            ShadowFixtures.input("500.00", listOf(ShadowLegacyFee("CARD", BigDecimal("1.00"), waived = false))),
            at,
        )
        assertThat(
            results,
        ).containsExactlyInAnyOrder(FeeShadowResult.Unmapped("CARD"), FeeShadowResult.Unmapped("MAINT"))
    }
}
