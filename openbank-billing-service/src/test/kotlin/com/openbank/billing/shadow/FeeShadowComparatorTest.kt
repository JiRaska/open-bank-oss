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
    fun `an unknown balance is not treated as a known zero`() {
        assertThat(single(null, ShadowFixtures.charged())).isInstanceOf(FeeShadowResult.UnknownInput::class.java)
    }

    @Test
    fun `unknown balance is not a measured LT waiver decision`() {
        unknownBalanceIsNotCompared("LT", "1000.00")
    }

    @Test
    fun `unknown balance is not a measured zero equality waiver decision`() {
        unknownBalanceIsNotCompared("EQ", "0.00")
    }

    private fun unknownBalanceIsNotCompared(operator: String, threshold: String) {
        val ir = ProductIrParser.parse(
            ShadowFixtures.IR.replace("\"GTE\"", "\"$operator\"")
                .replace("\"1000.00\"", "\"$threshold\""),
        )
        val legacy = com.openbank.libs.product.WaiverEvaluator.evaluate(
            "Balance ${if (operator == "LT") "<" else "="} $threshold EUR",
            com.openbank.libs.product.FeeContext(balance = null, currency = "EUR"),
        )
        assertThat(legacy).isEqualTo(com.openbank.libs.product.WaiveReason.CONTEXT_UNKNOWN)
        val result = comparator.compare(ir, ShadowFixtures.input(null, listOf(ShadowFixtures.charged())), at).single()
        assertThat(result).isNotInstanceOf(FeeShadowResult.Compared::class.java)
        val knownZero = comparator.compare(
            ir,
            ShadowFixtures.input("0.00", listOf(ShadowFixtures.waived())),
            at,
        ).single()
        assertThat(knownZero).isInstanceOf(FeeShadowResult.Compared::class.java)
        assertThat((knownZero as FeeShadowResult.Compared).diverged).isFalse()
    }

    @Test
    fun `missing balance still compares a fee without a waiver or with a known segment waiver`() {
        val noWaiver = revision.copy(fees = revision.fees.map { it.copy(waiver = null) })
        val withoutWaiver = comparator.compare(
            noWaiver,
            ShadowFixtures.input(null, listOf(ShadowFixtures.charged())),
            at,
        )
        assertThat((withoutWaiver.single() as FeeShadowResult.Compared).diverged).isFalse()
        val segmentWaiver = revision.copy(
            fees = revision.fees.map {
                it.copy(
                    waiver = com.openbank.libs.product.ir.Predicate.Comparison(
                        com.openbank.libs.product.WaiveAttribute.SEGMENT,
                        com.openbank.libs.product.WaiveOperator.EQ,
                        textValue = "RETAIL",
                    ),
                )
            },
        )
        val input = ShadowFixtures.input(null, listOf(ShadowFixtures.waived())).copy(segment = "RETAIL")
        assertThat(
            (comparator.compare(segmentWaiver, input, at).single() as FeeShadowResult.Compared).diverged,
        ).isFalse()
    }

    @Test
    fun `nested balance guards affect only their scheduled trigger`() {
        val rule = revision.hooks.getValue(com.openbank.libs.product.ir.Hook.SCHEDULED).single()
        val nested = com.openbank.libs.product.ir.Predicate.Not(
            com.openbank.libs.product.ir.Predicate.Or(
                listOf(
                    com.openbank.libs.product.ir.Predicate.And(listOf(revision.fees.single().waiver!!)),
                ),
            ),
        )
        val noWaiver = revision.copy(fees = revision.fees.map { it.copy(waiver = null) })
        val relevant = noWaiver.copy(
            hooks = noWaiver.hooks +
                (com.openbank.libs.product.ir.Hook.SCHEDULED to listOf(rule.copy(whenPredicate = nested))),
        )
        val input = ShadowFixtures.input(null, listOf(ShadowFixtures.charged()))
        assertThat(comparator.compare(relevant, input, at).single())
            .isInstanceOf(FeeShadowResult.UnknownInput::class.java)
        val unrelated = noWaiver.copy(
            hooks = noWaiver.hooks +
                (
                    com.openbank.libs.product.ir.Hook.SCHEDULED to listOf(
                        rule,
                        rule.copy(
                            ruleId = "unrelated",
                            trigger = "OTHER",
                            whenPredicate = nested,
                            action = com.openbank.libs.product.ir.Action.Notify("test", emptyMap()),
                        ),
                    )
                    ),
        )
        assertThat((comparator.compare(unrelated, input, at).single() as FeeShadowResult.Compared).diverged).isFalse()
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
