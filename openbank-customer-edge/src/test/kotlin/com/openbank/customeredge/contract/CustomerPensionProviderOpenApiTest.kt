// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CustomerPensionProviderOpenApiTest {
    private val spec = ObjectMapper(YAMLFactory())
        .readTree(requireNotNull(javaClass.getResource("/openapi.yaml")).readText())

    @Test
    fun `application contract publishes the unconfigured provider error`() {
        assertProviderError("/pension/applications")
    }

    @Test
    fun `transfer-in contract publishes the unconfigured provider error`() {
        assertProviderError("/pension/transfers-in")
    }

    private fun assertProviderError(path: String) {
        val response = spec.path("paths").path(path).path("post").path("responses").path("503")
        assertThat(response.isObject).describedAs("$path must publish 503").isTrue()
        val content = response.path("content").path("application/json")
        val schema = content.path("schema")
        assertThat(schema.path("type").asText()).isEqualTo("object")
        assertThat(schema.path("required").map { it.asText() }).containsExactlyInAnyOrder("error", "code")
        assertThat(schema.path("properties").path("error").path("type").asText()).isEqualTo("string")
        assertThat(schema.path("properties").path("code").path("enum").map { it.asText() })
            .containsExactly("PENSION_PROVIDER_NOT_CONFIGURED")
        assertThat(content.path("example").path("code").asText()).isEqualTo("PENSION_PROVIDER_NOT_CONFIGURED")
        assertThat(content.path("example").path("error").asText()).isNotBlank()
    }
}
