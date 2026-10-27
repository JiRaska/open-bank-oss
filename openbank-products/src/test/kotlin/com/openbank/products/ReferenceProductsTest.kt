// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.products

import com.openbank.libs.product.ir.ProductIrCanonical
import com.openbank.libs.product.ir.ProductIrParser
import com.openbank.products.catalog.ReferenceProducts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * The DSL is authoring sugar and the IR JSON is the artifact (ADR-0352 §5): every reference product
 * compiles, round-trips through the parser to the same hash, and equals its committed `ir/<name>.json`
 * byte for byte — so the hash on origin/main is the hash the catalog may publish (§7).
 */
class ReferenceProductsTest {

    @TestFactory
    fun `each reference product compiles, round-trips and matches its committed IR`() = ReferenceProducts.all.map { p ->
        DynamicTest.dynamicTest(p.name) {
            val rev = p.compile()
            val canonical = p.canonicalJson()
            // Re-parsing the canonical text yields the same canonical bytes and hash (BigDecimal scales differ
            // between "0.0250" and its canonical "0.0250000000", so the bytes, not object equality, are the claim).
            val reparsed = ProductIrParser.parse(canonical)
            assertThat(ProductIrCanonical.canonicalJson(reparsed)).isEqualTo(canonical)
            assertThat(reparsed.revisionHash).isEqualTo(ProductIrCanonical.revisionHash(rev))
            val committed = Path.of("ir", "${p.name}.json")
            assertThat(committed).describedAs("run `obp compile all --out ir` and commit").exists()
            assertThat(Files.readString(committed).trimEnd()).isEqualTo(canonical)
            // Authoring parity: the committed raw JSON, read as a document, is the DSL's revision.
            assertThat(ProductIrParser.parse(Files.readString(committed)).revisionHash).isEqualTo(rev.revisionHash)
        }
    }

    @Test
    fun `the library carries the three ADR-0352 reference deposit products with distinct hashes`() {
        assertThat(
            ReferenceProducts.all.map {
                it.name
            },
        ).containsExactly("savings-fixed", "current-account", "term-deposit")
        assertThat(ReferenceProducts.all.map { it.compile().revisionHash }).doesNotHaveDuplicates()
        assertThat(ReferenceProducts.byName("nope")).isNull()
    }

    @Test
    fun `every ruleId a product declares has a recorded DSL source location`() {
        for (p in ReferenceProducts.all) {
            val rev = p.compile()
            assertThat(p.sources.keys).containsAll(rev.allRuleIds)
            assertThat(p.sources.values).allMatch { it.file == "ReferenceProducts.kt" && it.line > 0 }
        }
    }
}
