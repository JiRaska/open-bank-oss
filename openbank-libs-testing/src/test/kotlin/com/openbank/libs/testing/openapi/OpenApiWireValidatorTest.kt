// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.testing.openapi

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiWireValidatorTest {

    @Test
    fun `OpenAPI 3_0 nullable keeps accepting null without accepting another type`() {
        val validator = validator("3.0.3", "{type: string, nullable: true}")
        assertThat(validator.violations("null", PATH)).isEmpty()
        assertThat(validator.violations("\"value\"", PATH)).isEmpty()
        assertThat(validator.violations("42", PATH)).isNotEmpty()
    }

    @Test
    fun `OpenAPI 3_1 nullable annotation does not admit null for a string schema`() {
        val validator = validator("3.1.0", "{type: string, nullable: true}")
        assertThat(validator.violations("null", PATH)).isNotEmpty()
        assertThat(validator.violations("\"value\"", PATH)).isEmpty()
        assertThat(validator.violations("42", PATH)).isNotEmpty()
    }

    @Test
    fun `OpenAPI 3_1 explicit null union accepts null and string but not a number`() {
        val validator = validator("3.1.0", "{type: [string, 'null']}")
        assertThat(validator.violations("null", PATH)).isEmpty()
        assertThat(validator.violations("\"value\"", PATH)).isEmpty()
        assertThat(validator.violations("42", PATH)).isNotEmpty()
    }

    private fun validator(version: String, schema: String): OpenApiWireValidator {
        val spec = """
            openapi: $version
            components: {}
            paths:
              $PATH:
                get:
                  responses:
                    '200':
                      content:
                        application/json:
                          schema: $schema
        """.trimIndent()
        // Inject a fixture through the existing private constructor, without widening the public ABI.
        val constructor = OpenApiWireValidator::class.java.getDeclaredConstructor(String::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(spec)
    }

    companion object {
        private const val PATH = "/response"
    }
}
