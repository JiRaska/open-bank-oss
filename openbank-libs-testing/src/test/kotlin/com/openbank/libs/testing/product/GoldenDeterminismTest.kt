// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.product

import com.openbank.libs.product.ir.InstructionCanonical
import com.openbank.libs.product.ir.IrVersion
import com.openbank.libs.product.ir.ProductIrCanonical
import com.openbank.libs.product.ir.ProductIrParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * ADR-0352 §8 determinism gate (`product-ir-determinism`), JVM half: every golden scenario is run
 * twice in this one JVM and the canonical instruction bytes — hash included — must be identical.
 * The cross-`irVersion` half compares the set under every supported version; with one supported
 * version it is a no-op and [one supported irVersion makes the cross-version half a no-op] says so
 * instead of silently passing.
 */
class GoldenDeterminismTest {

    private val runner = GoldenScenarioRunner()

    @TestFactory
    fun `two runs in one JVM are byte-identical`() = GoldenScenarios.names().map { name ->
        DynamicTest.dynamicTest(name) {
            val scenario = GoldenScenarios.load(name)
            val first = runner.run(scenario).map { InstructionCanonical.write(it.instructions) }
            val second = runner.run(scenario).map { InstructionCanonical.write(it.instructions) }
            assertThat(second).isEqualTo(first)
            // The revision itself re-parses to the same canonical bytes and hash.
            val reparsed = ProductIrParser.parse(ProductIrCanonical.canonicalJson(scenario.revision))
            assertThat(reparsed.revisionHash).isEqualTo(scenario.revision.revisionHash)
        }
    }

    @Test
    fun `one supported irVersion makes the cross-version half a no-op`() {
        assertThat(IrVersion.SUPPORTED).containsExactly(1)
        println("product-ir-determinism: cross-irVersion equivalence is a NO-OP — one supported irVersion (${IrVersion.SUPPORTED}); add the equivalence table when a second version ships")
    }
}
