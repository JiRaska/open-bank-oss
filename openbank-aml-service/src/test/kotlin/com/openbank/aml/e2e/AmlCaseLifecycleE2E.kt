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
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * End-to-end journeys of the AML case lifecycle over the real HTTP plane against real Postgres +
 * Valkey. The onboarding reconciler, the outbox dispatch conformance and the idempotency
 * fingerprint collisions are pinned by dedicated ITs; this suite pins what only the wired
 * surface shows:
 *
 *  - **Triage is risk-driven**: a MEDIUM/LOW case opens `OPEN`; a HIGH case lands `UNDER_REVIEW`
 *    immediately — the analyst queue is decided by the risk level, not by who filed the case.
 *  - **The decision journey is a state machine with terminal states**: OPEN → UNDER_REVIEW →
 *    CLEARED records a *fresh* `decidedAt` (the Instant.EPOCH default shipped to production once,
 *    #5837); ESCALATED cases still reach BLOCKED, but only with a mandatory decision reason, and
 *    CLEARED/BLOCKED refuse every further transition with 409 CONFLICT.
 *  - **The list surface filters**: by status, screening type and party, with bounded pages.
 *  - **The M2M boundary is named, not generic** (#10486): any ROLE_API service account can
 *    authenticate, but only the four payment/FX machine principals on the allow-list open a
 *    case — everyone else gets 403, while the named sepa-payment caller gets 201.
 *  - **Guards stay client errors**: a missing or blank Idempotency-Key is 400, an unknown
 *    screening type or risk level is 400, an unknown case id is 404, an unauthenticated caller
 *    is 401, and ROLE_VIEWER reads but never creates or decides.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class, restrictToAnnotatedClass = true)
class AmlCaseLifecycleE2E {

    @Test
    @TestSecurity(user = "e2e-compliance-1", roles = ["ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "e2e-compliance-1"),
            Claim(key = "preferred_username", value = "e2e-compliance-1"),
        ],
    )
    fun `a case opens triaged by risk level and reads back over http`() {
        val party = UUID.randomUUID()
        val before = Instant.now()

        val medium = createCase(party, riskLevel = "MEDIUM", screeningType = "TRANSACTION_MONITORING")
        assertThat(medium["status"]).isEqualTo("OPEN")
        assertThat(medium["partyId"]).isEqualTo(party.toString())
        assertThat(medium["decidedAt"]).isNull()
        assertRecent(medium["createdAt"], before)
        assertRecent(medium["screenedAt"], before)

        // Triage is risk-driven: a HIGH case skips OPEN and lands straight in the analyst queue.
        val high = createCase(UUID.randomUUID(), riskLevel = "HIGH", screeningType = "CUSTOMER_ONBOARDING")
        assertThat(high["status"]).isEqualTo("UNDER_REVIEW")

        // Read-back over GET returns exactly what was created.
        given().`when`().get("/api/v1/aml/cases/${medium["id"]}")
            .then().statusCode(200)
            .body("id", equalTo(medium["id"]))
            .body("status", equalTo("OPEN"))
            .body("riskLevel", equalTo("MEDIUM"))
    }

    @Test
    @TestSecurity(user = "e2e-compliance-1", roles = ["ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "e2e-compliance-1"),
            Claim(key = "preferred_username", value = "e2e-compliance-1"),
        ],
    )
    fun `the analyst decision journey runs open to cleared with a fresh decided-at`() {
        val caseId = createCase(UUID.randomUUID(), riskLevel = "LOW")["id"] as String
        val createdAt = Instant.parse(getCase(caseId)["createdAt"] as String)

        decide(caseId, "UNDER_REVIEW", assignedAnalyst = "analyst-7", by = "e2e-compliance-1")
            .then().statusCode(200)
            .body("status", equalTo("UNDER_REVIEW"))
            .body("assignedAnalyst", equalTo("analyst-7"))

        val decided = decide(
            caseId,
            "CLEARED",
            reason = "documented source of funds",
            by = "e2e-compliance-1",
        ).then().statusCode(200)
            .body("status", equalTo("CLEARED"))
            .body("decidedBy", equalTo("e2e-compliance-1"))
            .body("decidedAt", not(nullValue()))
            .extract().body().jsonPath().getMap<String, Any?>("")

        // #5837: decidedAt used to default to Instant.EPOCH and every terminal decision recorded
        // 1970-01-01. A fresh decision must be stamped at decision time, after the case existed.
        val decidedAt = Instant.parse(decided["decidedAt"] as String)
        assertThat(decidedAt).isAfter(createdAt)
        assertThat(decidedAt).isBefore(Instant.now().plusSeconds(60))

        // The cleared case is findable through the status filter.
        given().`when`().get("/api/v1/aml/cases?status=CLEARED&partyId=${decided["partyId"]}")
            .then().statusCode(200)
            .body("id", hasItem(caseId))
    }

    @Test
    @TestSecurity(user = "e2e-compliance-1", roles = ["ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "e2e-compliance-1"),
            Claim(key = "preferred_username", value = "e2e-compliance-1"),
        ],
    )
    fun `escalated cases reach blocked only with a reason and then stay terminal`() {
        val caseId = createCase(UUID.randomUUID(), riskLevel = "MEDIUM")["id"] as String

        decide(caseId, "ESCALATED", reason = "senior review", by = "e2e-compliance-1")
            .then().statusCode(200).body("status", equalTo("ESCALATED"))
        decide(caseId, "UNDER_REVIEW", assignedAnalyst = "analyst-2", by = "e2e-compliance-1")
            .then().statusCode(200).body("status", equalTo("UNDER_REVIEW"))

        // BLOCKED is a deliberate act: without a reason the domain refuses the transition.
        decide(caseId, "BLOCKED", by = "e2e-compliance-1")
            .then().statusCode(409)
            .body("code", equalTo("CONFLICT"))

        val blocked = decide(caseId, "BLOCKED", reason = "confirmed sanctions nexus", by = "e2e-compliance-1")
            .then().statusCode(200)
            .body("status", equalTo("BLOCKED"))
            .body("decisionReason", equalTo("confirmed sanctions nexus"))
            .extract().body().jsonPath().getMap<String, Any?>("")
        assertThat(blocked["decidedAt"]).isNotNull()

        // Terminal: BLOCKED refuses every further transition, and so does CLEARED.
        decide(caseId, "UNDER_REVIEW", by = "e2e-compliance-1").then().statusCode(409)

        val clearedCase = createCase(UUID.randomUUID(), riskLevel = "LOW")["id"] as String
        decide(clearedCase, "CLEARED", reason = "false positive", by = "e2e-compliance-1")
            .then().statusCode(200)
        decide(clearedCase, "UNDER_REVIEW", by = "e2e-compliance-1").then().statusCode(409)
    }

    @Test
    @TestSecurity(user = "e2e-compliance-1", roles = ["ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "e2e-compliance-1"),
            Claim(key = "preferred_username", value = "e2e-compliance-1"),
        ],
    )
    fun `the list surface filters by status screening type and party with bounded pages`() {
        val partyA = UUID.randomUUID()
        val partyB = UUID.randomUUID()
        val a1 = createCase(partyA, riskLevel = "LOW", screeningType = "MANUAL_INVESTIGATION")["id"] as String
        val a2 = createCase(partyA, riskLevel = "HIGH", screeningType = "PERIODIC_REVIEW")["id"] as String
        val b1 = createCase(partyB, riskLevel = "LOW", screeningType = "MANUAL_INVESTIGATION")["id"] as String

        // Party filter: only party A's cases, both present, party B's absent.
        given().`when`().get("/api/v1/aml/cases?partyId=$partyA")
            .then().statusCode(200)
            .body("id", hasItem(a1))
            .body("id", hasItem(a2))
            .body("id", not(hasItem(b1)))

        // Status filter: the HIGH case triaged itself into UNDER_REVIEW; the LOW ones stay OPEN.
        given().`when`().get("/api/v1/aml/cases?partyId=$partyA&status=UNDER_REVIEW")
            .then().statusCode(200)
            .body("id", hasItem(a2))
            .body("id", not(hasItem(a1)))

        // Screening-type filter.
        given().`when`().get("/api/v1/aml/cases?partyId=$partyA&screeningType=PERIODIC_REVIEW")
            .then().statusCode(200)
            .body("id", hasItem(a2))
            .body("id", not(hasItem(a1)))

        // Bounded pages: limit=1 pages through party A's two cases without overlap.
        val first = given().`when`().get("/api/v1/aml/cases?partyId=$partyA&limit=1")
            .then().statusCode(200).body("size()", equalTo(1))
            .extract().body().jsonPath().getList<String>("id")
        val second = given().`when`().get("/api/v1/aml/cases?partyId=$partyA&limit=1&offset=1")
            .then().statusCode(200).body("size()", equalTo(1))
            .extract().body().jsonPath().getList<String>("id")
        assertThat(first + second).containsExactlyInAnyOrder(a1, a2)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-edge", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-edge"),
            Claim(key = "sub", value = "e2e-edge-m2m"),
            Claim(key = "preferred_username", value = "service-account-openbank-edge"),
        ],
    )
    fun `a machine caller outside the named case-open list is refused 403`() {
        createCaseRaw("e2e-edge-${UUID.randomUUID()}", body(UUID.randomUUID()))
            .then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "service-account-openbank-sepa-payment", roles = ["ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-sepa-payment"),
            Claim(key = "sub", value = "e2e-sepa-m2m"),
            Claim(key = "preferred_username", value = "service-account-openbank-sepa-payment"),
        ],
    )
    fun `the named sepa-payment machine caller opens a case over m2m`() {
        val opened = createCase(UUID.randomUUID(), riskLevel = "MEDIUM", ref = "m2m-ref-${UUID.randomUUID()}")
        assertThat(opened["status"]).isEqualTo("OPEN")
        assertThat(opened["id"]).isNotNull()
    }

    @Test
    @TestSecurity(user = "e2e-operator-1", roles = ["ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "e2e-operator-1"),
            Claim(key = "preferred_username", value = "e2e-operator-1"),
        ],
    )
    fun `malformed case submissions stay client errors`() {
        val party = UUID.randomUUID()
        // Missing Idempotency-Key: the header is required (#3624 shape — 400, never a 500).
        given().contentType("application/json").body(body(party))
            .`when`().post(CASES_PATH)
            .then().statusCode(400)
        // Blank Idempotency-Key.
        given().contentType("application/json").header("Idempotency-Key", " ")
            .body(body(party))
            .`when`().post(CASES_PATH)
            .then().statusCode(400)
        // Unknown screening type and risk level: valueOf at the boundary is a 400, not a 500.
        createCaseRaw("e2e-bad-st-${UUID.randomUUID()}", body(party, screeningType = "NO_SUCH_TYPE"))
            .then().statusCode(400)
        createCaseRaw("e2e-bad-rl-${UUID.randomUUID()}", body(party, riskLevel = "NO_SUCH_LEVEL"))
            .then().statusCode(400)
        // Unknown case id: 404, not a 500.
        given().`when`().get("$CASES_PATH/${UUID.randomUUID()}")
            .then().statusCode(404)
            .body("code", equalTo("NOT_FOUND"))
    }

    @Test
    fun `an unauthenticated caller is refused 401 on every case surface`() {
        val caseId = UUID.randomUUID()
        given().contentType("application/json").header("Idempotency-Key", "e2e-${UUID.randomUUID()}")
            .body(body(caseId))
            .`when`().post(CASES_PATH)
            .then().statusCode(401)
        given().`when`().get("$CASES_PATH/$caseId").then().statusCode(401)
        given().`when`().get(CASES_PATH).then().statusCode(401)
        given().contentType("application/json")
            .body("""{"targetStatus":"CLEARED","decidedBy":"nobody"}""")
            .`when`().put("$CASES_PATH/$caseId/decision")
            .then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "e2e-viewer-1", roles = ["ROLE_VIEWER"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-admin-ui"),
            Claim(key = "sub", value = "e2e-viewer-1"),
            Claim(key = "preferred_username", value = "e2e-viewer-1"),
        ],
    )
    fun `viewer reads cases but never creates or decides`() {
        // Reading is allowed...
        given().`when`().get(CASES_PATH).then().statusCode(200)
        // ...creating and deciding are not.
        given().contentType("application/json").header("Idempotency-Key", "e2e-${UUID.randomUUID()}")
            .body(body(UUID.randomUUID()))
            .`when`().post(CASES_PATH)
            .then().statusCode(403)
        given().contentType("application/json")
            .body("""{"targetStatus":"CLEARED","decidedBy":"e2e-viewer-1"}""")
            .`when`().put("$CASES_PATH/${UUID.randomUUID()}/decision")
            .then().statusCode(403)
    }

    // ---------- REST journey helpers ----------

    private fun createCase(
        partyId: UUID,
        riskLevel: String = "MEDIUM",
        screeningType: String = "MANUAL_INVESTIGATION",
        ref: String = "ref-${UUID.randomUUID()}",
    ): Map<String, Any?> = createCaseRaw(
        "e2e-${UUID.randomUUID()}",
        body(partyId, riskLevel = riskLevel, screeningType = screeningType, ref = ref),
    ).then().statusCode(201).extract().body().jsonPath().getMap<String, Any?>("")

    private fun createCaseRaw(idempotencyKey: String, bodyJson: String) = given()
        .contentType("application/json")
        .header("Idempotency-Key", idempotencyKey)
        .body(bodyJson)
        .`when`().post(CASES_PATH)

    private fun decide(
        caseId: String,
        targetStatus: String,
        reason: String? = null,
        assignedAnalyst: String? = null,
        by: String,
    ): io.restassured.response.Response {
        val fields = mutableListOf("\"targetStatus\":\"$targetStatus\"", "\"decidedBy\":\"$by\"")
        reason?.let { fields += "\"decisionReason\":\"$it\"" }
        assignedAnalyst?.let { fields += "\"assignedAnalyst\":\"$it\"" }
        return given()
            .contentType("application/json")
            .body("{${fields.joinToString(",")}}")
            .`when`().put("$CASES_PATH/$caseId/decision")
    }

    private fun getCase(caseId: String): Map<String, Any?> = given().`when`().get("$CASES_PATH/$caseId")
        .then().statusCode(200).extract().body().jsonPath().getMap<String, Any?>("")

    private fun body(
        partyId: UUID,
        riskLevel: String = "MEDIUM",
        screeningType: String = "MANUAL_INVESTIGATION",
        ref: String = "ref-${UUID.randomUUID()}",
    ) = """
        {"partyId":"$partyId","customerReference":"$ref",
         "screeningType":"$screeningType","riskLevel":"$riskLevel","alertCode":"E2E-ALERT-1"}
    """.trimIndent()

    private fun assertRecent(value: Any?, after: Instant) {
        val instant = when (value) {
            is String -> Instant.parse(value)
            is Number -> Instant.ofEpochMilli(value.toLong())
            else -> error("unparseable instant: $value")
        }
        assertThat(instant).isBetween(after, Instant.now().plusSeconds(60))
    }

    private companion object {
        const val CASES_PATH = "/api/v1/aml/cases"
    }
}
