// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.product

import com.openbank.libs.finance.deposit.FinanceProductArithmetic
import com.openbank.libs.product.ir.InstructionCanonical
import com.openbank.libs.product.ir.IrLimits
import com.openbank.libs.product.ir.ProductClock
import com.openbank.libs.product.ir.ProductInterpreter
import com.openbank.libs.product.ir.ProductIrCanonical
import com.openbank.libs.product.ir.ProductIrParser
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

/** Phase 1 properties over [ProductArb]: bounded, balanced, deterministic, hash-stable. */
class ProductArbPropertyTest {

    private val interpreter = ProductInterpreter(FinanceProductArithmetic)

    private val cases = ProductArb.revision.flatMap { rev ->
        ProductArb.today.flatMap { today ->
            ProductArb.state(rev.currency, today).flatMap { state ->
                ProductArb.event(rev).map { event -> Triple(rev, state to today, event) }
            }
        }
    }

    @Test
    fun `every generated revision is valid, hash-stable under re-parse, and evaluates within the limits`(): Unit =
        runBlocking {
            checkAll(PropTestConfig(iterations = 300), cases) { (rev, stateAndDay, event) ->
                val (state, today) = stateAndDay
                val clock = ProductClock(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
                val out = interpreter.evaluate(rev, state, event, clock)
                assertThat(out.size).isLessThanOrEqualTo(IrLimits.MAX_INSTRUCTIONS_PER_EVALUATION)
                assertMoneyConserved(out)
                assertThat(out).allMatch { it.revisionHash == rev.revisionHash && it.irVersion == rev.irVersion }
                assertThat(
                    InstructionCanonical.write(interpreter.evaluate(rev, state, event, clock)),
                ).isEqualTo(InstructionCanonical.write(out))
                assertThat(
                    ProductIrParser.parse(ProductIrCanonical.canonicalJson(rev)).revisionHash,
                ).isEqualTo(rev.revisionHash)
            }
        }
}
