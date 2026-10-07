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

    @Suppress("UNCHECKED_CAST")
    private fun assertConforms(body: Map<String, Any?>, path: String, method: String) {
        val spec = javaClass.classLoader.getResourceAsStream("openapi.yaml")!!.use {
            Yaml().load<Map<String, Any?>>(it)
        }
        val paths = spec["paths"] as Map<String, Map<String, Any?>>
        val operation = paths.getValue(path)[method] as Map<String, Any?>
        val responses = operation["responses"] as Map<String, Map<String, Any?>>
        val content = responses.getValue("400")["content"] as Map<String, Map<String, Any?>>
        val ref = ((content.getValue("application/json")["schema"] as Map<String, String>)).getValue("\$ref")
        assertThat(ref).isEqualTo("#/components/schemas/ProblemDetail")
        val components = spec["components"] as Map<String, Any?>
        val schemas = components["schemas"] as Map<String, Map<String, Any?>>
        val schema = schemas.getValue(ref.substringAfterLast('/'))
        val required = schema["required"] as List<String>
        val properties = schema["properties"] as Map<String, Map<String, Any?>>
        assertThat(body.keys).containsAll(required).isSubsetOf(properties.keys)
        assertThat(schema["additionalProperties"]).isEqualTo(false)
        assertThat(body["status"]).isEqualTo(400)
        assertThat(body["code"]).isEqualTo("VALIDATION_ERROR")
        assertThat(body).doesNotContainKey("error")
    }
}
