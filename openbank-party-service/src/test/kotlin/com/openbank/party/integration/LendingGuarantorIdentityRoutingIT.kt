// SPDX-License-Identifier: Apache-2.0
package com.openbank.party.integration

import com.openbank.party.it.PostgresRedpandaTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(PostgresRedpandaTestResource::class)
class LendingGuarantorIdentityRoutingIT {
    private val path = "/api/v1/parties/lending-guarantor-identity/verify"

    @Test
    @TestSecurity(user = "service-account-openbank-lending-graph", roles = ["ROLE_LENDING_GRAPH_PROOF"])
    fun `dedicated caller gets only a boolean for an unknown party`() {
        given().contentType(ContentType.JSON).body(mapOf("partyId" to UUID.randomUUID().toString()))
            .post(path).then().statusCode(200).body("verified", equalTo(false))
    }

    @Test
    @TestSecurity(user = "service-account-openbank-services", roles = ["ROLE_API"])
    fun `shared backend caller cannot query guarantor identity`() {
        given().contentType(ContentType.JSON).body(mapOf("partyId" to UUID.randomUUID().toString()))
            .post(path).then().statusCode(403)
    }
}
