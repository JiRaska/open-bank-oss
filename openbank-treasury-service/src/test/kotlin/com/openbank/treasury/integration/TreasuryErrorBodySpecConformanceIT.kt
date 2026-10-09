// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0.html for details.

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

/** #12078: compare actual validation errors with the schema each operation publishes. */
@QuarkusTest
@QuarkusTestResource(TreasuryDealApiIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class TreasuryErrorBodySpecConformanceIT {

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `quote validation error conforms to its 400 schema`() {
        val body: Map<String, Any?> = given()
            .`when`().get("/api/v1/treasury/quotes?currency=CZK&tenorDays=30")
            .then().statusCode(400).extract().jsonPath().getMap("")
        assertConforms(body, "/api/v1/treasury/quotes", "get")
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `camt upload validation error conforms to its 400 schema`() {
        val body: Map<String, Any?> = given()
            .contentType("application/xml")
            .body("<Document/>")
            .`when`().post("/api/v1/treasury/nostro/statements")
            .then().statusCode(400).extract().jsonPath().getMap("")
        assertConforms(body, "/api/v1/treasury/nostro/statements", "post")
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `MT940 upload validation error conforms to its 400 schema`() {
        val body: Map<String, Any?> = given()
            .contentType("text/plain")
            .body(":20:X\n:25:nope\n")
            .`when`().post("/api/v1/treasury/nostro/statements/mt940")
            .then().statusCode(400).extract().jsonPath().getMap("")
        assertConforms(body, "/api/v1/treasury/nostro/statements/mt940", "post")
    }

    @Test
    @TestSecurity(user = "anna.approver", roles = ["ROLE_TREASURY_APPROVER"])
    fun `treasury-owned not-found response retains the Error schema`() {
        val body: Map<String, Any?> = given()
            .`when`().get("/api/v1/treasury/nostro/statements/${UUID.randomUUID()}/reconciliation")
            .then().statusCode(404).extract().jsonPath().getMap("")
        assertConforms(body, "/api/v1/treasury/nostro/statements/{id}/reconciliation", "get", "404", "Error")
        assertThat(body).containsKey("error")
    }

    @Test
    fun `the schema rejects the old validation error body`() {
        val oldBody = mapOf("error" to "invalid")
        assertThat(violations(oldBody, "ProblemDetail")).isNotEmpty()
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertConforms(
        body: Map<String, Any?>,
        path: String,
        method: String,
        status: String = "400",
        expectedSchema: String = "ProblemDetail",
    ) {
        val spec = javaClass.classLoader.getResourceAsStream("openapi.yaml")!!.use {
            Yaml().load<Map<String, Any?>>(it)
        }
        val paths = spec["paths"] as Map<String, Map<String, Any?>>
        val operation = paths.getValue(path)[method] as Map<String, Any?>
        val responses = operation["responses"] as Map<String, Map<String, Any?>>
        val content = responses.getValue(status)["content"] as Map<String, Map<String, Any?>>
        val ref = ((content.getValue("application/json")["schema"] as Map<String, String>)).getValue("\$ref")
        assertThat(ref).isEqualTo("#/components/schemas/$expectedSchema")
        assertThat(violations(body, expectedSchema)).describedAs("actual $status body: $body").isEmpty()
        if (status == "400") {
            assertThat(body["status"]).isEqualTo(400)
            assertThat(body["code"]).isEqualTo("VALIDATION_ERROR")
            assertThat(body).doesNotContainKey("error")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun violations(body: Map<String, Any?>, schemaName: String): List<String> {
        val spec = javaClass.classLoader.getResourceAsStream("openapi.yaml")!!.use {
            Yaml().load<Map<String, Any?>>(it)
        }
        val components = spec["components"] as Map<String, Any?>
        val schemas = components["schemas"] as Map<String, Map<String, Any?>>
        val schema = schemas.getValue(schemaName)
        val properties = schema["properties"] as Map<String, Map<String, Any?>>
        val failures = mutableListOf<String>()
        (schema["required"] as List<String>).filterNot(body::containsKey)
            .forEach { failures += "$it is required" }
        if (schema["additionalProperties"] == false) {
            (body.keys - properties.keys).forEach { failures += "$it is undeclared" }
        }
        body.mapNotNull { (key, value) -> typeViolation(key, value, properties[key]) }
            .forEach(failures::add)
        return failures
    }

    private fun typeViolation(key: String, value: Any?, property: Map<String, Any?>?): String? =
        when (property?.get("type")) {
            "string" -> if (value is String) null else "$key is not a string"
            "integer" -> if (value is Int || value is Long) null else "$key is not an integer"
            "boolean" -> if (value is Boolean) null else "$key is not a boolean"
            "array" -> if (value is List<*>) null else "$key is not an array"
            else -> null
        }
}
