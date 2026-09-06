// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.client

import io.mockk.every
import io.mockk.mockk
import io.smallrye.mutiny.Uni
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ProductCatalogAdapterTest {

    private val client = mockk<ProductCatalogClient>()
    private val adapter = ProductCatalogAdapter().also { it.client = client }
    private val productId: UUID = UUID.randomUUID()

    private fun respond(name: String?, vararg terms: String?) {
        every { client.getById(productId.toString()) } returns Uni.createFrom().item(
            ProductClientResponse(
                id = productId.toString(),
                code = "CURRENT_BASIC",
                name = name,
                termsAndConditions = terms.map { TermsAndConditionsClientResponse(it) },
            ),
        )
    }

    @Test
    fun `the first NON-NULL documentTemplateCode wins, skipping earlier terms without one`(): Unit = runBlocking {
        respond("Basic current account", null, "RAMCOVA_SMLOUVA", "VOP")

        assertThat(adapter.findDocumentTemplateCode(productId)).isEqualTo("RAMCOVA_SMLOUVA")
    }

    @Test
    fun `a product whose terms carry no template code yields null`(): Unit = runBlocking {
        respond("Basic current account", null, null)

        assertThat(adapter.findDocumentTemplateCode(productId)).isNull()
    }

    @Test
    fun `a product with no terms at all yields null`(): Unit = runBlocking {
        respond("Basic current account")

        assertThat(adapter.findDocumentTemplateCode(productId)).isNull()
    }

    @Test
    fun `findProduct carries the code through and tolerates an absent name`(): Unit = runBlocking {
        respond(null, "VOP")

        val info = adapter.findProduct(productId)
        assertThat(info?.code).isEqualTo("CURRENT_BASIC")
        assertThat(info?.name).isNull()
    }

    @Test
    fun `an unreachable product-catalog fails OPEN on both reads`(): Unit = runBlocking {
        every { client.getById(any()) } returns Uni.createFrom().failure(RuntimeException("connection refused"))

        assertThat(adapter.findDocumentTemplateCode(productId)).isNull()
        assertThat(adapter.findProduct(productId)).isNull()
    }
}
