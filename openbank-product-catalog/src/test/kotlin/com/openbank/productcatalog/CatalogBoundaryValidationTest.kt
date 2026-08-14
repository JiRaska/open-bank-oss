// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.ResourceArg
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(
    value = PostgresTestResource::class,
    initArgs = [ResourceArg(name = "db", value = "openbank_catalog_boundaries")],
)
@TestSecurity(user = "boundary-author", roles = ["ROLE_OPERATOR"])
class CatalogBoundaryValidationTest {
    @Test
    fun `rejects price text beyond the relational boundary before persistence`() {
        val suffix = UUID.randomUUID().toString().take(8).uppercase()
        val specificationId = given().contentType("application/json")
            .body(
                """{"code":"PRICE_BOUNDARY_$suffix","schemaRef":{"id":""" +
                    """"org.openbank.insurance.term-life","version":2}}""",
            )
            .post("/api/v2/specifications").then().statusCode(201).extract().path<String>("id")
        val offeringId = given().contentType("application/json")
            .body("""{"specificationId":"$specificationId","code":"PRICE_BOUNDARY_${suffix}_WEB"}""")
            .post("/api/v2/offerings").then().statusCode(201).extract().path<String>("id")
        val tooLong = "x".repeat(65)

        given().contentType("application/json")
            .body(
                """{"schemaRef":{"id":"org.openbank.insurance.term-life","version":2},""" +
                    """"name":{"en":"Boundary"},"attributes":$INSURANCE_ATTRIBUTES,"prices":[""" +
                    """{"code":"$tooLong","kind":"RATE",""" +
                    """"value":"1","unit":"annual","cadence":"ANNUALLY"}]}""",
            )
            .post("/api/v2/offerings/$offeringId/revisions").then()
            .statusCode(400)
            .body("message", containsString("price code"))
    }

    @Test
    fun `rejects non-canonical decimal text without creating a draft`() {
        val suffix = UUID.randomUUID().toString().take(8).uppercase()
        val specificationId = given().contentType("application/json")
            .body(
                """{"code":"DECIMAL_WIRE_$suffix","schemaRef":{"id":""" +
                    """"org.openbank.insurance.term-life","version":2}}""",
            )
            .post("/api/v2/specifications").then().statusCode(201).extract().path<String>("id")
        val offeringId = given().contentType("application/json")
            .body("""{"specificationId":"$specificationId","code":"DECIMAL_WIRE_${suffix}_WEB"}""")
            .post("/api/v2/offerings").then().statusCode(201).extract().path<String>("id")

        listOf("1e3", "01").forEach { invalidValue ->
            given().contentType("application/json")
                .body(
                    """{"schemaRef":{"id":"org.openbank.insurance.term-life","version":2},""" +
                        """"name":{"en":"Boundary"},"attributes":$INSURANCE_ATTRIBUTES,"prices":[""" +
                        """{"code":"PREMIUM","kind":"RATE",""" +
                        """"value":"$invalidValue","unit":"annual","cadence":"ANNUALLY"}]}""",
                )
                .post("/api/v2/offerings/$offeringId/revisions").then()
                .statusCode(400)
                .body("message", containsString("canonical decimal string"))
        }

        given().get("/api/v2/offerings/$offeringId/revisions").then()
            .statusCode(200)
            .body("$", hasSize<Any>(0))
    }

    private companion object {
        const val INSURANCE_ATTRIBUTES =
            """{"coverage":{"amount":"1","currency":"EUR"},"termYears":1,"premiumModel":"CALCULATED","perils":[{"code":"DEATH","description":"Death"}],"exclusions":[],"limits":[{"kind":"PER_EVENT","amount":"1","currency":"EUR"}],"deductibles":[],"underwritingQuestions":[]}"""
    }
}
