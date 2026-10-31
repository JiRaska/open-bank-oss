// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.products

import com.openbank.libs.product.ir.IrJson
import com.openbank.libs.product.replay.ReplayPortfolioJson
import com.openbank.products.catalog.ReferenceProducts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * `portfolios/approval.json` (ADR-0352 §7, phase 5b) is looked up by product code ONLY — the catalog has
 * no currency fallback, so a reference product without its own entry could never be approved. Every
 * reference product therefore has an entry, and its portfolio replays that product's golden scenario
 * account in the product's currency.
 */
class ApprovalPortfoliosTest {

    @Suppress("UNCHECKED_CAST")
    private val manifest = IrJson.parse(Files.readString(Path.of("portfolios", "approval.json"))) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private val byProductCode = manifest["byProductCode"] as Map<String, String>

    @Test
    fun `the manifest has no currency fallback`() {
        assertThat(manifest.keys).containsExactlyInAnyOrder("schema", "description", "byProductCode")
    }

    @TestFactory
    fun `each reference product has its own approval portfolio carrying its golden scenario`() =
        ReferenceProducts.all.map { p ->
            DynamicTest.dynamicTest(p.name) {
                val ir = p.compile()
                val file = byProductCode[ir.productCode]
                assertThat(file).describedAs("approval.json byProductCode entry for ${ir.productCode}").isNotNull()
                val tree = IrJson.parse(Files.readString(Path.of("portfolios", file!!)))
                val parsed = ReplayPortfolioJson.parse(tree)
                assertThat(
                    parsed.portfolio.accounts.map {
                        it.initialState.currency
                    }.toSet(),
                ).containsExactly(ir.currency)

                @Suppress("UNCHECKED_CAST")
                val golden = IrJson.parse(
                    Files.readString(
                        Path.of("src/main/resources/openbank-products/product-golden/${p.name}-reference.json"),
                    ),
                ) as Map<String, Any?>

                @Suppress("UNCHECKED_CAST")
                val accounts = (tree as Map<String, Any?>)["accounts"] as List<Map<String, Any?>>
                assertThat(accounts).anySatisfy { a ->
                    assertThat(a["state"]).isEqualTo(golden["state"])
                    assertThat(a["steps"]).isEqualTo(golden["steps"])
                }
            }
        }
}
