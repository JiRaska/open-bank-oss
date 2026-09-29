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
 * The published contract the app is built against (pinned with the app): `tin` is a map keyed by
 * country, the four booleans are required, and the route documents that the party comes from the
 * token only. Parsed as YAML rather than grepped, so a key under the wrong schema cannot pass.
 */
class CustomerAmlProfileOpenApiTest {
    private val spec: JsonNode = ObjectMapper(YAMLFactory())
        .readTree(requireNotNull(javaClass.getResource("/openapi.yaml")).readText())
    private val schemas = spec.path("components").path("schemas")

    private fun JsonNode.names(): List<String> = map { it.asText() }

    @Test
    fun `the route is published for GET and PUT with the pass-through 400`() {
        val route = spec.path("paths").path("/me/aml-profile")
        assertThat(route.has("get")).isTrue()
        assertThat(route.has("put")).isTrue()
        assertThat(route.path("put").path("responses").has("400")).isTrue()
        assertThat(route.path("get").path("responses").has("404")).isTrue()
        assertThat(route.path("put").path("description").asText())
            .contains("taken from the token only")
            .contains("X-Acting-For header are ignored")
    }

    @Test
    fun `the request requires every boolean and carries tin as a country-keyed map`() {
        val request = schemas.path("AmlProfileRequest")
        assertThat(request.path("required").names())
            .contains("cashIntensive", "usPerson", "truthful", "pep", "taxResidencies")
            .doesNotContain("tin", "partyId")
        assertThat(schemas.path("AmlPep").path("required").names()).containsExactly("isPep")
        val tin = request.path("properties").path("tin")
        assertThat(tin.path("type").asText()).isEqualTo("object")
        assertThat(tin.path("additionalProperties").path("type").asText()).isEqualTo("string")
        assertThat(request.path("properties").has("partyId")).isFalse()
        assertThat(request.path("properties").path("taxResidencies").path("items").path("type").asText())
            .isEqualTo("string")
    }

    @Test
    fun `the response tells the app when it was confirmed and whether the account needs a check`() {
        val profile = schemas.path("AmlProfile")
        assertThat(profile.path("required").names())
            .contains("version", "declaredAt", "tin", "reviewStatus", "eddRequired", "riskFactors")
        assertThat(profile.path("properties").path("reviewStatus").path("enum").names())
            .containsExactlyInAnyOrder("STANDARD", "ENHANCED_DUE_DILIGENCE")
    }
}
