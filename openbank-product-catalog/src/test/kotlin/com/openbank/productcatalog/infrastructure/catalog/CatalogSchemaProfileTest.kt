// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.infrastructure.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CatalogSchemaProfileTest {
    private val mapper = ObjectMapper()
    private val profile = CatalogSchemaProfile()

    @Test
    fun `rejects references outside local defs and dynamic references`() {
        val propertyReference = schema("\"${'$'}ref\":\"#/properties/name\"")
        val dynamicReference = schema("\"${'$'}dynamicRef\":\"#/${'$'}defs/name\"")

        assertThatThrownBy { profile.requireValid(mapper.readTree(propertyReference)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("local \$defs")
        assertThatThrownBy { profile.requireValid(mapper.readTree(dynamicReference)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("dynamic references")
    }

    @Test
    fun `rejects oversized and deeply nested instances`() {
        val oversized = mapper.createObjectNode().put("value", "x".repeat(CatalogSchemaProfile.MAX_INSTANCE_BYTES))
        var deeplyNested = mapper.createObjectNode()
        repeat(CatalogSchemaProfile.MAX_NESTING_DEPTH + 1) {
            deeplyNested = mapper.createObjectNode().set("child", deeplyNested)
        }

        assertThatThrownBy { profile.requireValidInstance(oversized) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("too large")
        assertThatThrownBy { profile.requireValidInstance(deeplyNested) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("nesting")
    }

    @Test
    fun `requires closed objects for type unions and implicit object applicators`() {
        val objectUnion =
            """{"${'$'}schema":"${CatalogSchemaProfile.DIALECT}","type":["object","null"]}"""
        val implicitObject =
            """{"${'$'}schema":"${CatalogSchemaProfile.DIALECT}","properties":{"name":{"type":"string"}}}"""

        listOf(objectUnion, implicitObject).forEach { candidate ->
            assertThatThrownBy { profile.requireValid(mapper.readTree(candidate)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("additionalProperties")
        }
    }

    @Test
    fun `requires objects introduced inside conditional branches to be closed`() {
        val openNestedObject =
            """{"${'$'}schema":"${CatalogSchemaProfile.DIALECT}","type":"object","additionalProperties":false,""" +
                """"properties":{"kind":{"type":"string"},"details":{"type":"object",""" +
                """"additionalProperties":false}},""" +
                """"if":{"properties":{"kind":{"const":"FIXED"}}},"then":{"properties":{"details":{"type":"object",""" +
                """"properties":{"amount":{"type":"string"}}}}}}"""

        assertThatThrownBy { profile.requireValid(mapper.readTree(openNestedObject)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("additionalProperties")
    }

    private fun schema(property: String): String =
        """{"${'$'}schema":"${CatalogSchemaProfile.DIALECT}","type":"object","additionalProperties":false,""" +
            """"properties":{"name":{$property}}}"""
}
