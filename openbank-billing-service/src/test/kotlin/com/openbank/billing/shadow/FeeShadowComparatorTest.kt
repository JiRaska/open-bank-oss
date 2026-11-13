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

class FeeShadowComparatorTest {

    private val comparator = FeeShadowComparator(ProductInterpreter(FinanceProductArithmetic))
    private val revision = ProductIrParser.parse(ShadowFixtures.IR)

    private fun single(balance: String?, legacy: ShadowLegacyFee): FeeShadowResult =
        comparator.compare(revision, ShadowFixtures.input(balance, listOf(legacy))).single()

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
    fun `a legacy zero charge not flagged waived agrees with an IR waiver, and disagrees with an IR charge`() {
        val zero = ShadowFixtures.charged("0.00")
        val waivedByIr = single("5000.00", zero) as FeeShadowResult.Compared
        assertThat(waivedByIr.diverged).isFalse()
        assertThat(waivedByIr.legacyCharged).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat((single("500.00", zero) as FeeShadowResult.Compared).diverged).isTrue()
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
        val result = comparator.compare(ir, ShadowFixtures.input(null, listOf(ShadowFixtures.charged()))).single()
        assertThat(result).isNotInstanceOf(FeeShadowResult.Compared::class.java)
        val knownZero = comparator.compare(
            ir,
            ShadowFixtures.input("0.00", listOf(ShadowFixtures.waived())),
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
            (comparator.compare(segmentWaiver, input).single() as FeeShadowResult.Compared).diverged,
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
        assertThat(comparator.compare(relevant, input).single())
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
        assertThat((comparator.compare(unrelated, input).single() as FeeShadowResult.Compared).diverged).isFalse()
    }

    @Test
    fun `a recorded zone outside the admissible set is not comparable, never re-zoned`() {
        val base = ShadowFixtures.input("500.00", listOf(ShadowFixtures.charged()))
        assertThat(comparator.compare(revision, base).single()).isInstanceOf(FeeShadowResult.Compared::class.java)
        listOf("America/New_York", "not-a-zone", null).forEach { zone ->
            assertThat(comparator.compare(revision, base.copy(evaluationZone = zone)).single())
                .isInstanceOf(FeeShadowResult.UnknownInput::class.java)
        }
    }

    @Test
    fun `a fee on only one side is unmapped, never agreed`() {
        val results = comparator.compare(
            revision,
            ShadowFixtures.input("500.00", listOf(ShadowLegacyFee("CARD", BigDecimal("1.00"), waived = false))),
        )
        assertThat(
            results,
        ).containsExactlyInAnyOrder(FeeShadowResult.Unmapped("CARD"), FeeShadowResult.Unmapped("MAINT"))
    }

    @Test
    fun `an old queued row without captured time is not compared`() {
        val input = ShadowFixtures.input("500", listOf(ShadowFixtures.charged())).copy(evaluatedAt = null)
        assertThat(comparator.compare(revision, input).single()).isInstanceOf(FeeShadowResult.UnknownInput::class.java)
    }

    @Test
    fun `a production-shaped input (cadence only, no tag) compares when exactly one tag has that cadence`() {
        val input = ShadowFixtures.input("500.00", listOf(ShadowFixtures.charged())).copy(scheduledTrigger = null)
        val r = comparator.compare(revision, input).single() as FeeShadowResult.Compared
        assertThat(r.diverged).isFalse()
        assertThat(r.ruleId).isEqualTo("fee.maintenance")
    }

    @Test
    fun `two schedule tags sharing the fired cadence are ambiguous, not guessed`() {
        val ambiguous = ProductIrParser.parse(
            ShadowFixtures.IR.replace(
                "\"cadence\":\"MONTHLY\"}}],",
                "\"cadence\":\"MONTHLY\"}},{\"ruleId\":\"open.schedule-other\",\"action\":{\"type\":\"SCHEDULE\",\"hook\":\"scheduled\",\"tag\":\"OTHER\",\"cadence\":\"MONTHLY\"}}],",
            ).replace(
                "\"feeRuleId\":\"fee.maintenance\"}}]}}",
                "\"feeRuleId\":\"fee.maintenance\"}},{\"ruleId\":\"scheduled.other\",\"trigger\":\"OTHER\",\"action\":{\"type\":\"CHARGE_FEE\",\"feeRuleId\":\"fee.maintenance\"}}]}}",
            ),
        )
        val input = ShadowFixtures.input("500.00", listOf(ShadowFixtures.charged())).copy(scheduledTrigger = null)
        assertThat(comparator.compare(ambiguous, input).single()).isInstanceOf(FeeShadowResult.UnknownInput::class.java)
    }

    @Test
    fun `manual invocation without a fired cadence does not guess from cycle id`() {
        val input = ShadowFixtures.input(
            "500",
            listOf(ShadowFixtures.charged()),
        ).copy(scheduledCadence = null, scheduledTrigger = null)
        assertThat(comparator.compare(revision, input).single()).isInstanceOf(FeeShadowResult.UnknownInput::class.java)
    }

    @Test
    fun `missing event history is not evidence of no withdrawals`() {
        val ir = ProductIrParser.parse(
            ShadowFixtures.IR.replace(
                "\"waiver\":{\"type\":\"comparison\",\"attribute\":\"BALANCE\",\"operator\":\"GTE\",\"threshold\":\"1000.00\",\"currency\":\"EUR\"}",
                "\"waiver\":{\"type\":\"noEvents\",\"kind\":\"WITHDRAWAL\",\"window\":{\"days\":30}}",
            ),
        )
        val input = ShadowFixtures.input("500", listOf(ShadowFixtures.waived()))
        assertThat(comparator.compare(ir, input).single()).isInstanceOf(FeeShadowResult.UnknownInput::class.java)
    }

    @Test
    fun `all rules for the actual fired trigger count instead of last rule wins`() {
        val ir = ProductIrParser.parse(
            ShadowFixtures.IR.replace(
                "\"scheduled\":[",
                "\"scheduled\":[{\"ruleId\":\"scheduled.second\",\"trigger\":\"FEE_MAINT\",\"action\":{\"type\":\"CHARGE_FEE\",\"feeRuleId\":\"fee.maintenance\"}},",
            ),
        )
        val input = ShadowFixtures.input("500", listOf(ShadowFixtures.charged()))
        val result = comparator.compare(ir, input).single() as FeeShadowResult.Compared
        assertThat(result.irCharged).isEqualByComparingTo("9.80")
        assertThat(result.diverged).isTrue()
    }

    @Test
    fun `missing facts do not hide fees present only in IR`() {
        val input = ShadowFixtures.input("500", emptyList()).copy(evaluatedAt = null)
        assertThat(comparator.compare(revision, input).single()).isEqualTo(FeeShadowResult.Unmapped("MAINT"))
    }

    @Test
    fun `opening insensitive fees remain comparable without an opening fact`() {
        val input = ShadowFixtures.input("500", listOf(ShadowFixtures.charged())).copy(openedAt = null)
        assertThat(comparator.compare(revision, input).single()).isInstanceOf(FeeShadowResult.Compared::class.java)
    }

    @Test
    fun `delayed replay uses captured assessment date and zone in actual arithmetic`() {
        val dates = mutableListOf<java.time.LocalDate>()
        val arithmetic = object : com.openbank.libs.product.ir.ProductArithmetic by FinanceProductArithmetic {
            override fun dailyAccrual(
                balance: BigDecimal,
                annualRate: BigDecimal,
                dayCount: com.openbank.libs.product.ir.IrDayCount,
                date: java.time.LocalDate,
                accrualRounding: com.openbank.libs.domain.money.RoundingPolicy,
            ): BigDecimal {
                dates.add(date)
                return FinanceProductArithmetic.dailyAccrual(balance, annualRate, dayCount, date, accrualRounding)
            }
        }
        val original = com.openbank.products.catalog.ReferenceProducts.savingsFixed.compile()
        val ir = original.copy(
            interest = original.interest!!.copy(bonus = null),
            hooks = original.hooks.mapValues { (_, rules) ->
                rules.map { rule ->
                    if (rule.action == com.openbank.libs.product.ir.Action.AccrueInterest) {
                        rule.copy(trigger = "FEE_MAINT")
                    } else {
                        rule
                    }
                }
            },
        )
        val input = ShadowFixtures.input("500", listOf(ShadowFixtures.charged("2.00"))).copy(
            evaluatedAt = java.time.Instant.parse("2026-05-01T23:30:00Z"),
            evaluationZone = "Europe/Prague",
        )
        val delayed = FeeShadowComparator(ProductInterpreter(arithmetic))
        delayed.compare(ir, input)
        delayed.compare(ir, input)
        assertThat(
            dates,
        ).containsExactly(java.time.LocalDate.parse("2026-05-02"), java.time.LocalDate.parse("2026-05-02"))
    }

    @Test
    fun `unknown turnover under negation is not compared`() {
        nullableNegation("MONTHLY_TURNOVER")
    }

    @Test
    fun `unknown pocket balance under negation is not compared`() {
        nullableNegation("AGGREGATE_POCKET_BALANCE")
    }

    @Test
    fun `unknown segment under negation is not compared`() {
        nullableNegation("SEGMENT")
    }

    private fun nullableNegation(attribute: String) {
        val predicate = if (attribute == "SEGMENT") {
            "{\"type\":\"comparison\",\"attribute\":\"SEGMENT\",\"operator\":\"EQ\",\"textValue\":\"RETAIL\"}"
        } else {
            "{\"type\":\"comparison\",\"attribute\":\"$attribute\",\"operator\":\"GTE\",\"threshold\":\"1000.00\",\"currency\":\"EUR\"}"
        }
        val ir = ProductIrParser.parse(
            ShadowFixtures.IR.replace(
                "{\"type\":\"comparison\",\"attribute\":\"BALANCE\",\"operator\":\"GTE\",\"threshold\":\"1000.00\",\"currency\":\"EUR\"}",
                "{\"type\":\"not\",\"operand\":$predicate}",
            ),
        )
        val result = comparator.compare(ir, ShadowFixtures.input("500", listOf(ShadowFixtures.waived()))).single()
        assertThat(result).describedAs(attribute).isInstanceOf(FeeShadowResult.UnknownInput::class.java)
    }

    @Test
    fun `decisive known logical branches do not require an irrelevant unknown scalar`() {
        val unknown = com.openbank.libs.product.ir.Predicate.Comparison(
            com.openbank.libs.product.WaiveAttribute.SEGMENT,
            com.openbank.libs.product.WaiveOperator.EQ,
            textValue = "RETAIL",
        )
        val knownFalse = com.openbank.libs.product.ir.Predicate.Comparison(
            com.openbank.libs.product.WaiveAttribute.BALANCE,
            com.openbank.libs.product.WaiveOperator.GTE,
            threshold = BigDecimal("1000"),
        )
        val knownTrue = com.openbank.libs.product.ir.Predicate.Not(knownFalse)
        val negatedUnknown = com.openbank.libs.product.ir.Predicate.Not(unknown)
        val cases = listOf(
            com.openbank.libs.product.ir.Predicate.Or(listOf(negatedUnknown, knownTrue)) to ShadowFixtures.waived(),
            com.openbank.libs.product.ir.Predicate.And(listOf(negatedUnknown, knownFalse)) to ShadowFixtures.charged(),
        )
        for ((predicate, legacy) in cases) {
            val ir = revision.copy(fees = listOf(revision.fees.single().copy(waiver = predicate)))
            val result = comparator.compare(ir, ShadowFixtures.input("500", listOf(legacy))).single()
            assertThat(result).isInstanceOf(FeeShadowResult.Compared::class.java)
            assertThat((result as FeeShadowResult.Compared).diverged).isFalse()
        }
    }

    @Test
    fun `a false scheduled guard does not read the fee action unknown waiver facts`() {
        val falseGuard = com.openbank.libs.product.ir.Predicate.Comparison(
            com.openbank.libs.product.WaiveAttribute.BALANCE,
            com.openbank.libs.product.WaiveOperator.GTE,
            threshold = BigDecimal("1000"),
        )
        val missing = com.openbank.libs.product.ir.Predicate.Not(
            com.openbank.libs.product.ir.Predicate.Comparison(
                com.openbank.libs.product.WaiveAttribute.SEGMENT,
                com.openbank.libs.product.WaiveOperator.EQ,
                textValue = "RETAIL",
            ),
        )
        val ir = revision.copy(
            fees = listOf(revision.fees.single().copy(waiver = missing)),
            hooks = revision.hooks.mapValues { (hook, rules) ->
                if (hook == com.openbank.libs.product.ir.Hook.SCHEDULED) {
                    rules.map { it.copy(whenPredicate = falseGuard) }
                } else {
                    rules
                }
            },
        )
        val result = comparator.compare(ir, ShadowFixtures.input("500", listOf(ShadowFixtures.waived()))).single()
        assertThat(result).isInstanceOf(FeeShadowResult.Compared::class.java)
        assertThat((result as FeeShadowResult.Compared).diverged).isFalse()
    }

    @Test
    fun `different valid scheduled triggers select the captured event independently of rule order`() {
        val extraSchedule = """{"ruleId":"open.other","action":{"type":"SCHEDULE",
            "hook":"scheduled","tag":"OTHER","cadence":"DAILY"}}"""
        val extraRule = """{"ruleId":"scheduled.other","trigger":"OTHER",
            "when":{"type":"comparison","attribute":"BALANCE","operator":"LT","threshold":"0"},
            "action":{"type":"CHARGE_FEE","feeRuleId":"fee.maintenance"}}"""
        val text = ShadowFixtures.IR.replace("\"onOpen\":[", "\"onOpen\":[$extraSchedule,")
            .replace("\"scheduled\":[", "\"scheduled\":[$extraRule,")
        val first = ProductIrParser.parse(text)
        val reversed = first.copy(hooks = first.hooks.mapValues { (_, rules) -> rules.reversed() })
        for (ir in listOf(first, reversed)) {
            val charged = ShadowFixtures.input("500", listOf(ShadowFixtures.charged()))
            val selected = comparator.compare(ir, charged).single() as FeeShadowResult.Compared
            assertThat(selected.diverged).isFalse()
            val waived = ShadowFixtures.input("500", listOf(ShadowFixtures.waived())).copy(scheduledTrigger = "OTHER")
            val other = comparator.compare(ir, waived).single() as FeeShadowResult.Compared
            assertThat(other.diverged).isFalse()
        }
    }
}
