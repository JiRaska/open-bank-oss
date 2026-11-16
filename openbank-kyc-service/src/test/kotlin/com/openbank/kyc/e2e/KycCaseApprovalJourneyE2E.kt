// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.e2e

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.notNullValue
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * End-to-end journey for a KYC case's whole life: **open it, watch the mandatory INDIVIDUAL
 * checks pass one by one until the case itself advances to UNDER_REVIEW, then approve it with
 * a regulatory-grade reason — and after every transition, read the case back through the API
 * and check the state a compliance officer would actually see.**
 *
 * Driven entirely through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`)
 * against a real Postgres (`PostgresTestResource`, per-class Testcontainers boot). No use case
 * or repository is called directly and nothing is mocked.
 *
 * ### What this journey covers that property tests cannot
 *
 * The module's tests prove one rule of the state machine each; this proves the operator-visible
 * route sequence serves the whole open → check → review → approve/reject story, so a wiring
 * regression that deregisters a route (#3371), drops a check from the mandatory set, or answers
 * the wrong status from a transition endpoint fails here even when every property-level
 * assertion stays green. The read-back after each transition is the observable that catches an
 * aggregate whose response renders the new state but never persists it.
 *
 * ### Service boundaries — what is NOT claimed here
 *
 * The screening checks (PEP / sanctions) are advanced by hand over the `PUT .../checks/{type}`
 * route, exactly as an operator would after reading an external vendor's result — this journey
 * never dials sanctions-service. `authz.enforce=false` mirrors the module's other HTTP suites:
 * there is no OPA sidecar in a test JVM, so the advisory `@Authorize` PEP is off; coarse
 * `@RolesAllowed` RBAC stays on and is asserted.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(KycCaseApprovalJourneyE2E.JourneyProfile::class)
class KycCaseApprovalJourneyE2E {

    /**
     * `authz.enforce=false`: no OPA sidecar in the test JVM, so the advisory `@Authorize` PEP
     * is off (same choice as the module's other HTTP suites). Coarse `@RolesAllowed` RBAC is
     * unaffected and is asserted by the negative tests.
     */
    class JourneyProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "authz.enforce" to "false",
        )
    }

    @Test
    @Order(1)
    @TestSecurity(user = OPENER, roles = ["ROLE_KYC_OPENER"])
    fun `1 - an operator opens a case and it reads back OPEN with the mandatory individual checks`() {
        val opened = given().contentType("application/json")
            .body("""{"partyId": "$partyId"}""")
            .post("/api/v1/kyc/cases")
        opened.then()
            .statusCode(201)
            .body("status", equalTo("OPEN"))
            .body("partyId", equalTo(partyId.toString()))
            .body("riskLevel", equalTo("MEDIUM"))
            .body("subjectType", equalTo("INDIVIDUAL"))
            .body(
                "checks.checkType",
                hasItems("IDENTITY", "ADDRESS", "PEP_SCREENING", "SANCTIONS_SCREENING"),
            )
            .body("checks.findAll { it.status != 'PENDING' }", equalTo(emptyList<Any>()))
        caseId = UUID.fromString(opened.jsonPath().getString("id"))

        // Read-back through the by-id route: the case a compliance officer would see.
        given().get("/api/v1/kyc/cases/$caseId").then()
            .statusCode(200)
            .body("status", equalTo("OPEN"))
            .body("checks.size()", equalTo(4))
    }

    @Test
    @Order(2)
    @TestSecurity(user = OPENER, roles = ["ROLE_KYC_OPENER"])
    fun `2 - a second open for the same party is refused, not duplicated`() {
        given().contentType("application/json")
            .body("""{"partyId": "$partyId"}""")
            .post("/api/v1/kyc/cases").then()
            .statusCode(409)

        // ...and the party still resolves to exactly the one case opened in test 1.
        given().get("/api/v1/kyc/cases/party/$partyId").then()
            .statusCode(200)
            .body("id", equalTo(caseId.toString()))
    }

    @Test
    @Order(3)
    @TestSecurity(user = OPENER, roles = ["ROLE_KYC_OPENER"])
    fun `3 - the case stays OPEN until every mandatory check has passed`() {
        passCheck("IDENTITY", "passport verified")
        given().get("/api/v1/kyc/cases/$caseId").then()
            .statusCode(200)
            .body("status", equalTo("OPEN"))
    }

    @Test
    @Order(4)
    @TestSecurity(user = OPENER, roles = ["ROLE_KYC_OPENER"])
    fun `4 - the last passing check advances the case to UNDER_REVIEW and it reads back`() {
        passCheck("ADDRESS", "utility bill on file")
        passCheck("PEP_SCREENING", "no politically exposed person match")
        passCheck("SANCTIONS_SCREENING", "clear against consolidated lists")
        given().get("/api/v1/kyc/cases/$caseId").then()
            .statusCode(200)
            .body("status", equalTo("UNDER_REVIEW"))
            .body("checks.findAll { it.status != 'PASSED' }", equalTo(emptyList<Any>()))
    }

    @Test
    @Order(5)
    @TestSecurity(user = REVIEWER, roles = ["ROLE_KYC_REVIEWER"])
    fun `5 - approving from UNDER_REVIEW records the approver identity and the reason`() {
        given().contentType("application/json")
            .body("""{"reason": "all mandatory checks passed, onboarding approved"}""")
            .post("/api/v1/kyc/cases/$caseId/approve").then()
            .statusCode(200)
            .body("status", equalTo("APPROVED"))
            .body("reviewedBy", equalTo(REVIEWER))
            .body("reviewedAt", notNullValue())
            .body("notes", equalTo("all mandatory checks passed, onboarding approved"))

        // Read-back: an approval that is not persisted is not an approval — the ČNB audit
        // trail obligation (§8) is about what a later review can actually retrieve.
        given().get("/api/v1/kyc/cases/$caseId").then()
            .statusCode(200)
            .body("status", equalTo("APPROVED"))
            .body("reviewedBy", equalTo(REVIEWER))
    }

    @Test
    @Order(6)
    @TestSecurity(user = ADMIN, roles = ["ROLE_ADMIN"])
    fun `6 - a second case can be rejected from UNDER_REVIEW with the reason kept`() {
        val secondPartyId = UUID.randomUUID()
        val opened = given().contentType("application/json")
            .body("""{"partyId": "$secondPartyId"}""")
            .post("/api/v1/kyc/cases")
        opened.then().statusCode(201)
        val secondCaseId = opened.jsonPath().getString("id")

        // Approving an OPEN case must fail closed with the state-transition error, not 500.
        given().contentType("application/json")
            .body("""{"reason": "trying to approve too early"}""")
            .post("/api/v1/kyc/cases/$secondCaseId/approve").then()
            .statusCode(422)
            .body("code", equalTo("INVALID_STATE_TRANSITION"))

        listOf("IDENTITY", "ADDRESS", "PEP_SCREENING", "SANCTIONS_SCREENING").forEach { checkType ->
            passCheck(secondCaseId, checkType, "completed during review")
        }
        given().contentType("application/json")
            .body("""{"reason": "adverse media hit cannot be resolved"}""")
            .post("/api/v1/kyc/cases/$secondCaseId/reject").then()
            .statusCode(200)
            .body("status", equalTo("REJECTED"))
            .body("reviewedBy", equalTo(ADMIN))

        given().get("/api/v1/kyc/cases/$secondCaseId").then()
            .statusCode(200)
            .body("status", equalTo("REJECTED"))
            .body("notes", equalTo("adverse media hit cannot be resolved"))
    }

    @Test
    @Order(7)
    @TestSecurity(user = ADMIN, roles = ["ROLE_ADMIN"])
    fun `7 - the surface fails closed on a short reason, an unknown case and a bogus status filter`() {
        // A reason under the ČNB 10-character minimum is rejected before any DB interaction.
        val secondPartyId = UUID.randomUUID()
        val opened = given().contentType("application/json")
            .body("""{"partyId": "$secondPartyId"}""")
            .post("/api/v1/kyc/cases")
        opened.then().statusCode(201)
        val thirdCaseId = opened.jsonPath().getString("id")

        given().contentType("application/json")
            .body("""{"reason": "short"}""")
            .post("/api/v1/kyc/cases/$thirdCaseId/approve").then()
            .statusCode(422)
            .body("code", equalTo("INVALID_APPROVAL_REASON"))

        given().get("/api/v1/kyc/cases/${UUID.randomUUID()}").then()
            .statusCode(404)

        // #9038: an unparseable status filter must 400, not silently drop the condition.
        given().queryParam("status", "NOT_A_STATUS").get("/api/v1/kyc/cases").then()
            .statusCode(400)
    }

    @Test
    @Order(8)
    @TestSecurity(user = VIEWER, roles = ["ROLE_VIEWER"])
    fun `8 - a viewer can list cases but cannot open one, and the status filter finds the verdict`() {
        given().contentType("application/json")
            .body("""{"partyId": "${UUID.randomUUID()}"}""")
            .post("/api/v1/kyc/cases").then()
            .statusCode(403)

        given().queryParam("status", "APPROVED").get("/api/v1/kyc/cases").then()
            .statusCode(200)
            .body("statusFilter", equalTo("APPROVED"))
            .body("items.find { it.id == '$caseId' }", notNullValue())
    }

    private fun passCheck(checkType: String, result: String) = passCheck(caseId.toString(), checkType, result)

    private fun passCheck(caseId: String, checkType: String, result: String) {
        given().contentType("application/json")
            .body("""{"status": "PASSED", "result": "$result"}""")
            .put("/api/v1/kyc/cases/$caseId/checks/$checkType").then()
            .statusCode(200)
    }

    private companion object {
        const val OPENER = "kyc-e2e-opener"
        const val REVIEWER = "kyc-e2e-reviewer"
        const val VIEWER = "kyc-e2e-viewer"
        const val ADMIN = "kyc-e2e-admin"
    }

    private val partyId: UUID = UUID.randomUUID()
    private lateinit var caseId: UUID
}
