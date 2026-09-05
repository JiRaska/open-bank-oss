// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.domain

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import java.time.Instant

class ProductValidationTest {
    @Test
    fun `all canonical banking seeds satisfy the write invariants`() {
        assertSoftly { softly ->
            ProductSeed.products.forEach { product ->
                softly.assertThatCode { ProductValidation.requireValid(product) }
                    .describedAs("seed %s", product.code)
                    .doesNotThrowAnyException()
            }
        }
    }

    @Test
    fun `rejects invalid currency before persistence`() {
        val product = product().copy(currency = "euro")

        assertThatThrownBy { ProductValidation.requireValid(product) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ISO 4217")
    }

    @Test
    fun `rejects non-finite pricing before JSON persistence`() {
        val product = product().copy(baseRate = Double.POSITIVE_INFINITY)

        assertThatThrownBy { ProductValidation.requireValid(product) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("baseRate must be a finite number")
    }

    @Test
    fun `enabled multi-currency product must include its default currency`() {
        val product = product().copy(
            multiCurrencyConfig = MultiCurrencyConfig(
                enabled = true,
                supportedCurrencies = listOf("EUR", "USD"),
                defaultCurrency = "CZK",
            ),
        )

        assertThatThrownBy { ProductValidation.requireValid(product) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("defaultCurrency")
    }

    @Test
    fun `archived product cannot re-enter active lifecycle`() {
        val product = product().copy(status = ProductStatus.ARCHIVED)

        assertThatThrownBy { product.activate(java.time.Instant.parse("2026-08-12T10:00:00Z")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("cannot be activated")
    }

    private fun product() = Product(
        code = "SAVINGS_STANDARD",
        name = "Standard savings",
        type = "SAVINGS",
        currency = "EUR",
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
