// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.e2e

import com.openbank.aml.it.PostgresRedisTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * The analyst investigation journey over the real HTTP surface: a compliance analyst files a
 * screening case (idempotency-keyed, origin pinned to REST, caller-bound decision fields), walks
 * it through triage to a decision, and probes the boundaries the aggregate enforces — idempotent
 * replay, fingerprint mismatch, terminal-state refusal, and the four-eyes hold-decision lane that
 * is reserved for held payments' cases and refuses a staff case with 409.
 *
 * The module's tests prove one property each; this proves the route sequence serves the whole
 * "case lands, analyst decides it" story, so a wiring regression that deregisters a route (#3371),
 * drops the idempotency binding, or lets a single analyst decide a held payment's case fails here
 * even when every property-level assertion stays green.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestMethodOrder(OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AmlCaseInvestigationJourneyE2E {

    private val partyId = UUID.randomUUID()
    private val idempotencyKey = "e2e-aml-${UUID.randomUUID()}"
    private lateinit var caseId: String

    private fun createCaseBody(customerReference: String) = """{
        "partyId":"$partyId",
        "accountId":null,
        "transactionId":null,
        "customerReference":"$customerReference",
        "screeningType":"TRANSACTION_MONITORING",
        "riskLevel":"MEDIUM",
        "alertCode":"E2E-STRUCTURING",
        "alertDetail":"three just-under-threshold deposits",
        "matchedEntity":"n/a"
    }"""

    @Test
    @Order(1)
    @TestSecurity(user = "analyst-1", roles = ["ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "analyst-1"),
        ],
    )
    fun `1 - a compliance analyst files a screening case that reads back`() {
        val created = given()
            .contentType("application/json")
            .header("Idempotency-Key", idempotencyKey)
            .body(createCaseBody("e2e-case-1"))
            .post("/api/v1/aml/cases")
            .then()
            .statusCode(201)
            .body("partyId", equalTo(partyId.toString()))
            .body("screeningType", equalTo("TRANSACTION_MONITORING"))
            .body("riskLevel", equalTo("MEDIUM"))
            .body("status", equalTo("OPEN"))
            .body("alertCode", equalTo("E2E-STRUCTURING"))
            .body("requiresFourEyesDisposition", equalTo(false))
            .extract()
        caseId = created.path("id")

        given().get("/api/v1/aml/cases/$caseId").then()
            .statusCode(200)
            .body("id", equalTo(caseId))
            .body("idempotencyKey", equalTo(idempotencyKey))
            .body("status", equalTo("OPEN"))

        given().get("/api/v1/aml/cases/${UUID.randomUUID()}").then().statusCode(404)
    }

    @Test
    @Order(2)
    @TestSecurity(user = "analyst-1", roles = ["ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "analyst-1"),
        ],
    )
    fun `2 - the idempotency key replays the same case and refuses a different body`() {
        given()
            .contentType("application/json")
            .header("Idempotency-Key", idempotencyKey)
            .body(createCaseBody("e2e-case-1"))
            .post("/api/v1/aml/cases")
            .then()
            .statusCode(201)
            .header("X-Idempotency-Replayed", equalTo("true"))
            .body("id", equalTo(caseId))

        given()
            .contentType("application/json")
            .header("Idempotency-Key", idempotencyKey)
            .body(createCaseBody("e2e-case-2"))
            .post("/api/v1/aml/cases")
            .then()
            .statusCode(409)

        given()
            .contentType("application/json")
            .body(createCaseBody("e2e-case-3"))
            .post("/api/v1/aml/cases")
            .then()
            .statusCode(400)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "analyst-1", roles = ["ROLE_COMPLIANCE"])
    fun `3 - the case list filters by status screening type and party`() {
        given().queryParam("status", "OPEN").get("/api/v1/aml/cases").then()
            .statusCode(200)
            .body("id", hasItem(caseId))

        given().queryParam("screeningType", "TRANSACTION_MONITORING").get("/api/v1/aml/cases").then()
            .statusCode(200)
            .body("id", hasItem(caseId))

        given().queryParam("partyId", partyId).get("/api/v1/aml/cases").then()
            .statusCode(200)
            .body("id", hasItem(caseId))

        given().queryParam("status", "BLOCKED").get("/api/v1/aml/cases").then()
            .statusCode(200)
    }

    @Test
    @Order(4)
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `4 - triage moves the case under review and the caller is bound as decider`() {
        given()
            .contentType("application/json")
            .body(
                """
                {"targetStatus":"UNDER_REVIEW","decisionReason":"triage",
                "assignedAnalyst":"analyst-1","decidedBy":"spoofed-name"}
                """.trimIndent(),
            )
            .put("/api/v1/aml/cases/$caseId/decision")
            .then()
            .statusCode(200)
            .body("status", equalTo("UNDER_REVIEW"))
            .body("decidedBy", equalTo("operator-1"))
    }

    @Test
    @Order(5)
    @TestSecurity(user = "operator-1", roles = ["ROLE_OPERATOR"])
    fun `5 - a single analyst closes a staff case and a terminal case refuses more decisions`() {
        given()
            .contentType("application/json")
            .body(
                """
                {"targetStatus":"CLEARED","decisionReason":"deposits explained by payroll",
                "assignedAnalyst":"analyst-1"}
                """.trimIndent(),
            )
            .put("/api/v1/aml/cases/$caseId/decision")
            .then()
            .statusCode(200)
            .body("status", equalTo("CLEARED"))
            .body("decisionReason", containsString("payroll"))

        given().get("/api/v1/aml/cases/$caseId").then()
            .statusCode(200)
            .body("status", equalTo("CLEARED"))

        given()
            .contentType("application/json")
            .body(
                """
                {"targetStatus":"ESCALATED","decisionReason":"too late",
                "assignedAnalyst":"analyst-1"}
                """.trimIndent(),
            )
            .put("/api/v1/aml/cases/$caseId/decision")
            .then()
            .statusCode(409)
    }

    @Test
    @Order(6)
    @TestSecurity(user = "analyst-1", roles = ["ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "analyst-1"),
        ],
    )
    fun `6 - the four-eyes hold-decision lane refuses a staff case`() {
        given()
            .contentType("application/json")
            .body("""{"outcome":"CLEAR","reason":"looks fine"}""")
            .post("/api/v1/aml/cases/$caseId/hold-decision/proposal")
            .then()
            .statusCode(409)
            .body(containsString("held payment"))
    }

    @Test
    @Order(7)
    @TestSecurity(user = "analyst-1", roles = ["ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "analyst-1"),
        ],
    )
    fun `7 - reserved key namespaces the service's own jobs write are refused`() {
        given()
            .contentType("application/json")
            .header("Idempotency-Key", "tm:e2e-pack-${UUID.randomUUID()}")
            .body(createCaseBody("e2e-pack-case"))
            .post("/api/v1/aml/cases")
            .then()
            .statusCode(400)
    }

    @Test
    @Order(8)
    @TestSecurity(user = "mystery-sa", roles = ["ROLE_API"])
    fun `8 - an unnamed service account cannot open a case`() {
        given()
            .contentType("application/json")
            .header("Idempotency-Key", "e2e-aml-${UUID.randomUUID()}")
            .body(createCaseBody("e2e-sa-case"))
            .post("/api/v1/aml/cases")
            .then()
            .statusCode(403)
    }

    @Test
    @Order(9)
    @TestSecurity(user = "viewer-1", roles = ["ROLE_VIEWER"])
    fun `9 - a viewer reads cases but cannot file or decide them`() {
        given().get("/api/v1/aml/cases/$caseId").then()
            .statusCode(200)
            .body("id", equalTo(caseId))

        given()
            .contentType("application/json")
            .header("Idempotency-Key", "e2e-aml-${UUID.randomUUID()}")
            .body(createCaseBody("e2e-viewer-case"))
            .post("/api/v1/aml/cases")
            .then()
            .statusCode(403)

        given()
            .contentType("application/json")
            .body(
                """
                {"targetStatus":"ESCALATED","decisionReason":"x",
                "assignedAnalyst":null}
                """.trimIndent(),
            )
            .put("/api/v1/aml/cases/$caseId/decision")
            .then()
            .statusCode(403)
    }
}
