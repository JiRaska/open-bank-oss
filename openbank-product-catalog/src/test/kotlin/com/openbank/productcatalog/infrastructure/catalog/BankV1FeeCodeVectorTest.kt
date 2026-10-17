// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.openbank.productcatalog.domain.Fee
import com.openbank.productcatalog.domain.ProductSeed
import com.openbank.productcatalog.infrastructure.persistence.BankV1CatalogMapping
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the price-component code a v1 fee id maps to. billing-service recomputes this code in its
 * `LegacyFees` bridge to recover a fee's v1 identity (so an already-billed fee keeps its idempotency
 * key); its `LegacyFeesTest` holds the SAME literals. If either side changes the algorithm one of the
 * two tests goes red -- otherwise every fee would silently fail closed to a non-waivable one under a
 * new idempotency key.
 */
class BankV1FeeCodeVectorTest {
    private val mapper = ObjectMapper().registerKotlinModule().findAndRegisterModules()
    private val mapping = BankV1CatalogMapping(mapper, CatalogJson(mapper))

    private fun codeOf(feeId: String): String {
        val product = ProductSeed.products.first().copy(
            fees = listOf(Fee(id = feeId, name = "n", type = "MONTHLY", amount = 1.0, currency = "CZK", frequency = "MONTHLY")),
        )
        return mapping.contentOf(product).prices.single { it.code.startsWith("FEE_") }.code
    }

    @Test
    fun `fee ids map to the literal price codes billing recomputes`() {
        assertThat(codeOf("f1")).isEqualTo("FEE_F1_3F524CDC07A11D7C6220")
        assertThat(codeOf("excess-withdrawal")).isEqualTo("FEE_EXCESS_WITHDRAWAL_7524DF43737E1210743F")
        assertThat(codeOf("3f20a53e-5ea4-307a-8a0f-9605728381ba"))
            .isEqualTo("FEE_3F20A53E_5EA4_307A_8A0F_9605728381BA_9B3EDD9EEE85705FCBC9")
    }
}
