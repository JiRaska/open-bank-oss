// SPDX-License-Identifier: Apache-2.0
package com.openbank.lending.infrastructure.rest

import com.openbank.lending.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.junit.jupiter.api.Test
import java.util.UUID

@QuarkusTest
@QuarkusTestResource(value = PostgresRedisTestResource::class, restrictToAnnotatedClass = true)
class LendingGraphSourceRouteIT {
    private val route = "/api/v1/lending/graph/loans/${UUID.randomUUID()}/approved-guarantees"

    @Test
    fun `anonymous investigator cannot read guarantee history`() {
        given().get(route).then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "lending-officer", roles = ["ROLE_LENDING_OFFICER"])
    fun `lending officer cannot read guarantee history`() {
        given().get(route).then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "credit-risk", roles = ["ROLE_CREDIT_RISK"])
    fun `authorized role reaches dormant read and receives no data`() {
        given().get(route).then().statusCode(503)
    }
}
