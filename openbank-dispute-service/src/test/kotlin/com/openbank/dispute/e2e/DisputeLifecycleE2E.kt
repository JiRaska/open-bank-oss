// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.dispute.e2e

import com.openbank.dispute.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * End-to-end journeys of the dispute spine over the real HTTP plane against real Postgres. The
 * outbox conformance, the trace contract and the missing-parameter guards are pinned by dedicated
 * ITs; this suite pins what only the wired surface shows:
 *
 *  - **Opening a dispute is a commitment**: it gets a generated `DSP-` reference, a resolution
 *    deadline 45 days out, and an `OPENED` timeline event attributed to the CUSTOMER — the fact
 *    ADR-0220 needs to stop promoting to a disputing customer.
 *  - **Resolution is one-way and outcome-specific** (ADR-0117): a remediation verdict is only
 *    reachable from the evidence-gathering states — an ESCALATED dispute is refused 409; PARTIAL
 *    requires an amount strictly inside (0, claim) else 422; UPHELD compensates the full claim
 *    into `RESOLVED_CUSTOMER`, REJECTED lands `RESOLVED_MERCHANT` with no remediation amount.
 *  - **Withdraw and escalate attribute an actor**: a missing `actor` query parameter is a 400,
 *    never a 500 (#3104), and the actor is stamped on the dispute.
 *  - **Evidence is a tamper-evident hash chain** (ADR-0117/ADR-0133): appended items carry a
 *    monotonic sequence, a prevHash and a recordHash, and the verify endpoint walks the chain
 *    intact.
 *  - **Guards stay client errors**: an unknown dispute is 404 on GET, 400 on the generic update
 *    and 422 on resolve (each endpoint's own contract), an unknown status filter is 400, an
 *    unauthenticated caller is 401, and ROLE_VIEWER reads but never mutates.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class, restrictToAnnotatedClass = true)
@TestProfile(DisputeLifecycleE2E.AuthzAdvisoryProfile::class)
class DisputeLifecycleE2E {

    /**
     * `authz.enforce` defaults to true and there is no OPA sidecar under test, so the PDP fails
     * closed and every `@Authorize` surface answers 503. Advisory keeps the RolesAllowed plane
     * (the subject of this suite) the only gate, matching ComplaintRevisionIT's AuthzOffProfile.
     */
    class AuthzAdvisoryProfile : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("authz.enforce" to "false")
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `a dispute opens with a generated reference a deadline and an opened timeline event`() {
        val opened = openDispute(account = UUID.randomUUID(), amount = "1250.00")
        assertThat(opened["status"]).isEqualTo("OPEN")
        assertThat(opened["resolution"]).isEqualTo("PENDING")
        assertThat(opened["reference"] as String).startsWith("DSP-")

        // The resolution SLA is stamped at filing: deadline == filingDate + 45 days.
        val filingDate = LocalDate.parse(opened["filingDate"] as String)
        assertThat(LocalDate.parse(opened["resolutionDeadline"] as String))
            .isEqualTo(filingDate.plusDays(45))

        val id = opened["id"] as String

        // Read-back by id and by reference agree.
        given().`when`().get("$BASE_PATH/$id")
            .then().statusCode(200)
            .body("id", equalTo(id))
            .body("reference", equalTo(opened["reference"]))
        given().`when`().get("$BASE_PATH/reference/${opened["reference"]}")
            .then().statusCode(200)
            .body("id", equalTo(id))

        // The OPENED timeline event is the ADR-0220 exclusion fact, attributed to the customer.
        given().`when`().get("$BASE_PATH/$id/timeline")
            .then().statusCode(200)
            .body("eventType", hasItem("OPENED"))
            .body("actor", hasItem("CUSTOMER"))
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `the investigation updates status and stamps resolved-at when it reaches a terminal state`() {
        val id = openDispute(account = UUID.randomUUID(), amount = "250.00")["id"] as String

        given().contentType("application/json")
            .body("""{"status":"UNDER_REVIEW","resolvedBy":"analyst-3"}""")
            .`when`().put("$BASE_PATH/$id")
            .then().statusCode(200)
            .body("status", equalTo("UNDER_REVIEW"))
            .body("resolvedAt", nullValue())

        // Entering a terminal status through the generic update stamps the resolution instant
        // (#8745 finding 2: the same terminal state also emits dispute.resolved out of the box).
        val terminal = given().contentType("application/json")
            .body("""{"status":"RESOLVED_MERCHANT","resolution":"REPRESENTMENT","resolvedBy":"analyst-3"}""")
            .`when`().put("$BASE_PATH/$id")
            .then().statusCode(200)
            .body("status", equalTo("RESOLVED_MERCHANT"))
            .body("resolution", equalTo("REPRESENTMENT"))
            .body("resolvedAt", not(nullValue()))
            .body("resolvedBy", equalTo("analyst-3"))
            .extract().body().jsonPath().getMap<String, Any?>("")
        assertThat(terminal["resolvedAt"]).isNotNull()

        given().`when`().get("$BASE_PATH/$id/timeline")
            .then().statusCode(200)
            .body("eventType", hasItem("STATUS_CHANGED"))
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `withdraw and escalate require an actor and an escalated dispute is not resolvable`() {
        // Missing actor is a malformed request: 400, never a 500 (#3104/#3624).
        val withdrawTarget = openDispute(account = UUID.randomUUID(), amount = "99.00")["id"] as String
        given().contentType(
            "application/json",
        ).`when`().post("$BASE_PATH/$withdrawTarget/withdraw").then().statusCode(400)
        given().contentType("application/json").`when`().post("$BASE_PATH/$withdrawTarget/withdraw?actor=customer-9")
            .then().statusCode(200)
            .body("status", equalTo("WITHDRAWN"))
            .body("resolution", equalTo("WITHDRAWN"))
            .body("resolvedBy", equalTo("customer-9"))
            .body("resolvedAt", not(nullValue()))

        val escalateTarget = openDispute(account = UUID.randomUUID(), amount = "410.00")["id"] as String
        given().contentType(
            "application/json",
        ).`when`().post("$BASE_PATH/$escalateTarget/escalate").then().statusCode(400)
        given().contentType("application/json").`when`().post("$BASE_PATH/$escalateTarget/escalate?actor=scheme-team")
            .then().statusCode(200)
            .body("status", equalTo("ESCALATED"))

        // ESCALATED is out of the evidence-gathering set: the remediation verdict refuses 409.
        given().contentType("application/json")
            .body("""{"outcome":"UPHELD","resolvedBy":"analyst-1"}""")
            .`when`().post("$BASE_PATH/$escalateTarget/resolve")
            .then().statusCode(409)
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `the remediation verdict resolves with outcome-specific stamps and validation`() {
        // UPHELD: the full claim is compensated and the customer-side resolution is recorded.
        val upheld = openDispute(account = UUID.randomUUID(), amount = "750.00")
        given().contentType("application/json")
            .body("""{"outcome":"UPHELD","resolvedBy":"analyst-1","notes":"clear fraud"}""")
            .`when`().post("$BASE_PATH/${upheld["id"]}/resolve")
            .then().statusCode(200)
            .body("status", equalTo("RESOLVED_CUSTOMER"))
            .body("remediationOutcome", equalTo("UPHELD"))
            .body("remediationAmount", equalTo(upheld["amount"]))
            .body("resolvedBy", equalTo("analyst-1"))
            .body("resolvedAt", not(nullValue()))

        // PARTIAL: the amount must lie strictly inside (0, claim).
        val partial = openDispute(account = UUID.randomUUID(), amount = "500.00")["id"] as String
        given().contentType("application/json")
            .body("""{"outcome":"PARTIAL","resolvedBy":"analyst-1"}""")
            .`when`().post("$BASE_PATH/$partial/resolve")
            .then().statusCode(422)
        given().contentType("application/json")
            .body("""{"outcome":"PARTIAL","remediationAmount":500.00,"resolvedBy":"analyst-1"}""")
            .`when`().post("$BASE_PATH/$partial/resolve")
            .then().statusCode(422)
        given().contentType("application/json")
            .body("""{"outcome":"PARTIAL","remediationAmount":200.00,"resolvedBy":"analyst-1"}""")
            .`when`().post("$BASE_PATH/$partial/resolve")
            .then().statusCode(200)
            .body("status", equalTo("RESOLVED_CUSTOMER"))
            .body("remediationOutcome", equalTo("PARTIAL"))
            .body("remediationAmount", equalTo(200.0f))

        // REJECTED: no compensation, merchant-side resolution.
        val rejected = openDispute(account = UUID.randomUUID(), amount = "80.00")["id"] as String
        given().contentType("application/json")
            .body("""{"outcome":"REJECTED","resolvedBy":"analyst-1","notes":"claim unsupported"}""")
            .`when`().post("$BASE_PATH/$rejected/resolve")
            .then().statusCode(200)
            .body("status", equalTo("RESOLVED_MERCHANT"))
            .body("remediationOutcome", equalTo("REJECTED"))
            .body("remediationAmount", nullValue())

        // A resolved dispute is terminal on this path too.
        given().contentType("application/json")
            .body("""{"outcome":"UPHELD","resolvedBy":"analyst-1"}""")
            .`when`().post("$BASE_PATH/$rejected/resolve")
            .then().statusCode(409)
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `evidence appends to a tamper-evident hash chain that verifies intact`() {
        val id = openDispute(account = UUID.randomUUID(), amount = "300.00")["id"] as String

        val first = addEvidence(id, "receipt.pdf", "CUSTOMER_RECEIPT", "signed delivery receipt")
            .then().statusCode(201)
            .body("sequence", equalTo(0))
            .body("prevHash", not(nullValue()))
            .body("recordHash", not(nullValue()))
            .extract().body().jsonPath().getMap<String, Any?>("")
        val second = addEvidence(id, "gps-log.csv", "LOCATION_DATA", "courier route")
            .then().statusCode(201)
            .body("sequence", equalTo(1))
            .body("recordHash", not(nullValue()))
            .extract().body().jsonPath().getMap<String, Any?>("")
        assertThat(second["prevHash"]).isEqualTo(first["recordHash"])

        // The plain evidence read-back carries both items (no order contract); the verify
        // endpoint is the ordering authority — it walks the chain from the genesis hash.
        given().`when`().get("$BASE_PATH/$id/evidence")
            .then().statusCode(200)
            .body("size()", equalTo(2))
            .body("sequence", hasItem(0))
            .body("sequence", hasItem(1))
        given().`when`().get("$BASE_PATH/$id/evidence/verify")
            .then().statusCode(200)
            .body("intact", equalTo(true))
            .body("itemsChecked", equalTo(2))
            .body("firstBrokenEvidenceId", nullValue())
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `the list surfaces filter by status and by account`() {
        val accountA = UUID.randomUUID()
        val accountB = UUID.randomUUID()
        val a1 = openDispute(account = accountA, amount = "10.00")["id"] as String
        val a2 = openDispute(account = accountA, amount = "20.00")["id"] as String
        val b1 = openDispute(account = accountB, amount = "30.00")["id"] as String

        // Default listing is the whole OPEN queue, which holds every dispute opened so far —
        // including a sibling account's. Account scoping below is what isolates.
        given().`when`().get(BASE_PATH)
            .then().statusCode(200)
            .body("id", hasItem(a1))
            .body("id", hasItem(a2))
            .body("id", hasItem(b1))

        // Status filter follows the lifecycle.
        given().contentType("application/json").`when`().post("$BASE_PATH/$a2/escalate?actor=ops")
            .then().statusCode(200)
        given().`when`().get("$BASE_PATH?status=ESCALATED")
            .then().statusCode(200)
            .body("id", hasItem(a2))
            .body("id", not(hasItem(a1)))

        // Account scoping returns only that account's disputes.
        given().`when`().get("$BASE_PATH/account/$accountA")
            .then().statusCode(200)
            .body("id", hasItem(a1))
            .body("id", hasItem(a2))
            .body("id", not(hasItem(b1)))

        // An unknown status is a client error at the valueOf boundary.
        given().`when`().get("$BASE_PATH?status=NO_SUCH_STATUS").then().statusCode(400)
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    fun `unknown disputes and malformed submissions stay client errors`() {
        val unknown = UUID.randomUUID()
        given().`when`().get("$BASE_PATH/$unknown").then().statusCode(404)
        given().`when`().get("$BASE_PATH/reference/NO-SUCH-REF").then().statusCode(404)

        // Each mutation surface has its own not-found contract: the generic update answers 400
        // (IllegalArgumentException), the remediation verdict answers 422.
        given().contentType("application/json")
            .body("""{"status":"UNDER_REVIEW"}""")
            .`when`().put("$BASE_PATH/$unknown")
            .then().statusCode(400)
        given().contentType("application/json")
            .body("""{"outcome":"UPHELD","resolvedBy":"analyst-1"}""")
            .`when`().post("$BASE_PATH/$unknown/resolve")
            .then().statusCode(422)

        // An unknown disputeType fails at the boundary with 400, not a 500.
        given().contentType("application/json")
            .body(
                openBody(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "NO_SUCH_TYPE",
                    "50.00",
                ),
            )
            .`when`().post(BASE_PATH)
            .then().statusCode(400)
    }

    @Test
    fun `an unauthenticated caller is refused 401 on every dispute surface`() {
        val id = UUID.randomUUID()
        given().`when`().get(BASE_PATH).then().statusCode(401)
        given().`when`().get("$BASE_PATH/$id").then().statusCode(401)
        given().contentType("application/json").body(openBody(id, id, id, "OTHER", "10.00"))
            .`when`().post(BASE_PATH)
            .then().statusCode(401)
        given().contentType("application/json").body("""{"status":"OPEN"}""")
            .`when`().put("$BASE_PATH/$id")
            .then().statusCode(401)
        given().contentType("application/json").`when`().post("$BASE_PATH/$id/withdraw?actor=x").then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "e2e-viewer-1", roles = ["ROLE_VIEWER"])
    fun `viewer reads disputes but never mutates them`() {
        // Reading is allowed on every read surface...
        given().`when`().get(BASE_PATH).then().statusCode(200)
        given().`when`().get("$BASE_PATH/${UUID.randomUUID()}").then().statusCode(404)
        // ...and every mutation is refused: open, update, evidence, withdraw, escalate, resolve,
        // and even the evidence-chain verify (operator-role only).
        given().contentType("application/json")
            .body(openBody(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "OTHER", "15.00"))
            .`when`().post(BASE_PATH)
            .then().statusCode(403)
        given().contentType("application/json")
            .body("""{"submittedBy":"x","evidenceType":"DOC","disputeId":"${UUID.randomUUID()}"}""")
            .`when`().post("$BASE_PATH/${UUID.randomUUID()}/evidence")
            .then().statusCode(403)
        given().contentType(
            "application/json",
        ).`when`().post("$BASE_PATH/${UUID.randomUUID()}/withdraw?actor=x").then().statusCode(403)
        given().contentType(
            "application/json",
        ).`when`().post("$BASE_PATH/${UUID.randomUUID()}/escalate?actor=x").then().statusCode(403)
        given().contentType("application/json")
            .body("""{"outcome":"UPHELD","resolvedBy":"x"}""")
            .`when`().post("$BASE_PATH/${UUID.randomUUID()}/resolve")
            .then().statusCode(403)
        given().`when`().get("$BASE_PATH/${UUID.randomUUID()}/evidence/verify").then().statusCode(403)
    }

    // ---------- REST journey helpers ----------

    private fun openDispute(account: UUID, amount: String): Map<String, Any?> = given().contentType("application/json")
        .body(openBody(UUID.randomUUID(), account, UUID.randomUUID(), "UNAUTHORIZED", amount))
        .`when`().post(BASE_PATH)
        .then().statusCode(201)
        .extract().body().jsonPath().getMap<String, Any?>("")

    private fun addEvidence(disputeId: String, file: String, type: String, description: String) = given()
        .contentType("application/json")
        .body(
            """
            {"disputeId":"$disputeId","submittedBy":"customer-1","evidenceType":"$type",
             "description":"$description","fileReference":"$file"}
            """.trimIndent(),
        )
        .`when`().post("$BASE_PATH/$disputeId/evidence")

    private fun openBody(transactionId: UUID, accountId: UUID, partyId: UUID, disputeType: String, amount: String) =
        """
        {"transactionId":"$transactionId","accountId":"$accountId","partyId":"$partyId",
         "disputeType":"$disputeType","amount":$amount,"currency":"EUR",
         "description":"e2e dispute","merchantName":"e2e-merchant",
         "transactionDate":"2026-10-01"}
        """.trimIndent()

    private companion object {
        const val BASE_PATH = "/api/v1/disputes"
    }
}
