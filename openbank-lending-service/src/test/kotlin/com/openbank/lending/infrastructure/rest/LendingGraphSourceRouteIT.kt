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
    private val writerRoute = "/api/v1/lending/graph/loans/${UUID.randomUUID()}/guarantees"

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

    @Test
    fun `anonymous caller cannot propose guarantee evidence`() {
        given().contentType("application/json").body(proposalBody()).post(writerRoute).then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "operator", roles = ["ROLE_OPERATOR"])
    fun `operator cannot propose guarantee evidence`() {
        given().contentType("application/json").body(proposalBody()).post(writerRoute).then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "lending-maker", roles = ["ROLE_LENDING_OFFICER"])
    fun `maker reaches disabled writer without source side effects`() {
        given().contentType("application/json").body(proposalBody()).post(writerRoute).then().statusCode(503)
    }

    @Test
    @TestSecurity(user = "credit-risk", roles = ["ROLE_CREDIT_RISK"])
    fun `risk role cannot propose but can reach disabled checker route`() {
        given().contentType("application/json").body(proposalBody()).post(writerRoute).then().statusCode(403)
        given().contentType("application/json").body("""{"decision":"APPROVED"}""")
            .post("$writerRoute/${UUID.randomUUID()}/decision").then().statusCode(503)
    }

    private fun proposalBody() = """{
        "contractId":"${UUID.randomUUID()}",
        "revision":1,
        "guarantorPartyId":"${UUID.randomUUID()}",
        "capAmount":500.00,
        "currency":"EUR",
        "coverageFraction":0.5,
        "seniority":1,
        "validFrom":"2026-09-25T10:00:00Z",
        "sourceDocumentId":"${UUID.randomUUID()}",
        "sourceSha256":"${"a".repeat(64)}"
    }
    """.trimIndent()
}
