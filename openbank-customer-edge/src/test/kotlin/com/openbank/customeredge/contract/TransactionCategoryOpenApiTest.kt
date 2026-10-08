// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.openbank.libs.spend.SpendCategory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TransactionCategoryOpenApiTest {
    private val spec = ObjectMapper(YAMLFactory())
        .readTree(requireNotNull(javaClass.getResource("/openapi.yaml")).readText())

    @Test
    fun `edge publishes account-scoped category operations and backend vocabulary`() {
        val paths = spec.path("paths")
        val list = paths.path("/transactions/category-overrides").path("get")
        val detail = paths.path("/transactions/{transactionId}/category")
        assertThat(list.path("parameters").toString()).contains("accountId")
        assertThat(detail.path("put").path("parameters").toString()).contains("accountId", "transactionId")
        val request = detail.path("put").path("requestBody").path("content").path("application/json")
        assertThat(request.path("schema").path("required").map { it.asText() })
            .containsExactly("category")
        assertThat(detail.path("delete").path("responses").has("204")).isTrue()

        val schemas = spec.path("components").path("schemas")
        assertThat(schemas.path("SpendCategory").path("enum").map { it.asText() })
            .containsExactlyElementsOf(SpendCategory.IDS)
        val override = schemas.path("TransactionCategoryOverride")
        assertThat(override.path("properties").has("counterpartyKey")).isTrue()
        val categorySource = schemas.path("Transaction").path("properties").path("categorySource")
        assertThat(categorySource.path("enum").map { it.asText() })
            .containsExactly("CUSTOMER", "MCC", "CATALOGUE")
    }
}
