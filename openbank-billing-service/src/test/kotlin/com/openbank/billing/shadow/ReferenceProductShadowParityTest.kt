// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.billing.shadow

import com.openbank.billing.application.usecase.FeeShadowComparator
import com.openbank.billing.application.usecase.FeeShadowResult
import com.openbank.billing.domain.ShadowLegacyFee
import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.FeeContext
import com.openbank.libs.product.WaiveReason
import com.openbank.libs.product.WaiverEvaluator
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.testing.product.GoldenScenarioRunner
import com.openbank.products.catalog.ReferenceProducts
import com.openbank.products.dsl.ProductDefinition
import com.openbank.products.golden.ProductGoldens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal

/**
 * The shadow's parity contract (ADR-0352 phase 3): for the public reference products, the LEGACY
 * billing decision (the shared WaiverEvaluator over the free-text condition billing reads today) and
 * the PRODUCT IR decision agree on every balance across the waiver boundary, so a non-zero divergence
 * counter in production means a real difference in a real product, not a comparator artefact.
 */
class ReferenceProductShadowParityTest {

    private val comparator = FeeShadowComparator(ProductInterpreter(FinanceProductArithmetic))

    /** The legacy representation of each reference product's maintenance fee: amount and waiver text. */
    private data class LegacyFee(val product: ProductDefinition, val amount: String, val condition: String)

    private val legacy = listOf(
        LegacyFee(ReferenceProducts.currentAccount, "4.90", "Balance >= 1000 EUR"),
        LegacyFee(ReferenceProducts.savingsFixed, "2.00", "Balance >= 5000 EUR"),
    )

    private val balances =
        listOf(null, "0.00", "-200.00", "999.99", "1000.00", "1000.01", "4999.99", "5000.00", "250000.00")

    @TestFactory
    fun `legacy waiver and product IR agree on every balance across the waiver boundary`() = legacy.map { fee ->
        DynamicTest.dynamicTest(fee.product.name) {
            val revision = fee.product.compile()
            var compared = 0
            for (balance in balances) {
                val reason = WaiverEvaluator.evaluate(
                    fee.condition,
                    FeeContext(balance = balance?.let(::BigDecimal), currency = "EUR"),
                )
                val waived = reason == WaiveReason.WAIVED_BY_CONDITION
                val decision = ShadowLegacyFee("MAINT", if (waived) BigDecimal.ZERO else BigDecimal(fee.amount), waived)
                val results = comparator.compare(revision, ShadowFixtures.input(balance, listOf(decision)))
                if (balance == null) {
                    assertThat(results.single()).isInstanceOf(FeeShadowResult.UnknownInput::class.java)
                    continue
                }
                val result = results.single() as FeeShadowResult.Compared
                assertThat(result.diverged)
                    .describedAs(
                        "${fee.product.name} at balance $balance: legacy=${decision.chargedAmount} ir=${result.irCharged}",
                    )
                    .isFalse()
                compared++
            }
            assertThat(compared).isEqualTo(balances.count { it != null })
        }
    }

    @Test
    fun `the golden vectors the comparator is held to still pass, so parity is measured against a correct IR`() {
        val runner = GoldenScenarioRunner()
        val names = ProductGoldens.names()
        assertThat(names).isNotEmpty()
        names.forEach { runner.assertPasses(ProductGoldens.load(it)) }
    }
}
