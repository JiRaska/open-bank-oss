// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.product.ir

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The JSON Schema shipped as `product-ir/v1.schema.json` and the Kotlin parser must agree: a
 * document one accepts and the other rejects is a tool that lies to its author. The schema is
 * exercised with the same validator product-catalog's `JsonSchemaCatalogValidator` uses.
 */
class ProductIrSchemaAgreementTest {

    private val mapper = ObjectMapper()
    private val schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(checkNotNull(javaClass.getResourceAsStream("/product-ir/v1.schema.json")))

    private fun schemaAccepts(json: String) = schema.validate(mapper.readTree(json)).isEmpty()

    private fun parserAccepts(json: String) = runCatching { ProductIrParser.parse(json) }.isSuccess

    @Test
    fun `the schema is a trusted-profile document`() {
        val text = javaClass.getResource("/product-ir/v1.schema.json")!!.readText()
        assertThat(text).contains("\"\$schema\": \"https://json-schema.org/draft/2020-12/schema\"")
        assertThat(text).doesNotContain("\"\$ref\": \"http")
        val node = mapper.readTree(text)
        val objects = node.findParents("type").filter { it.get("type")?.asText() == "object" && it.has("properties") }
        assertThat(objects).isNotEmpty.allMatch { it.get("additionalProperties")?.asBoolean() == false }
    }

    @Test
    fun `both accept the fixtures`() {
        for (fixture in listOf(IrFixtures.savingsJson, IrFixtures.termDepositJson)) {
            assertThat(schema.validate(mapper.readTree(fixture))).isEmpty()
            assertThat(parserAccepts(fixture)).isTrue()
        }
    }

    @Test
    fun `both reject the same structural defects`() {
        val base = IrFixtures.savingsJson
        val defects = listOf(
            "unknown key" to
                base.replace("\"productCode\": \"SAV-FIXED\",", "\"productCode\": \"SAV-FIXED\", \"script\": \"x\","),
            "missing ruleId" to base.replace("\"ruleId\": \"fee.maintenance\",", ""),
            "ruleId pattern" to base.replace("\"ruleId\": \"open.welcome\"", "\"ruleId\": \"3\""),
            "unknown enum" to base.replace("\"dayCount\": \"ACT_365_FIXED\"", "\"dayCount\": \"ACT_364\""),
            "irVersion" to base.replace("\"irVersion\": 1", "\"irVersion\": 2"),
            "rate precision" to base.replace("\"rate\": \"0.0250\"", "\"rate\": \"0.02500000001\""),
            "FIXED with tiers" to
                base.replace(
                    "\"rate\": \"0.0250\",",
                    "\"rate\": \"0.0250\", \"tiers\": [{\"from\": \"0\", \"rate\": \"0.01\"}],",
                ),
            "unknown action" to base.replace("\"type\": \"ACCRUE_INTEREST\"", "\"type\": \"RUN_SCRIPT\""),
            "window days" to base.replace("\"days\": 30", "\"days\": 0"),
            "bad variable name" to
                base.replace(
                    "\"variables\": { \"productCode\": \"SAV-FIXED\" }",
                    "\"variables\": { \"Product Code\": \"SAV-FIXED\" }",
                ),
        )
        for ((label, json) in defects) {
            assertThat(schemaAccepts(json)).withFailMessage("schema should reject: $label").isFalse()
            assertThat(parserAccepts(json)).withFailMessage("parser should reject: $label").isFalse()
        }
    }
}
