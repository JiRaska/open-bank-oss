// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.products.golden

import com.openbank.libs.testing.product.GoldenScenario

/**
 * The reference products' golden scenarios (ADR-0352 §8, §11.3): every file under
 * `openbank-products/product-golden/` on the classpath, listed in its `index.txt`. Same format and
 * same runner as the phase-1 set in openbank-libs-testing, under this module's own resource prefix so
 * the two jars never shadow each other's `index.txt`. The `product-ir-determinism` gate derives its
 * scope from every `product-golden/` directory, so this one is in scope by construction.
 */
object ProductGoldens {
    const val PREFIX = "/openbank-products/product-golden"

    fun names(): List<String> = text("$PREFIX/index.txt").lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    fun load(name: String): GoldenScenario = GoldenScenario.parse(text("$PREFIX/$name"))

    fun all(): List<GoldenScenario> = names().map { load(it) }

    private fun text(path: String): String =
        checkNotNull(ProductGoldens::class.java.getResourceAsStream(path)) { "missing classpath resource $path" }
            .readBytes().toString(Charsets.UTF_8)
}
