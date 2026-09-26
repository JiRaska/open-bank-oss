// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.customeredge.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The published contract of GET /business/company, parsed as YAML. Field names are literals here
 * on purpose: the resource test compares the live response's keys against this same schema, so a
 * rename on either side reddens one of the two.
 */
class CustomerBusinessCompanyOpenApiTest {
    private val spec: JsonNode = ObjectMapper(YAMLFactory())
        .readTree(requireNotNull(javaClass.getResource("/openapi.yaml")).readText())
    private val schema = spec.path("components").path("schemas").path("BusinessCompany")

    private fun JsonNode.keys(): List<String> = fieldNames().asSequence().toList()

    @Test
    fun `the route requires X-Acting-For and documents the 403`() {
        val get = spec.path("paths").path("/business/company").path("get")
        val header = get.path("parameters").first { it.path("name").asText() == "X-Acting-For" }
        assertThat(header.path("in").asText()).isEqualTo("header")
        assertThat(header.path("required").asBoolean()).isTrue()
        assertThat(get.path("responses").has("403")).isTrue()
        val ref = get.path(
            "responses",
        ).path("200").path("content").path("application/json").path("schema").path("\$ref")
        assertThat(ref.asText()).isEqualTo("#/components/schemas/BusinessCompany")
    }

    @Test
    fun `the schema carries exactly the company fields`() {
        val props = schema.path("properties")
        assertThat(props.keys()).containsExactlyInAnyOrder(
            "partyId",
            "legalName",
            "registrationNumber",
            "legalForm",
            "seat",
            "status",
            "representatives",
            "signingRule",
            "accounts",
        )
        assertThat(props.path("seat").path("properties").keys())
            .containsExactlyInAnyOrder("street", "city", "postalCode", "country")
        assertThat(props.path("representatives").path("items").path("properties").keys())
            .containsExactlyInAnyOrder("name", "role", "partyId", "isYou")
        assertThat(props.path("accounts").path("items").path("properties").keys())
            .containsExactlyInAnyOrder("id", "iban", "currency", "product")
    }
}
