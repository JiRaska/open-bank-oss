// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.products

import com.openbank.libs.product.ir.InstructionCanonical
import com.openbank.libs.testing.product.GoldenScenarioRunner
import com.openbank.products.catalog.ReferenceProducts
import com.openbank.products.golden.ProductGoldens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** The reference products' golden scenarios pass, are deterministic, and describe the committed products. */
class ProductGoldenScenariosTest {

    private val runner = GoldenScenarioRunner()

    @TestFactory
    fun `golden scenarios pass and two runs are byte-identical`() = ProductGoldens.names().map { name ->
        DynamicTest.dynamicTest(name) {
            val scenario = ProductGoldens.load(name)
            runner.assertPasses(scenario)
            val first = runner.run(scenario).map { InstructionCanonical.write(it.instructions) }
            val second = runner.run(scenario).map { InstructionCanonical.write(it.instructions) }
            assertThat(second).isEqualTo(first)
        }
    }

    @Test
    fun `every reference product has a golden scenario whose revision is that product`() {
        val goldenHashes = ProductGoldens.all().map { it.revision.revisionHash }
        for (p in ReferenceProducts.all) {
            assertThat(goldenHashes).describedAs("golden scenario for ${p.name}").contains(p.compile().revisionHash)
        }
        assertThat(ProductGoldens.names()).hasSize(ReferenceProducts.all.size)
    }
}
