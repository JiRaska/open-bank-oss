// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BusinessPaymentBatchOpenApiTest {
    private val spec: JsonNode = ObjectMapper(YAMLFactory())
        .readTree(requireNotNull(javaClass.getResource("/openapi.yaml")).readText())

    @Test
    fun `every draft operation declares the implemented authentication scheme`() {
        val operations = listOf(
            "/business/payment-batches" to "get",
            "/business/payment-batches" to "post",
            "/business/payment-batches/{id}" to "get",
            "/business/payment-batches/{id}/items" to "put",
        )
        operations.forEach { (path, method) ->
            val security = spec.path("paths").path(path).path(method).path("security")
            assertThat(security.size()).isEqualTo(1)
            assertThat(security[0].fieldNames().asSequence().toList()).containsExactly("customerOidc")
            assertThat(security[0].path("customerOidc").isArray).isTrue()
            assertThat(security[0].path("customerOidc").size()).isZero()
        }
    }

    @Test
    fun `all successful draft routes declare their JSON responses`() {
        val paths = spec.path("paths")
        val expected = listOf(
            Triple("/business/payment-batches", "get", "200" to "BusinessPaymentBatchDraftPage"),
            Triple("/business/payment-batches", "post", "200" to "BusinessPaymentBatchDraft"),
            Triple("/business/payment-batches", "post", "201" to "BusinessPaymentBatchDraft"),
            Triple("/business/payment-batches/{id}", "get", "200" to "BusinessPaymentBatchDraft"),
            Triple("/business/payment-batches/{id}/items", "put", "200" to "BusinessPaymentBatchDraft"),
        )
        expected.forEach { (path, method, response) ->
            val ref = paths.path(path).path(method).path("responses").path(response.first)
                .path("content").path("application/json").path("schema").path("\$ref")
            assertThat(ref.asText()).isEqualTo("#/components/schemas/${response.second}")
        }
    }

    @Test
    fun `a caller without a company mandate has a 403 contract`() {
        val path = spec.path("paths").path("/business/payment-batches")
        for (method in listOf("get", "post")) {
            assertThat(path.path(method).path("responses").has("403")).isTrue()
            val header = path.path(method).path("parameters").first {
                it.path("name").asText() == "X-Acting-For"
            }
            assertThat(header.path("required").asBoolean()).isTrue()
        }
    }

    @Test
    fun `draft schemas declare the returned fields and item pagination`() {
        val schemas = spec.path("components").path("schemas")
        val draft = schemas.path("BusinessPaymentBatchDraft")
        assertThat(draft.path("properties").fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "id", "state", "debtorAccountId", "itemCount", "totalAmountMinor", "currency",
            "revision", "createdAt", "updatedAt", "items",
        )
        assertThat(draft.path("required").map { it.asText() }).containsAll(
            listOf("id", "state", "debtorAccountId", "itemCount", "totalAmountMinor", "revision"),
        )
        assertThat(draft.path("properties").path("items").path("items").path("\$ref").asText())
            .isEqualTo("#/components/schemas/BusinessPaymentBatchDraftItem")
        val page = schemas.path("BusinessPaymentBatchDraftPage")
        assertThat(page.path("properties").fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrder("data", "page", "size")
        assertThat(page.path("properties").path("data").path("items").path("\$ref").asText())
            .isEqualTo("#/components/schemas/BusinessPaymentBatchDraft")
    }
}
