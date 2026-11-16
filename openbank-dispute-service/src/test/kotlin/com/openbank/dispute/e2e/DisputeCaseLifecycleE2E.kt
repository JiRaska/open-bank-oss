// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.e2e

import com.openbank.libs.testing.containers.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.notNullValue
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.math.BigDecimal
import java.util.UUID

/**
 * End-to-end journey for a dispute case's whole compliance life: **open it against a card
 * transaction, move it under review, append tamper-evident evidence and walk the hash chain,
 * resolve it UPHELD with the remediation recorded; and on the side, watch an escalated case refuse
 * resolution (409), a withdrawn case close its own loop, an invalid PARTIAL amount fail with 422,
 * and an auditor read but never write.**
 *
 * Driven entirely through the real HTTP surface (`@QuarkusTest` + RestAssured + `@TestSecurity`)
 * against a real Postgres (`PostgresTestResource`, per-class Testcontainers). No use case or
 * repository is called directly and nothing is mocked — dispute-service owns its aggregates and
 * emits its outbox events, so the whole case file is this service's own durable state.
 *
 * ### Why the read-backs matter
 *
 * Every mutation answers the state it just computed, which is the same code path that computed
 * it. Reading the dispute back with `GET /{id}`, re-finding it through `reference/` and
 * `account/`, and walking the `timeline` after each transition is what proves the case file —
 * not just the response — carries the new state, and the evidence-chain walk is what proves the
 * tamper-evident log actually chains the items this journey appended.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation::class)
@QuarkusTestResource(PostgresTestResource::class)
@TestProfile(DisputeCaseLifecycleE2E.JourneyProfile::class)
class DisputeCaseLifecycleE2E {

    /** RBAC is asserted through `@RolesAllowed`; the advisory OPA PEP has no sidecar in tests. */
    class JourneyProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf(
            "authz.enforce" to "false",
        )
    }

    @Test
    @Order(1)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `1 - an operator opens a dispute and it reads back through every lookup`() {
        val opened = given()
            .contentType("application/json")
            .body(openDisputeBody(DISPUTE_ONE_TX, DISPUTE_ONE_ACCOUNT, amount = "2500.00"))
            .post("/api/v1/disputes")
        opened.then()
            .statusCode(201)
            .body("id", notNullValue())
            .body("reference", startsWith("DSP-"))
            .body("status", equalTo("OPEN"))
            .body("resolution", equalTo("PENDING"))
            .body("disputeType", equalTo("UNAUTHORIZED"))
            .body("amount", equalTo(2500.00f))
            .body("currency", equalTo("CZK"))
        disputeOneId = opened.jsonPath().getUUID("id")
        disputeOneRef = opened.jsonPath().getString("reference")

        // By id, by reference, by account, and through the OPEN filter — the case file must be
        // findable whichever door the operator walks in through.
        given().get("/api/v1/disputes/$disputeOneId").then()
            .statusCode(200)
            .body("id", equalTo(disputeOneId.toString()))
            .body("reference", equalTo(disputeOneRef))
        given().get("/api/v1/disputes/reference/$disputeOneRef").then()
            .statusCode(200)
            .body("id", equalTo(disputeOneId.toString()))
        given().get("/api/v1/disputes/account/$DISPUTE_ONE_ACCOUNT").then()
            .statusCode(200)
            .body("id", hasItem(disputeOneId.toString()))
        given().queryParam("status", "OPEN").get("/api/v1/disputes").then()
            .statusCode(200)
            .body("id", hasItem(disputeOneId.toString()))
    }

    @Test
    @Order(2)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `2 - the case moves under review`() {
        given()
            .contentType("application/json")
            .body("""{"status":"UNDER_REVIEW","resolvedBy":"$OPERATOR"}""")
            .put("/api/v1/disputes/$disputeOneId")
            .then()
            .statusCode(200)
            .body("status", equalTo("UNDER_REVIEW"))

        given().get("/api/v1/disputes/$disputeOneId").then()
            .statusCode(200)
            .body("status", equalTo("UNDER_REVIEW"))
        given().queryParam("status", "UNDER_REVIEW").get("/api/v1/disputes").then()
            .statusCode(200)
            .body("id", hasItem(disputeOneId.toString()))
    }

    @Test
    @Order(3)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `3 - evidence appends to a tamper-evident chain that verifies`() {
        addEvidence(disputeOneId, "RECEIPT", "Signed merchant receipt")
        addEvidence(disputeOneId, "CHAT_LOG", "Support chat transcript")

        given().get("/api/v1/disputes/$disputeOneId/evidence").then()
            .statusCode(200)
            .body("", hasSize<Any>(2))
            .body("evidenceType", hasItem("RECEIPT"))
            .body("evidenceType", hasItem("CHAT_LOG"))
            .body("sequence", hasItem(0))
            .body("sequence", hasItem(1))

        // The chain walk is what proves the two items were linked (prevHash/recordHash), not
        // merely stored side by side.
        given().get("/api/v1/disputes/$disputeOneId/evidence/verify").then()
            .statusCode(200)
            .body("intact", equalTo(true))
            .body("itemsChecked", equalTo(2))
            .body("firstBrokenEvidenceId", equalTo(null))
    }

    @Test
    @Order(4)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `4 - an upheld resolution closes the case for the customer`() {
        given()
            .contentType("application/json")
            .body(
                """{"outcome":"UPHELD","resolvedBy":"$OPERATOR","notes":"Evidence supports the customer"}""",
            )
            .post("/api/v1/disputes/$disputeOneId/resolve")
            .then()
            .statusCode(200)
            .body("status", equalTo("RESOLVED_CUSTOMER"))
            .body("resolution", equalTo("PENDING"))
            .body("remediationOutcome", equalTo("UPHELD"))
            .body("resolvedBy", equalTo(OPERATOR))

        // UPHELD remediates the full claimed amount.
        val remediation = given().get("/api/v1/disputes/$disputeOneId").jsonPath()
            .getString("remediationAmount")
        org.assertj.core.api.Assertions
            .assertThat(BigDecimal(remediation).compareTo(BigDecimal("2500.00")))
            .isZero()

        // The timeline carries the whole case history: open, review, both evidence items, resolve.
        given().get("/api/v1/disputes/$disputeOneId/timeline").then()
            .statusCode(200)
            .body("eventType", hasItem("RESOLVED"))
            .body("eventType", hasItem("EVIDENCE_ADDED"))
            .body("eventType", hasItem("STATUS_CHANGED"))
    }

    @Test
    @Order(5)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `5 - a second case escalates with an attributed actor`() {
        val opened = given()
            .contentType("application/json")
            .body(openDisputeBody(DISPUTE_TWO_TX, DISPUTE_TWO_ACCOUNT, amount = "900.00"))
            .post("/api/v1/disputes")
        opened.then().statusCode(201)
        disputeTwoId = opened.jsonPath().getUUID("id")

        // The actor attribution is genuinely required — a bare escalate is a malformed request.
        given().contentType("application/json").post("/api/v1/disputes/$disputeTwoId/escalate").then()
            .statusCode(400)

        given().contentType("application/json").queryParam("actor", OPERATOR)
            .post("/api/v1/disputes/$disputeTwoId/escalate").then()
            .statusCode(200)
            .body("status", equalTo("ESCALATED"))

        given().get("/api/v1/disputes/$disputeTwoId/timeline").then()
            .statusCode(200)
            .body(
                "find { it.eventType == 'STATUS_CHANGED' }.actor",
                equalTo(OPERATOR),
            )
    }

    @Test
    @Order(6)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `6 - an escalated case cannot be resolved through remediation`() {
        // ESCALATED is a terminal state for the remediation path — only OPEN/UNDER_REVIEW/
        // PENDING_* cases resolve. The conflict is surfaced as 409, not a silent no-op.
        given()
            .contentType("application/json")
            .body("""{"outcome":"REJECTED","resolvedBy":"$OPERATOR"}""")
            .post("/api/v1/disputes/$disputeTwoId/resolve")
            .then()
            .statusCode(409)

        given().get("/api/v1/disputes/$disputeTwoId").then()
            .statusCode(200)
            .body("status", equalTo("ESCALATED"))
    }

    @Test
    @Order(7)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `7 - a third case is withdrawn by its actor`() {
        val opened = given()
            .contentType("application/json")
            .body(openDisputeBody(DISPUTE_THREE_TX, DISPUTE_THREE_ACCOUNT, amount = "150.00"))
            .post("/api/v1/disputes")
        opened.then().statusCode(201)
        val id = opened.jsonPath().getUUID("id")

        given().contentType("application/json").queryParam("actor", OPERATOR)
            .post("/api/v1/disputes/$id/withdraw").then()
            .statusCode(200)
            .body("status", equalTo("WITHDRAWN"))
            .body("resolution", equalTo("WITHDRAWN"))
        given().get("/api/v1/disputes/$id").then()
            .statusCode(200)
            .body("status", equalTo("WITHDRAWN"))
    }

    @Test
    @Order(8)
    @TestSecurity(user = OPERATOR, roles = ["ROLE_OPERATOR"])
    fun `8 - a PARTIAL remediation outside the claimed amount is a client-rule violation`() {
        val opened = given()
            .contentType("application/json")
            .body(openDisputeBody(DISPUTE_FOUR_TX, DISPUTE_FOUR_ACCOUNT, amount = "400.00"))
            .post("/api/v1/disputes")
        opened.then().statusCode(201)
        val id = opened.jsonPath().getUUID("id")

        // PARTIAL requires remediationAmount strictly between zero and the claimed amount;
        // 10 000.00 against a 400.00 claim is rejected with 422 and the case stays OPEN.
        given()
            .contentType("application/json")
            .body("""{"outcome":"PARTIAL","remediationAmount":10000.00,"resolvedBy":"$OPERATOR"}""")
            .post("/api/v1/disputes/$id/resolve")
            .then()
            .statusCode(422)
        given().get("/api/v1/disputes/$id").then()
            .statusCode(200)
            .body("status", equalTo("OPEN"))
    }

    @Test
    @Order(9)
    @TestSecurity(user = AUDITOR, roles = ["ROLE_AUDITOR"])
    fun `9 - an auditor reads the case files but never writes`() {
        // Reads: list, by id, timeline — the auditor compliance view.
        given().get("/api/v1/disputes").then().statusCode(200)
        given().get("/api/v1/disputes/$disputeOneId").then()
            .statusCode(200)
            .body("id", equalTo(disputeOneId.toString()))
        given().get("/api/v1/disputes/$disputeOneId/timeline").then().statusCode(200)

        // Writes are refused.
        given()
            .contentType("application/json")
            .body(openDisputeBody(DISPUTE_FIVE_TX, DISPUTE_FIVE_ACCOUNT, amount = "10.00"))
            .post("/api/v1/disputes")
            .then()
            .statusCode(403)
        given().contentType("application/json").queryParam("actor", AUDITOR)
            .post("/api/v1/disputes/$disputeTwoId/escalate").then()
            .statusCode(403)

        // Unknown ids fail closed rather than answering an empty envelope.
        given().get("/api/v1/disputes/${UUID.randomUUID()}").then().statusCode(404)
    }

    private fun addEvidence(disputeId: UUID, type: String, description: String) {
        given()
            .contentType("application/json")
            .body(
                """{"id":"${UUID.randomUUID()}","disputeId":"$disputeId","submittedBy":"$OPERATOR",""" +
                    """"evidenceType":"$type","description":"$description",""" +
                    """"fileReference":"e2e://$type","sequence":0}""",
            )
            .post("/api/v1/disputes/$disputeId/evidence")
            .then()
            .statusCode(201)
    }

    private fun openDisputeBody(tx: UUID, account: UUID, amount: String): String =
        """
        {
          "transactionId": "$tx",
          "accountId": "$account",
          "partyId": "$PARTY",
          "disputeType": "UNAUTHORIZED",
          "amount": $amount,
          "currency": "CZK",
          "description": "E2E unauthorized card transaction",
          "merchantName": "E2E Merchant",
          "transactionDate": "2026-11-01"
        }
        """.trimIndent()

    companion object {
        const val OPERATOR = "e2e-dispute-operator"
        const val AUDITOR = "e2e-dispute-auditor"
        val PARTY: UUID = UUID.fromString("11111111-2222-4333-8444-5555555555d1")
        val DISPUTE_ONE_TX: UUID = UUID.fromString("11111111-2222-4333-8444-5555555551a1")
        val DISPUTE_ONE_ACCOUNT: UUID = UUID.fromString("11111111-2222-4333-8444-5555555551b1")
        val DISPUTE_TWO_TX: UUID = UUID.fromString("11111111-2222-4333-8444-5555555552a1")
        val DISPUTE_TWO_ACCOUNT: UUID = UUID.fromString("11111111-2222-4333-8444-5555555552b1")
        val DISPUTE_THREE_TX: UUID = UUID.fromString("11111111-2222-4333-8444-5555555553a1")
        val DISPUTE_THREE_ACCOUNT: UUID = UUID.fromString("11111111-2222-4333-8444-5555555553b1")
        val DISPUTE_FOUR_TX: UUID = UUID.fromString("11111111-2222-4333-8444-5555555554a1")
        val DISPUTE_FOUR_ACCOUNT: UUID = UUID.fromString("11111111-2222-4333-8444-5555555554b1")
        val DISPUTE_FIVE_TX: UUID = UUID.fromString("11111111-2222-4333-8444-5555555555a1")
        val DISPUTE_FIVE_ACCOUNT: UUID = UUID.fromString("11111111-2222-4333-8444-5555555555b1")

        lateinit var disputeOneId: UUID
        lateinit var disputeOneRef: String
        lateinit var disputeTwoId: UUID
    }
}
