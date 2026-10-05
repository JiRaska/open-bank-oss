// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.integration

import com.openbank.treasury.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.util.UUID

/** #12078: real HTTP error bodies must conform to the specific response schema in openapi.yaml. */
@QuarkusTest
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class TreasuryErrorBodySpecConformanceIT {
    private companion object {
        @Suppress("UNCHECKED_CAST")
        val SPEC: Map<String, Any?> by lazy {
            Thread.currentThread().contextClassLoader.getResourceAsStream("openapi.yaml")!!
                .use { Yaml().load<Map<String, Any?>>(it) }
        }
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `validation errors from quotes and both uploads conform to ProblemDetail`() {
        val quote = given()
            .`when`().get("/api/v1/treasury/quotes?currency=CZK&tenorDays=30")
            .then().statusCode(400).extract().jsonPath().getMap<String, Any?>("")
        assertResponseMatchesSpec(quote, "/api/v1/treasury/quotes", "get", "400", "ProblemDetail")

        val camt = given().contentType("application/xml").body("<Document/>")
            .`when`().post("/api/v1/treasury/nostro/statements")
            .then().statusCode(400).extract().jsonPath().getMap<String, Any?>("")
        assertResponseMatchesSpec(camt, "/api/v1/treasury/nostro/statements", "post", "400", "ProblemDetail")

        val mt940 = given().contentType("text/plain").body(":20:TEST")
            .`when`().post("/api/v1/treasury/nostro/statements/mt940")
            .then().statusCode(400).extract().jsonPath().getMap<String, Any?>("")
        assertResponseMatchesSpec(mt940, "/api/v1/treasury/nostro/statements/mt940", "post", "400", "ProblemDetail")

        for (body in listOf(quote, camt, mt940)) {
            assertThat(body).doesNotContainKey("error")
            assertThat(body["code"]).isEqualTo("VALIDATION_ERROR")
        }
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `treasury-owned not-found error keeps its documented shape`() {
        val body = given()
            .`when`().get("/api/v1/treasury/nostro/statements/${UUID.randomUUID()}/reconciliation")
            .then().statusCode(404).extract().jsonPath().getMap<String, Any?>("")
        assertResponseMatchesSpec(body, "/api/v1/treasury/nostro/statements/{id}/reconciliation", "get", "404", "Error")
        assertThat(body).containsKey("error")
    }

    @Test
    fun `schema validator rejects the formerly documented validation body`() {
        assertThat(violations(mapOf("error" to "invalid"), "ProblemDetail")).isNotEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertResponseMatchesSpec(
        body: Map<String, Any?>,
        path: String,
        method: String,
        status: String,
        expectedSchema: String,
    ) {
        val paths = SPEC.getValue("paths") as Map<String, Map<String, Any?>>
        val operation = paths.getValue(path).getValue(method) as Map<String, Any?>
        val responses = operation.getValue("responses") as Map<String, Map<String, Any?>>
        val response = responses.getValue(status)
        val content = response.getValue("content") as Map<String, Map<String, Any?>>
        val json = content.getValue("application/json")
        val schema = json.getValue("schema") as Map<String, String>
        assertThat(schema.getValue("\$ref")).isEqualTo("#/components/schemas/$expectedSchema")
        assertThat(violations(body, expectedSchema)).describedAs("actual $status body: $body").isEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun violations(body: Map<String, Any?>, schemaName: String): List<String> {
        val components = SPEC.getValue("components") as Map<String, Any?>
        val schemas = components.getValue("schemas") as Map<String, Map<String, Any?>>
        val schema = schemas.getValue(schemaName)
        val properties = schema.getValue("properties") as Map<String, Map<String, Any?>>
        val failures = mutableListOf<String>()
        (schema["required"] as List<String>).filterNot(body::containsKey)
            .forEach { failures += "$it is required" }
        if (schema["additionalProperties"] == false) {
            (body.keys - properties.keys).forEach { failures += "$it is undeclared" }
        }
        body.forEach { (key, value) ->
            when (properties[key]?.get("type")) {
                "string" -> if (value !is String) failures += "$key is not a string"
                "integer" -> if (value !is Int && value !is Long) failures += "$key is not an integer"
                "boolean" -> if (value !is Boolean) failures += "$key is not a boolean"
                "array" -> if (value !is List<*>) failures += "$key is not an array"
            }
        }
        return failures
    }
}
