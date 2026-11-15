// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.product

/** The committed golden set: every scenario under `product-golden/`, listed in its `index.txt`. */
object GoldenScenarios {
    private const val INDEX = "/product-golden/index.txt"

    fun names(): List<String> = text(INDEX).lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    fun load(name: String): GoldenScenario = GoldenScenario.parse(text("/product-golden/$name"))

    fun all(): List<GoldenScenario> = names().map { load(it) }

    private fun text(path: String): String =
        checkNotNull(GoldenScenarios::class.java.getResourceAsStream(path)) { "missing classpath resource $path" }
            .readBytes().toString(Charsets.UTF_8)
}
