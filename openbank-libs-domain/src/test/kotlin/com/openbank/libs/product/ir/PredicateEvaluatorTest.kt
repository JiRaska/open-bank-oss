// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.ir

import com.openbank.libs.product.WaiveAttribute
import com.openbank.libs.product.WaiveOperator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PredicateEvaluatorTest {

    private val today = IrFixtures.day
    private val state = IrFixtures.state("2500.00").copy(
        segment = "VIP",
        monthlyTurnover = BigDecimal("900.00"),
        events = listOf(
            AccountEvent(EventKind.WITHDRAWAL, today.minusDays(3), BigDecimal("100.00")),
            AccountEvent(EventKind.WITHDRAWAL, today.minusDays(40), BigDecimal("999.00")),
            AccountEvent(EventKind.DEPOSIT, today, BigDecimal("50.00")),
        ),
        balanceSamples = listOf(
            BalanceSample(today.minusDays(10), BigDecimal("1200.00")),
            BalanceSample(today.minusDays(1), BigDecimal("2500.00")),
            BalanceSample(today.minusDays(60), BigDecimal("1.00")),
        ),
    )

    private fun t(p: Predicate, s: ProductState = state, posting: Posting? = null) =
        PredicateEvaluator.test(p, s, posting, today)

    @Test
    fun `event predicates honour the window inclusive of today`() {
        assertThat(t(Predicate.NoEvents(EventKind.WITHDRAWAL, Window(30)))).isFalse()
        assertThat(t(Predicate.NoEvents(EventKind.WITHDRAWAL, Window(4)))).isFalse() // today-3 .. today
        assertThat(t(Predicate.NoEvents(EventKind.WITHDRAWAL, Window(3)))).isTrue() // today-2 .. today
        assertThat(t(Predicate.NoEvents(EventKind.CARD_PAYMENT, Window(365)))).isTrue()
        assertThat(t(Predicate.CountEvents(EventKind.WITHDRAWAL, Window(365), WaiveOperator.EQ, 2))).isTrue()
        assertThat(t(Predicate.CountEvents(EventKind.WITHDRAWAL, Window(30), WaiveOperator.GT, 1))).isFalse()
        assertThat(
            t(Predicate.SumEvents(EventKind.WITHDRAWAL, Window(365), WaiveOperator.GTE, BigDecimal("1099"))),
        ).isTrue()
        assertThat(t(Predicate.SumEvents(EventKind.DEPOSIT, Window(1), WaiveOperator.EQ, BigDecimal("50")))).isTrue()
    }

    @Test
    fun `balanceMin takes the minimum sample in the window and fails closed without samples`() {
        assertThat(t(Predicate.BalanceMin(Window(30), WaiveOperator.GTE, BigDecimal("1000")))).isTrue()
        assertThat(t(Predicate.BalanceMin(Window(90), WaiveOperator.GTE, BigDecimal("1000")))).isFalse()
        assertThat(
            t(
                Predicate.BalanceMin(Window(30), WaiveOperator.GTE, BigDecimal.ZERO),
                state.copy(balanceSamples = emptyList()),
            ),
        ).isFalse()
    }

    @Test
    fun `comparison keeps the waiver-grammar semantics including fail-closed cases`() {
        assertThat(
            t(Predicate.Comparison(WaiveAttribute.BALANCE, WaiveOperator.GT, BigDecimal("2000"), currency = "EUR")),
        ).isTrue()
        assertThat(
            t(Predicate.Comparison(WaiveAttribute.BALANCE, WaiveOperator.GT, BigDecimal("2000"), currency = "CZK")),
        ).isFalse()
        assertThat(t(Predicate.Comparison(WaiveAttribute.BALANCE, WaiveOperator.GT, null))).isFalse()
        assertThat(
            t(Predicate.Comparison(WaiveAttribute.MONTHLY_TURNOVER, WaiveOperator.LTE, BigDecimal("900"))),
        ).isTrue()
        assertThat(
            t(Predicate.Comparison(WaiveAttribute.MONTHLY_TURNOVER, WaiveOperator.LT, BigDecimal("900"))),
        ).isFalse()
        assertThat(
            t(Predicate.Comparison(WaiveAttribute.AGGREGATE_POCKET_BALANCE, WaiveOperator.NEQ, BigDecimal.ZERO)),
        ).isFalse()
        assertThat(t(Predicate.Comparison(WaiveAttribute.SEGMENT, WaiveOperator.EQ, textValue = "vip"))).isTrue()
        assertThat(t(Predicate.Comparison(WaiveAttribute.SEGMENT, WaiveOperator.NEQ, textValue = "VIP"))).isFalse()
        assertThat(t(Predicate.Comparison(WaiveAttribute.SEGMENT, WaiveOperator.GT, textValue = "VIP"))).isFalse()
        assertThat(t(Predicate.Comparison(WaiveAttribute.SEGMENT, WaiveOperator.EQ, textValue = null))).isFalse()
        assertThat(
            t(
                Predicate.Comparison(WaiveAttribute.SEGMENT, WaiveOperator.EQ, textValue = "VIP"),
                state.copy(segment = null),
            ),
        ).isFalse()
        assertThat(t(Predicate.Comparison(WaiveAttribute.CURRENCY, WaiveOperator.EQ, textValue = "EUR"))).isTrue()
    }

    @Test
    fun `posting comparison needs a posting and projects the balance by direction`() {
        val p = Predicate.PostingComparison(PostingAttribute.PROJECTED_BALANCE, WaiveOperator.LT, BigDecimal.ZERO)
        assertThat(t(p)).isFalse()
        assertThat(t(p, posting = Posting(EventKind.WITHDRAWAL, PostingDirection.DEBIT, BigDecimal("3000")))).isTrue()
        assertThat(t(p, posting = Posting(EventKind.DEPOSIT, PostingDirection.CREDIT, BigDecimal("3000")))).isFalse()
        val amt = Predicate.PostingComparison(PostingAttribute.POSTING_AMOUNT, WaiveOperator.GTE, BigDecimal("3000"))
        assertThat(t(amt, posting = Posting(EventKind.WITHDRAWAL, PostingDirection.DEBIT, BigDecimal("3000")))).isTrue()
    }

    @Test
    fun `and or not compose`() {
        val yes = Predicate.NoEvents(EventKind.CARD_PAYMENT, Window(1))
        val no = Predicate.NoEvents(EventKind.DEPOSIT, Window(1))
        assertThat(t(Predicate.And(listOf(yes, no)))).isFalse()
        assertThat(t(Predicate.Or(listOf(yes, no)))).isTrue()
        assertThat(t(Predicate.Not(no))).isTrue()
        assertThat(t(Predicate.And(emptyList()))).isTrue()
    }

    @Test
    fun `every operator compares as its symbol says`() {
        val one = BigDecimal.ONE
        assertThat(WaiveOperator.entries.map { PredicateEvaluator.compare(one, it, one) })
            .containsExactly(false, true, false, true, true, false)
    }
}
