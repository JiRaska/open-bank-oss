// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.psd2.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Psd2DomesticCurrencyContractTest {
    private val contract = ObjectMapper(YAMLFactory())
        .readTree(requireNotNull(javaClass.getResource("/openapi.yaml")).readText())

    @Test
    fun `both PIS surfaces document CZK refusal and v2 uses the Czech request shape`() {
        val berlin = contract.path("paths").path("/v1/payments/{paymentProduct}").path("post")
        val bespoke = contract.path("paths").path("/open-banking/v2/payments/domestic-cz").path("post")
        val currency = contract.path("components").path("schemas").path("PisCzechPaymentRequest")
            .path("properties").path("instructedAmount").path("properties").path("currency")

        assertThat(contract.path("info").path("version").asText()).isEqualTo("2.8.0")
        assertThat(berlin.path("requestBody").path("description").asText()).contains("domestic-cz requires CZK")
        assertThat(berlin.path("responses").path("400").path("description").asText())
            .contains("FORMAT_ERROR", "non-CZK")
        assertThat(berlin.path("responses").path("401").path("description").asText())
            .contains("Missing TPP credentials")
        assertThat(
            bespoke.path("requestBody").path("content").path("application/json")
                .path("schema").path("\$ref").asText(),
        ).isEqualTo("#/components/schemas/PisCzechPaymentRequest")
        assertThat(bespoke.path("responses").path("400").path("description").asText())
            .contains("FORMAT_ERROR", "CZK only")
        assertThat(bespoke.path("responses").path("401").path("description").asText())
            .contains("Missing or invalid TPP credentials")
        assertThat(currency.path("description").asText()).contains("domestic-cz accepts CZK only")
    }
}
