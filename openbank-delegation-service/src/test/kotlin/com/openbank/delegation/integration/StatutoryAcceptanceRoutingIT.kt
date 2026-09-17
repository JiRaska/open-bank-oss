// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.integration

import com.openbank.delegation.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.junit.jupiter.api.Test
import java.util.UUID

/** Real HTTP proof that each route is registered and rejects calls outside the edge identity boundary. */
@QuarkusTest
@QuarkusTestResource(StatutoryDelegationOperationSchemaIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class StatutoryAcceptanceRoutingIT {
    private val id = UUID.randomUUID()
    private val root = "/api/v1/delegations/statutory-acceptances"

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    fun `registered routes reject missing company scope before reading evidence`() {
        RestAssured.given().contentType(ContentType.JSON).header("Idempotency-Key", "accept-1")
            .post("$root/for-grant/$id").then().statusCode(400)
        RestAssured.given().get("$root/$id").then().statusCode(400)
        RestAssured.given().get("$root/pages").then().statusCode(400)
        RestAssured.given().get("$root/$id/approval-intent").then().statusCode(400)
        RestAssured.given().get("$root/$id/decisions").then().statusCode(400)
        RestAssured.given().get("$root/$id/progress").then().statusCode(400)
        RestAssured.given().contentType(ContentType.JSON).post("$root/$id/execute").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "test-bank-operator", roles = ["ROLE_OPERATOR"])
    fun `operator cannot substitute a customer company and human header`() {
        RestAssured.given().contentType(ContentType.JSON)
            .header("X-Customer-Party-Id", UUID.randomUUID().toString())
            .header("X-Customer-Actor-Party-Id", UUID.randomUUID().toString())
            .header("Idempotency-Key", "accept-1")
            .post("$root/for-grant/$id").then().statusCode(403)
    }
}
