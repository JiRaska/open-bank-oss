// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.product

import com.openbank.libs.product.ir.Instruction
import com.openbank.libs.product.ir.PostingDirection
import java.math.BigDecimal

/**
 * ADR-0352 §8 money conservation: every `Post` instruction is balanced per currency. The
 * interpreter already refuses to return an unbalanced one; this assertion is the test-side twin
 * so a golden scenario or a property test states the invariant in its own words and reports every
 * offending instruction, not only the first.
 */
object MoneyConservation {

    fun violations(instructions: List<Instruction>): List<String> = instructions
        .filterIsInstance<Instruction.Post>()
        .filterNot { it.isBalancedPerCurrency() }
        .map { post ->
            val byCurrency = post.lines.groupBy { it.currency }.mapValues { (_, ls) ->
                val d = ls.filter { it.direction == PostingDirection.DEBIT }.fold(BigDecimal.ZERO) { a, l ->
                    a +
                        l.amount
                }
                val c = ls.filter { it.direction == PostingDirection.CREDIT }.fold(BigDecimal.ZERO) { a, l ->
                    a +
                        l.amount
                }
                "debit=$d credit=$c"
            }
            "rule ${post.ruleId}: $byCurrency"
        }

    /** Fails with every unbalanced `Post` listed. */
    fun assertMoneyConserved(instructions: List<Instruction>) {
        val v = violations(instructions)
        if (v.isNotEmpty()) throw AssertionError("money not conserved:\n  " + v.joinToString("\n  "))
    }
}

/** Top-level alias so a test reads `assertMoneyConserved(instructions)`. */
fun assertMoneyConserved(instructions: List<Instruction>) = MoneyConservation.assertMoneyConserved(instructions)
