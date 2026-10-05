// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.integration

import com.openbank.dispute.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** Real HTTP and DB proof that a local enum write cannot masquerade as a filed scheme case (#8869). */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DisputeChargebackGuardIT.AuthzOffProfile::class)
@TestSecurity(user = "operator", roles = ["ROLE_OPERATOR", "ROLE_ADMIN"])
class DisputeChargebackGuardIT {
    class AuthzOffProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("authz.enforce" to "false")
    }

    @Test
    fun `explicit chargeback returns conflict without changing the stored dispute`() {
        val disputeId = given()
            .contentType("application/json")
            .body(
                """{"transactionId":"${UUID.randomUUID()}","accountId":"${UUID.randomUUID()}",""" +
                    """"partyId":"${UUID.randomUUID()}","disputeType":"UNAUTHORIZED","amount":50.00,""" +
                    """"transactionDate":"${LocalDate.now().minusDays(1)}"}""",
            )
            .post("/api/v1/disputes")
            .then().statusCode(201)
            .extract().jsonPath().getString("id")

        given()
            .contentType("application/json")
            .body("""{"status":"RESOLVED_CUSTOMER","resolution":"CHARGEBACK","chargebackAmount":50.00}""")
            .put("/api/v1/disputes/$disputeId")
            .then().statusCode(409)
            .body("error", containsString("confirmed network case"))

        given().get("/api/v1/disputes/$disputeId")
            .then().statusCode(200)
            .body("status", equalTo("OPEN"), "resolution", equalTo("PENDING"))

        given()
            .contentType("application/json")
            .body("""{"status":"UNDER_REVIEW"}""")
            .put("/api/v1/disputes/$disputeId")
            .then().statusCode(200)
            .body("status", equalTo("UNDER_REVIEW"), "resolution", equalTo("PENDING"))
    }
}
