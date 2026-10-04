// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure

import com.openbank.treasury.domain.model.ProductLimit
import com.openbank.treasury.domain.model.ProductLimitPolicy
import com.openbank.treasury.domain.model.ProductType
import io.smallrye.config.ConfigMapping
import io.smallrye.config.WithParentName
import java.math.BigDecimal
import java.util.Optional

/**
 * `openbank.treasury.product-limits` (ADR-0315 D4): one entry per [ProductType] name, each with
 * `max-principal` by currency (the currency set IS the allowed list) and an optional
 * `max-tenor-days`. Keys are product and currency codes, which carry no dot, so SmallRye's quoting
 * of dotted leaf keys cannot bite. A product with no entry is not permitted (fail-closed).
 */
@ConfigMapping(prefix = "openbank.treasury.product-limits")
interface ProductLimitConfig {
    @WithParentName
    fun products(): Map<String, Product>

    interface Product {
        fun maxPrincipal(): Map<String, BigDecimal>

        fun maxTenorDays(): Optional<Long>
    }

    /** An unknown product name is a typo that would leave the real product refused-by-omission: refuse to boot. */
    fun toPolicy(): ProductLimitPolicy = ProductLimitPolicy(
        products().map { (name, p) ->
            val product = requireNotNull(ProductType.entries.find { it.name == name }) {
                "openbank.treasury.product-limits: unknown product '$name' (known: ${ProductType.entries})"
            }
            ProductLimit(product, p.maxPrincipal(), p.maxTenorDays().orElse(null))
        },
    )
}
