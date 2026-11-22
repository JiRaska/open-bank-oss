// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.

package com.openbank.account.application.usecase

import com.openbank.account.application.port.out.CatalogProduct
import com.openbank.account.application.port.out.ProductCatalogPort
import com.openbank.account.application.port.out.ProductLookupResult
import com.openbank.account.application.port.out.ProductRevisionPinMetrics
import com.openbank.account.application.port.out.PublishedRevision
import com.openbank.account.application.port.out.RevisionResolution
import com.openbank.libs.market.OfferingMarkets
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ProductRevisionMarketTest {
    @Test
    fun `domestic opening refuses an offering restricted to another market`() {
        val product = UUID.randomUUID()
        val catalogue = mockk<ProductCatalogPort>()
        val pinning = ProductRevisionPinning(catalogue, mockk<ProductRevisionPinMetrics>(relaxed = true))
        val revision = PublishedRevision(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            null,
            null,
            null,
            marketCountries = OfferingMarkets(setOf("SK")),
        )
        coEvery { catalogue.findById(product) } returns
            ProductLookupResult.Found(CatalogProduct(product, "CURRENT", "ACTIVE", "CZK"))
        coEvery { catalogue.resolvePublishedRevision(product, any()) } returns RevisionResolution.Published(revision)

        assertThatThrownBy { runBlocking { pinning.pinFor(product, "CZK", Instant.EPOCH) } }
            .isInstanceOf(ProductNotEligibleException::class.java)
            .hasMessageContaining("outside the Czech domestic route")
    }
}
