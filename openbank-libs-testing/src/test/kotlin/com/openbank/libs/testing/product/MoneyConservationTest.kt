// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.product

import com.openbank.libs.product.ir.AccountingClass
import com.openbank.libs.product.ir.Instruction
import com.openbank.libs.product.ir.PostingDirection
import com.openbank.libs.product.ir.PostingLine
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MoneyConservationTest {

    private fun post(vararg lines: PostingLine) = Instruction.Post("r", "h", 1, lines.toList(), "n")

    @Test
    fun `an unbalanced Post fails with the per-currency totals, a balanced set passes`() {
        val bad = post(
            PostingLine(AccountingClass.CUSTOMER_DEPOSIT, PostingDirection.DEBIT, BigDecimal("5"), "EUR"),
            PostingLine(AccountingClass.FEE_INCOME, PostingDirection.CREDIT, BigDecimal("4"), "EUR"),
        )
        assertThatThrownBy { assertMoneyConserved(listOf(bad, Instruction.Reject("x", "h", 1, "R"))) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageContaining("rule r: {EUR=debit=5 credit=4}")
        assertThat(
            MoneyConservation.violations(
                listOf(
                    bad.copy(
                        lines =
                        bad.lines +
                            PostingLine(AccountingClass.SETTLEMENT, PostingDirection.CREDIT, BigDecimal.ONE, "EUR"),
                    ),
                ),
            ),
        ).isEmpty()
    }
}
