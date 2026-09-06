// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.document.infrastructure.client

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MultivaluedHashMap
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * The filter must stay INERT when no override is configured — local dev dials product-catalog
 * directly, and a `Host` header written unconditionally would misroute every call.
 */
class ProductCatalogHostHeaderFilterTest {

    private val headers = MultivaluedHashMap<String, Any>()
    private val ctx = mockk<ClientRequestContext>().also { every { it.headers } returns headers }

    @Test
    fun `a configured override is written as the Host header`() {
        val filter = ProductCatalogHostHeaderFilter().also { it.hostOverride = Optional.of("product-catalog.accounts.svc") }

        filter.filter(ctx)

        assertThat(headers.getFirst(HttpHeaders.HOST)).isEqualTo("product-catalog.accounts.svc")
    }

    @Test
    fun `no override leaves the request untouched`() {
        val filter = ProductCatalogHostHeaderFilter().also { it.hostOverride = Optional.empty() }

        filter.filter(ctx)

        assertThat(headers).doesNotContainKey(HttpHeaders.HOST)
    }

    @Test
    fun `an override REPLACES a pre-existing Host rather than appending a second one`() {
        headers.add(HttpHeaders.HOST, "stale.example")
        val filter = ProductCatalogHostHeaderFilter().also { it.hostOverride = Optional.of("product-catalog.accounts.svc") }

        filter.filter(ctx)

        assertThat(headers[HttpHeaders.HOST]).containsExactly("product-catalog.accounts.svc")
    }
}
