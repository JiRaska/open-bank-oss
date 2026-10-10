// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DomesticPaymentCurrencyOpenApiTest {
    private val contract = ObjectMapper(YAMLFactory())
        .readTree(requireNotNull(javaClass.getResource("/openapi.yaml")).readText())

    @Test
    fun `domestic create contract specifies CZK and the early currency refusal`() {
        val create = contract.path("paths").path("/domestic-payments").path("post")
        assertThat(create.isMissingNode).isFalse()

        val currency = create.path("requestBody").path("content").path("application/json")
            .path("schema").path("properties").path("currency")
        assertThat(currency.path("default").asText()).isEqualTo("CZK")
        assertThat(currency.path("description").asText()).contains("CZK only for domestic clearing")

        val refusal = create.path("responses").path("400").path("description").asText()
        assertThat(refusal).contains("CURRENCY_NOT_ALLOWED", "before spend reservation or SCA")

        val denied = create.path("responses").path("403").path("description").asText()
        assertThat(denied).contains("Caller may not debit this account", "not an oracle")
    }
}
