// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.ir

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ProductStateTransitionTest {

    private val day = IrFixtures.day

    private fun post(vararg lines: PostingLine) = Instruction.Post("r", "h", 1, lines.toList(), "n")
    private fun line(cls: AccountingClass, dir: PostingDirection, amt: String) =
        PostingLine(cls, dir, BigDecimal(amt), "EUR")

    @Test
    fun `accrual raises accrued interest, capitalisation moves it to the balance, a fee lowers the balance`() {
        val s0 = IrFixtures.state("100.00")
        val s1 = ProductStateTransition.apply(
            s0,
            listOf(
                post(
                    line(AccountingClass.INTEREST_EXPENSE, PostingDirection.DEBIT, "0.5"),
                    line(AccountingClass.ACCRUED_INTEREST_PAYABLE, PostingDirection.CREDIT, "0.5"),
                ),
            ),
            day,
        )
        assertThat(s1.accruedInterest).isEqualByComparingTo("0.5")
        assertThat(s1.balance).isEqualByComparingTo("100.00")
        val s2 = ProductStateTransition.apply(
            s1,
            listOf(
                post(
                    line(AccountingClass.ACCRUED_INTEREST_PAYABLE, PostingDirection.DEBIT, "0.5"),
                    line(AccountingClass.CUSTOMER_DEPOSIT, PostingDirection.CREDIT, "0.5"),
                ),
                post(
                    line(AccountingClass.CUSTOMER_DEPOSIT, PostingDirection.DEBIT, "5"),
                    line(AccountingClass.FEE_INCOME, PostingDirection.CREDIT, "5"),
                ),
                Instruction.Reprice("r", "h", 1, "interest.rate", BigDecimal("0.03")),
                Instruction.Notify("r", "h", 1, "t", emptyMap()),
            ),
            day.plusDays(1),
        )
        assertThat(s2.accruedInterest).isEqualByComparingTo("0")
        assertThat(s2.balance).isEqualByComparingTo("95.5")
        assertThat(s2.parameters).containsEntry("interest.rate", BigDecimal("0.03"))
        assertThat(s2.events.map { it.kind }).containsExactly(EventKind.INTEREST, EventKind.FEE)
        assertThat(s2.balanceSamples.map { it.balance }).containsExactly(BigDecimal("100.00"), BigDecimal("95.50"))
    }

    @Test
    fun `the event kind follows the counter line, so a penalty or pay-out is never counted as a fee`() {
        val cases = mapOf(
            AccountingClass.FEE_INCOME to EventKind.FEE,
            AccountingClass.PENALTY_INCOME to EventKind.PENALTY,
            AccountingClass.SETTLEMENT to EventKind.TRANSFER_OUT,
            AccountingClass.ACCRUED_INTEREST_PAYABLE to EventKind.INTEREST,
        )
        for ((counter, kind) in cases) {
            val interest = counter == AccountingClass.ACCRUED_INTEREST_PAYABLE
            val dir = if (interest) PostingDirection.CREDIT else PostingDirection.DEBIT
            val other = if (dir == PostingDirection.CREDIT) PostingDirection.DEBIT else PostingDirection.CREDIT
            val s = ProductStateTransition.apply(
                IrFixtures.state("100.00"),
                listOf(post(line(AccountingClass.CUSTOMER_DEPOSIT, dir, "1"), line(counter, other, "1"))),
                day,
            )
            assertThat(s.events.single().kind).withFailMessage("$counter").isEqualTo(kind)
        }
        val creditOnly = post(line(AccountingClass.CUSTOMER_DEPOSIT, PostingDirection.CREDIT, "1"))
        assertThat(ProductStateTransition.eventKindOf(creditOnly)).isEqualTo(EventKind.TRANSFER_IN)
    }

    @Test
    fun `an accepted customer posting moves the balance and records the event`() {
        val s = ProductStateTransition.applyPosting(
            IrFixtures.state("100.00"),
            Posting(EventKind.WITHDRAWAL, PostingDirection.DEBIT, BigDecimal("30")),
            day,
        )
        assertThat(s.balance).isEqualByComparingTo("70")
        assertThat(s.events.single().kind).isEqualTo(EventKind.WITHDRAWAL)
        val d = ProductStateTransition.applyPosting(
            s,
            Posting(EventKind.DEPOSIT, PostingDirection.CREDIT, BigDecimal("5")),
            day,
        )
        assertThat(d.balance).isEqualByComparingTo("75")
    }
}
