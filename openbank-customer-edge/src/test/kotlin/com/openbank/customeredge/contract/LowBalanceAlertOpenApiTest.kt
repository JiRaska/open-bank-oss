// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LowBalanceAlertOpenApiTest {
    private val contract = ObjectMapper(YAMLFactory())
        .readTree(requireNotNull(javaClass.getResource("/openapi.yaml")).readText())

    @Test
    fun `low balance currency is one uppercase path segment and invalid values return 400`() {
        val endpoint = contract.path("paths").path("/balances/{accountId}/{currency}/low-balance-alert")
        val currency = endpoint.path("parameters").first { it.path("name").asText() == "currency" }.path("schema")
        assertThat(currency.path("pattern").asText()).isEqualTo("^[A-Z]{3}$")
        for (method in listOf("get", "put")) {
            assertThat(endpoint.path(method).path("responses").has("400")).isTrue()
            assertThat(endpoint.path(method).path("responses").has("403")).isTrue()
        }
    }
}
