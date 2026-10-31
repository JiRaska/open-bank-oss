// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.ir

import com.openbank.libs.product.WaiveAttribute
import com.openbank.libs.product.WaiveOperator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ProductMonotonicityTest {

    private val balanceLt = Predicate.Comparison(WaiveAttribute.BALANCE, WaiveOperator.LT, BigDecimal("40000"))
    private val balanceGte = Predicate.Comparison(WaiveAttribute.BALANCE, WaiveOperator.GTE, BigDecimal("1000"))

    private fun withBonus(p: Predicate): ProductRevisionIr {
        val rev = IrFixtures.savings()
        return rev.copy(interest = rev.interest!!.copy(bonus = BonusRate("interest.bonus", BigDecimal("0.01"), p)))
    }

    @Test
    fun `the committed fixtures are monotone`() {
        assertThat(ProductMonotonicity.findings(IrFixtures.savings())).isEmpty()
        assertThat(ProductMonotonicity.isMonotone(IrFixtures.termDeposit())).isTrue()
    }

    @Test
    fun `a bonus below a balance is flagged, a bonus above one is not`() {
        assertThat(ProductMonotonicity.findings(withBonus(balanceLt)))
            .singleElement().extracting { it.ruleId to it.message }
            .isEqualTo("interest.bonus" to "bonus is gated by BALANCE < 40000")
        assertThat(ProductMonotonicity.findings(withBonus(balanceGte))).isEmpty()
    }

    @Test
    fun `negation flips the direction, and equality is never monotone`() {
        assertThat(ProductMonotonicity.findings(withBonus(Predicate.Not(balanceGte))))
            .singleElement().extracting { it.message }.isEqualTo("bonus is gated by not BALANCE >= 1000")
        assertThat(ProductMonotonicity.findings(withBonus(Predicate.Not(balanceLt)))).isEmpty()
        val eq = Predicate.Comparison(WaiveAttribute.AGGREGATE_POCKET_BALANCE, WaiveOperator.NEQ, BigDecimal.ZERO)
        assertThat(ProductMonotonicity.findings(withBonus(eq))).hasSize(1)
    }

    @Test
    fun `a falling leaf is found under and, or, and balanceMin`() {
        val min = Predicate.BalanceMin(Window(30), WaiveOperator.LTE, BigDecimal("500"))
        val events = Predicate.NoEvents(EventKind.WITHDRAWAL, Window(30))
        assertThat(
            ProductMonotonicity.findings(
                withBonus(Predicate.Or(listOf(events, Predicate.And(listOf(balanceGte, min))))),
            ),
        )
            .extracting<String> { it.message }.containsExactly("bonus is gated by balanceMin(30d) <= 500")
        val other = Predicate.Comparison(WaiveAttribute.MONTHLY_TURNOVER, WaiveOperator.LT, BigDecimal.ONE)
        assertThat(ProductMonotonicity.findings(withBonus(other))).isEmpty()
    }

    @Test
    fun `a tier that pays less than the one below, and a gated accrue rule, are flagged`() {
        val rev = IrFixtures.termDeposit()
        val t = rev.interest!!.tiers
        val falling = rev.copy(
            interest = rev.interest.copy(
                tiers = t.zip(t.reversed()) { a, b ->
                    a.copy(rate = b.rate)
                },
            ),
        )
        assertThat(ProductMonotonicity.findings(falling)).singleElement().extracting { it.message }
            .asString().startsWith("tier 1 rate")
        val gated = rev.copy(
            hooks = rev.hooks + (
                Hook.SCHEDULED to rev.hooks.getValue(Hook.SCHEDULED).map {
                    if (it.action == Action.AccrueInterest) it.copy(whenPredicate = balanceLt) else it
                }
                ),
        )
        assertThat(ProductMonotonicity.findings(gated)).extracting<String> { it.message }
            .containsExactly("accrual is gated by BALANCE < 40000")
        assertThat(ProductMonotonicity.findings(rev.copy(interest = null))).isEmpty()
    }

    @Test
    fun `a projected balance below a threshold is flagged, a posting amount is not`() {
        val projected = Predicate.PostingComparison(
            PostingAttribute.PROJECTED_BALANCE,
            WaiveOperator.LT,
            BigDecimal("100"),
        )
        assertThat(ProductMonotonicity.findings(withBonus(projected))).extracting<String> { it.message }
            .containsExactly("bonus is gated by PROJECTED_BALANCE < 100")
        val rising = Predicate.PostingComparison(
            PostingAttribute.PROJECTED_BALANCE,
            WaiveOperator.GTE,
            BigDecimal("100"),
        )
        assertThat(ProductMonotonicity.findings(withBonus(rising))).isEmpty()
        val amount = Predicate.PostingComparison(PostingAttribute.POSTING_AMOUNT, WaiveOperator.LT, BigDecimal("100"))
        assertThat(ProductMonotonicity.findings(withBonus(amount))).isEmpty()
    }

    @Test
    fun `an accrue rule on any hook is checked, not only on scheduled`() {
        val rev = IrFixtures.savings()
        val onPost = HookRule("posting.accrue", null, balanceLt, Action.AccrueInterest)
        val withPostingAccrual = rev.copy(hooks = rev.hooks + (Hook.POST_POSTING to listOf(onPost)))
        assertThat(ProductMonotonicity.findings(withPostingAccrual)).extracting<String> { it.ruleId }
            .containsExactly("posting.accrue")
    }
}
