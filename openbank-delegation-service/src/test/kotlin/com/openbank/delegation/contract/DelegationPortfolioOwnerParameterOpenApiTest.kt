// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.contract

import com.openbank.delegation.infrastructure.rest.DelegationPortfolioResource
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

/** The owner portfolio path must resolve to the same name that the served resource accepts. */
@Suppress("UNCHECKED_CAST")
class DelegationPortfolioOwnerParameterOpenApiTest {
    @Test
    fun `owner portfolio OpenAPI path parameter matches template and runtime`() {
        val spec = Yaml().load<Map<String, Any>>(requireNotNull(javaClass.getResourceAsStream("/openapi.yaml")))
        val paths = spec.getValue("paths") as Map<String, Map<String, Any>>
        val components = spec.getValue("components") as Map<String, Any>
        val parameters = components.getValue("parameters") as Map<String, Map<String, Any>>
        val route = "/api/v1/delegation-portfolios/owner/{ownerPartyId}"
        val operation = paths.getValue(route).getValue("get") as Map<String, Any>
        val refs = operation.getValue("parameters") as List<Map<String, String>>
        val pathNames = refs.map { ref -> parameters.getValue(ref.getValue("\$ref").substringAfterLast('/')) }
            .filter { it["in"] == "path" }
            .map { it.getValue("name") }

        val resource = DelegationPortfolioResource::class.java
        val list = resource.declaredMethods.single { it.name == "list" && it.isAnnotationPresent(GET::class.java) }
        val runtimePath = resource.getAnnotation(Path::class.java).value + list.getAnnotation(Path::class.java).value
        val runtimeName = list.parameters.single { it.isAnnotationPresent(PathParam::class.java) }
            .getAnnotation(PathParam::class.java).value

        assertThat(runtimePath).isEqualTo(route)
        assertThat(pathNames).containsExactly("ownerPartyId")
        assertThat(route).contains("{$runtimeName}")
        assertThat(parameters.getValue("PartyId").getValue("name")).isEqualTo("partyId")
    }
}
