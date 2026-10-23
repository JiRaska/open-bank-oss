// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.products

import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.Instruction
import com.openbank.libs.product.ir.InstructionCanonical
import com.openbank.libs.product.ir.IrLimits
import com.openbank.libs.product.ir.ProductClock
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.testing.product.ProductArb
import com.openbank.libs.testing.product.assertMoneyConserved
import com.openbank.products.catalog.ReferenceProducts
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bigDecimal
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneOffset

/**
 * Properties over [ProductArb] for the committed reference products (ADR-0352 phase 2): under any
 * generated state and event each product stays within the run limits, conserves money, stamps every
 * instruction with its own hash, and evaluates deterministically.
 */
class ReferenceProductPropertyTest {

    private val interpreter = ProductInterpreter(FinanceProductArithmetic)

    @TestFactory
    fun `bounded, balanced, hash-stamped and deterministic under generated states and events`() =
        ReferenceProducts.all.map { p ->
            DynamicTest.dynamicTest(p.name) {
                val rev = p.compile()
                val cases = ProductArb.today.flatMap { today ->
                    // ProductArb draws balances uniformly up to 500 000, so a waiver threshold of 1 000 almost
                    // never fails and the fee's Post path is not exercised. Half the cases are re-drawn below
                    // 2 000 so every reference product's posting rules are reached (the counters below assert it).
                    ProductArb.state(rev.currency, today).flatMap { generated ->
                        Arb.boolean().flatMap { low ->
                            Arb.bigDecimal(BigDecimal("0.00"), BigDecimal("2000.00")).map { b ->
                                if (low) generated.copy(balance = b.setScale(2, RoundingMode.HALF_UP)) else generated
                            }
                        }
                    }.flatMap { state ->
                        ProductArb.event(rev).map { event -> Triple(today, state, event) }
                    }
                }
                var nonEmpty = 0
                var posts = 0
                runBlocking {
                    checkAll(PropTestConfig(iterations = 200), cases) { (today, state, event) ->
                        val clock = ProductClock(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
                        val out = interpreter.evaluate(rev, state, event, clock)
                        if (out.isNotEmpty()) nonEmpty++
                        posts += out.count { it is Instruction.Post }
                        assertThat(out.size).isLessThanOrEqualTo(IrLimits.MAX_INSTRUCTIONS_PER_EVALUATION)
                        assertMoneyConserved(out)
                        assertThat(out).allMatch {
                            it.revisionHash == rev.revisionHash && it.irVersion == rev.irVersion
                        }
                        assertThat(InstructionCanonical.write(interpreter.evaluate(rev, state, event, clock)))
                            .isEqualTo(InstructionCanonical.write(out))
                    }
                }
                // Without these the properties above pass vacuously on 200 empty evaluations.
                assertThat(nonEmpty).describedAs("evaluations that produced any instruction").isGreaterThan(0)
                assertThat(posts).describedAs("Post instructions checked for money conservation").isGreaterThan(0)
            }
        }
}
