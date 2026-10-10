// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.catalog

import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MultivaluedHashMap
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Optional

class ProductCatalogHostHeaderFilterTest {
    private val headers = MultivaluedHashMap<String, Any>()
    private val request = mockk<ClientRequestContext>().also { every { it.headers } returns headers }

    @Test
    fun `configured host replaces the URL host for KEDA routing`() {
        headers.add(HttpHeaders.HOST, "old-host")
        val filter = ProductCatalogHostHeaderFilter().also { it.hostOverride = Optional.of("catalog-host") }

        filter.filter(request)

        assertThat(headers[HttpHeaders.HOST]).containsExactly("catalog-host")
    }

    @Test
    fun `local direct catalog URL keeps its own host`() {
        val filter = ProductCatalogHostHeaderFilter().also { it.hostOverride = Optional.empty() }

        filter.filter(request)

        assertThat(headers).doesNotContainKey(HttpHeaders.HOST)
    }
}
