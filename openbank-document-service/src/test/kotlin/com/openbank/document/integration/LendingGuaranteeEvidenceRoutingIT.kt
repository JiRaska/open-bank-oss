// SPDX-License-Identifier: Apache-2.0
package com.openbank.document.integration

import com.openbank.document.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID

/** Real HTTP proof that the new route is registered and the shared M2M identity is denied. */
@QuarkusTest
@QuarkusTestResource(DocumentPartyBrowsePolicyIT.InMemoryKafkaResource::class)
@QuarkusTestResource(PostgresRedisTestResource::class)
class LendingGuaranteeEvidenceRoutingIT {
    private fun body() = mapOf(
        "documentId" to UUID.randomUUID().toString(),
        "loanId" to UUID.randomUUID().toString(),
        "guarantorPartyId" to UUID.randomUUID().toString(),
        "bankScope" to "test-bank-a",
        "sealedSha256" to "a".repeat(64),
    )

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `shared backend token gets forbidden rather than a document answer`() {
        given().contentType(ContentType.JSON).body(body())
            .post("/api/v1/documents/lending-guarantee-evidence/verify")
            .then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-lending-graph", roles = ["ROLE_LENDING_GRAPH_PROOF"])
    fun `dedicated token gets only a boolean for a missing document`() {
        given().contentType(ContentType.JSON).body(body())
            .post("/api/v1/documents/lending-guarantee-evidence/verify")
            .then().statusCode(200).body("matches", equalTo(false))
    }
}
