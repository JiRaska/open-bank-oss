// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class YearEndResultAllocationPlanTest {

    private fun line(code: String, type: GlAccountType, debit: String, credit: String, currency: String = "CZK") =
        TrialBalanceLine(
            UUID.randomUUID(),
            code,
            "Account $code",
            type,
            currency,
            BigDecimal(debit),
            BigDecimal(credit),
        )

    private fun plan(vararg lines: TrialBalanceLine) = YearEndResultAllocationPlan.plan(2025, lines.toList(), "6020")

    private fun YearEndResultAllocationPlan.balance(): BigDecimal = legs.fold(BigDecimal.ZERO) { acc, l ->
        if (l.side == JournalSide.DEBIT) acc.add(l.amount) else acc.subtract(l.amount)
    }

    @Test
    fun `a profit closes income and expense and credits retained earnings with the profit`() {
        val p = plan(
            line("4100", GlAccountType.INCOME, "0", "1000"),
            line("5100", GlAccountType.EXPENSE, "300", "0"),
            line("1000", GlAccountType.ASSET, "700", "0"),
        )
        assertThat(p.legs).containsExactly(
            ResultAllocationLeg("4100", JournalSide.DEBIT, BigDecimal("1000")),
            ResultAllocationLeg("5100", JournalSide.CREDIT, BigDecimal("300")),
            ResultAllocationLeg("6020", JournalSide.CREDIT, BigDecimal("700")),
        )
        assertThat(p.netResult).isEqualByComparingTo("-700")
        assertThat(p.balance()).isEqualByComparingTo("0")
    }

    @Test
    fun `a loss debits retained earnings`() {
        val p = plan(line("4100", GlAccountType.INCOME, "0", "100"), line("5100", GlAccountType.EXPENSE, "250", "0"))
        assertThat(p.legs.last()).isEqualTo(ResultAllocationLeg("6020", JournalSide.DEBIT, BigDecimal("150")))
        assertThat(p.balance()).isEqualByComparingTo("0")
    }

    @Test
    fun `balance-sheet accounts and zero-net P and L accounts produce nothing`() {
        val p = plan(
            line("1000", GlAccountType.ASSET, "500", "0"),
            line("6020", GlAccountType.EQUITY, "0", "500"),
            line("4100", GlAccountType.INCOME, "200", "200"),
        )
        assertThat(p.isEmpty).isTrue()
        assertThat(p.netResult).isEqualByComparingTo("0")
    }

    @Test
    fun `a break-even year closes the accounts without a retained earnings leg`() {
        val p = plan(line("4100", GlAccountType.INCOME, "0", "80"), line("5100", GlAccountType.EXPENSE, "80", "0"))
        assertThat(p.legs.map { it.glAccountCode }).containsExactly("4100", "5100")
        assertThat(p.balance()).isEqualByComparingTo("0")
    }

    @Test
    fun `a non-CZK income or expense balance is refused, naming the blocking balance`() {
        assertThatThrownBy {
            plan(line("4100", GlAccountType.INCOME, "0", "10"), line("4004", GlAccountType.INCOME, "0", "5", "EUR"))
        }.isInstanceOf(LedgerConflictException::class.java).hasMessageContaining("4004 EUR")
    }

    @Test
    fun `a zero-net non-CZK account does not block`() {
        val p = plan(line("4100", GlAccountType.INCOME, "0", "10"), line("4004", GlAccountType.INCOME, "5", "5", "EUR"))
        assertThat(p.legs).hasSize(2)
    }

    @Test
    fun `digest is stable for the same plan and differs for a residual`() {
        val a = plan(line("4100", GlAccountType.INCOME, "0", "1000.00"))
        val b = plan(line("4100", GlAccountType.INCOME, "0", "1000"))
        val residual = plan(line("4100", GlAccountType.INCOME, "0", "40"))
        assertThat(a.digest()).isEqualTo(b.digest())
        assertThat(a.digest()).isNotEqualTo(residual.digest())
    }
}
