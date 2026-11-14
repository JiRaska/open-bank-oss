// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.oidc.Claim
import io.quarkus.test.security.oidc.ClaimType
import io.quarkus.test.security.oidc.OidcSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.response.Response
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * ADR-0374 point 3 through real HTTP and a real Postgres: a held payment's case reaches CLEARED or
 * BLOCKED only when one analyst proposes and a different one confirms the same outcome and reason,
 * and the status-changed event a rail releases the payment on is written exactly once, on the
 * confirmation. Ordered: each step is one principal's request. Ids are literals.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class HoldDecisionFourEyesIT : MonitoringDecisionITSupport() {

    // Explicit row ids far above both the BIGSERIAL and the Hibernate pooled sequence: other classes
    // in the same database draw from those, and an implicit id collided with one of theirs.
    private fun seedHoldCase(rowId: Long, caseId: UUID, payment: String) {
        rows(
            """
            INSERT INTO aml_cases
              (id, case_id, idempotency_key, party_id, account_id, customer_reference, screening_type, risk_level,
               status, alert_code, origin, screened_at, created_at, updated_at)
            VALUES ($4, $1, $2, NULL, $3, 'ref', 'TRANSACTION_MONITORING', 'HIGH', 'UNDER_REVIEW', 'PAYMENT_HELD',
                    'PAYMENT_HOLD_INTAKE', now(), now(), now())
            """.trimIndent(),
            caseId,
            "payment-hold:domestic:$payment",
            ACCOUNT,
            rowId,
        )
    }

    private fun statusEvents(caseId: UUID) = rows(
        "SELECT payload FROM aml_outbox WHERE aggregate_id = $1 AND event_type = 'aml.case.status_changed.v1'",
        caseId,
    ).map { it.getString("payload") }

    private fun anyEvents(caseId: UUID) = rows("SELECT 1 FROM aml_outbox WHERE aggregate_id = $1", caseId)

    private fun propose(caseId: UUID, outcome: String, reason: String): Response = given().contentType(ContentType.JSON)
        .body(mapOf("outcome" to outcome, "reason" to reason))
        .post("/api/v1/aml/cases/$caseId/hold-decision/proposal")

    private fun confirm(caseId: UUID, outcome: String, reason: String): Response = given().contentType(ContentType.JSON)
        .body(mapOf("outcome" to outcome, "reason" to reason))
        .post("/api/v1/aml/cases/$caseId/hold-decision/confirmation")

    private fun withdraw(caseId: UUID): Response = given().contentType(ContentType.JSON)
        .post("/api/v1/aml/cases/$caseId/hold-decision/withdrawal")

    private fun put(caseId: UUID, status: String): Response = given().contentType(ContentType.JSON)
        .body(mapOf("targetStatus" to status, "decisionReason" to "looks fine"))
        .put("/api/v1/aml/cases/$caseId/decision")

    @Test
    @Order(1)
    @TestSecurity(user = "alice", roles = ["ROLE_COMPLIANCE", "ROLE_OPERATOR"])
    @OidcSecurity(
        claims = [
            Claim(
                key = "azp",
                value = HUMAN_CLIENT,
            ), Claim(key = "sub", value = "alice"), Claim(key = "preferred_username", value = "alice"),
        ],
    )
    fun `(1) the single-step endpoint refuses a terminal decision on a hold case and points at the proposal routes`() {
        seedHoldCase(900_000_001L, HOLD_CASE, "00000000-0000-4000-8000-00000000e011")
        seedHoldCase(900_000_002L, REPLACED_CASE, "00000000-0000-4000-8000-00000000e012")
        rows(
            """
            INSERT INTO aml_cases
              (id, case_id, idempotency_key, party_id, customer_reference, screening_type, risk_level, status,
               alert_code, origin, screened_at, created_at, updated_at)
            VALUES (900000003, $1, 'it:legacy-4eyes', $2, 'ref', 'TRANSACTION_MONITORING', 'MEDIUM', 'OPEN',
                    'TM-900', 'REST', now(), now(), now())
            """.trimIndent(),
            LEGACY_CASE,
            PARTY,
        )
        assertThat(
            rows("SELECT 1 FROM aml_cases WHERE case_id IN ($1, $2, $3)", HOLD_CASE, REPLACED_CASE, LEGACY_CASE),
        ).hasSize(3)
        for (target in listOf("CLEARED", "BLOCKED")) {
            put(
                HOLD_CASE,
                target,
            ).then().statusCode(409).body("message", org.hamcrest.Matchers.containsString("hold-decision/proposal"))
        }
        assertThat(case(HOLD_CASE).getString("status")).isEqualTo("UNDER_REVIEW")
        assertThat(anyEvents(HOLD_CASE)).isEmpty()
        // Control: a case the intake did not open keeps its single-analyst decision and its event shape.
        put(
            LEGACY_CASE,
            "CLEARED",
        ).then().statusCode(200).body("status", equalTo("CLEARED")).body("decidedBy", equalTo("alice"))
        assertThat(statusEvents(LEGACY_CASE)).singleElement().satisfies({ assertThat(it).doesNotContain("origin") })
    }

    @Test
    @Order(2)
    @TestSecurity(user = "alice", roles = ["ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = HUMAN_CLIENT), Claim(key = "sub", value = "alice"),
            Claim(key = "preferred_username", value = "alice"), Claim(key = "person_id", value = "staff-a"),
            Claim(key = "amr", value = OTP_AMR, type = ClaimType.JSON_ARRAY),
        ],
    )
    fun `(2) alice proposes, nothing is decided and nothing is emitted, and she cannot confirm herself`() {
        propose(HOLD_CASE, "CLEAR", "false positive").then().statusCode(201)
            .body("proposedBy", equalTo("alice")).body("outcome", equalTo("CLEAR"))
            .body("status", equalTo("UNDER_REVIEW")).body("expiresAt", org.hamcrest.Matchers.notNullValue())
        assertThat(case(HOLD_CASE).getString("status")).isEqualTo("UNDER_REVIEW")
        assertThat(
            rows("SELECT hold_proposer_person_id FROM aml_cases WHERE case_id = $1", HOLD_CASE).single().getString(0),
        )
            .isEqualTo("staff-a")
        assertThat(anyEvents(HOLD_CASE)).isEmpty()
        confirm(HOLD_CASE, "CLEAR", "false positive").then().statusCode(409)
        assertThat(case(HOLD_CASE).getString("status")).isEqualTo("UNDER_REVIEW")
        assertThat(anyEvents(HOLD_CASE)).isEmpty()
        propose(REPLACED_CASE, "CLEAR", "first look").then().statusCode(201)
        // Not a hold case: no proposal route.
        propose(LEGACY_CASE, "CLEAR", "x").then().statusCode(409)
    }

    @Test
    @Order(3)
    @TestSecurity(user = "alice.second", roles = ["ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = HUMAN_CLIENT), Claim(key = "sub", value = "alice.second"),
            Claim(key = "preferred_username", value = "alice.second"), Claim(key = "person_id", value = "staff-a"),
            Claim(key = "amr", value = OTP_AMR, type = ClaimType.JSON_ARRAY),
        ],
    )
    fun `(3) alice through her second account cannot confirm, but withdraws her own proposal`() {
        confirm(HOLD_CASE, "CLEAR", "false positive").then().statusCode(409)
            .body("message", org.hamcrest.Matchers.containsString("same person"))
        assertThat(anyEvents(HOLD_CASE)).isEmpty()
        withdraw(REPLACED_CASE).then().statusCode(200).body("withdrawnBy", equalTo("alice.second"))
        assertThat(anyEvents(REPLACED_CASE)).isEmpty()
    }

    @Test
    @Order(4)
    @TestSecurity(user = "svc", roles = ["ROLE_COMPLIANCE", "ROLE_OPERATOR", "ROLE_API"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = "openbank-services"),
            Claim(key = "sub", value = "svc"),
            Claim(key = "preferred_username", value = "service-account-openbank-services"),
        ],
    )
    fun `(4) a service account holding the analyst role is refused`() {
        confirm(HOLD_CASE, "CLEAR", "false positive").then().statusCode(403)
        propose(HOLD_CASE, "BLOCK", "x").then().statusCode(403)
        withdraw(HOLD_CASE).then().statusCode(403)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "dave", roles = ["ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = HUMAN_CLIENT), Claim(key = "sub", value = "dave"),
            Claim(key = "preferred_username", value = "dave"),
            Claim(key = "amr", value = OTP_AMR, type = ClaimType.JSON_ARRAY),
        ],
    )
    fun `(5) a staff token without a person id can neither confirm nor propose`() {
        confirm(HOLD_CASE, "CLEAR", "false positive").then().statusCode(409)
            .body("message", org.hamcrest.Matchers.containsString("person id"))
        propose(REPLACED_CASE, "BLOCK", "second look").then().statusCode(409)
        assertThat(anyEvents(HOLD_CASE)).isEmpty()
    }

    @Test
    @Order(6)
    @TestSecurity(user = "carol", roles = ["ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = HUMAN_CLIENT), Claim(key = "sub", value = "carol"),
            Claim(key = "preferred_username", value = "carol"), Claim(key = "person_id", value = "staff-c"),
            Claim(key = "amr", value = OTP_AMR, type = ClaimType.JSON_ARRAY),
        ],
    )
    fun `(6) carol proposes on the withdrawn case, but cannot replace or withdraw alice's pending one`() {
        propose(HOLD_CASE, "BLOCK", "replace it").then().statusCode(409)
        withdraw(HOLD_CASE).then().statusCode(409)
        propose(REPLACED_CASE, "BLOCK", "second look").then().statusCode(201).body("proposedBy", equalTo("carol"))
        // A proposal of the V17 shape (no person id, no expiry) neither blocks nor can be confirmed.
        seedHoldCase(900_000_004L, V17_CASE, "00000000-0000-4000-8000-00000000e013")
        rows(
            "UPDATE aml_cases SET hold_proposal_outcome = 'CLEAR', hold_proposal_reason = 'old', " +
                "hold_proposed_by = 'legacy', hold_proposed_at = now() WHERE case_id = $1",
            V17_CASE,
        )
        confirm(V17_CASE, "CLEAR", "old").then().statusCode(409)
        propose(V17_CASE, "BLOCK", "fresh look").then().statusCode(201).body("proposedBy", equalTo("carol"))
    }

    @Test
    @Order(7)
    @TestSecurity(user = "bob", roles = ["ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = HUMAN_CLIENT), Claim(key = "sub", value = "bob"),
            Claim(key = "preferred_username", value = "bob"), Claim(key = "person_id", value = "staff-b"),
            Claim(key = "amr", value = PWD_AMR, type = ClaimType.JSON_ARRAY),
        ],
    )
    fun `(7) bob without a second factor cannot confirm`() {
        confirm(HOLD_CASE, "CLEAR", "false positive").then().statusCode(409)
            .body("message", org.hamcrest.Matchers.containsString("second factor"))
        assertThat(anyEvents(HOLD_CASE)).isEmpty()
    }

    @Test
    @Order(8)
    @TestSecurity(user = "bob", roles = ["ROLE_COMPLIANCE"])
    @OidcSecurity(
        claims = [
            Claim(key = "azp", value = HUMAN_CLIENT), Claim(key = "sub", value = "bob"),
            Claim(key = "preferred_username", value = "bob"), Claim(key = "person_id", value = "staff-b"),
            Claim(key = "amr", value = OTP_AMR, type = ClaimType.JSON_ARRAY),
        ],
    )
    fun `(8) bob confirms only what was proposed, and exactly one event leaves, with origin`() {
        confirm(HOLD_CASE, "BLOCK", "false positive").then().statusCode(409)
        confirm(HOLD_CASE, "CLEAR", "a different reason").then().statusCode(409)
        // What alice proposed and then withdrew: refused, so no decision lands on a proposal that is gone.
        confirm(REPLACED_CASE, "CLEAR", "first look").then().statusCode(409)
        assertThat(anyEvents(HOLD_CASE)).isEmpty()
        assertThat(anyEvents(REPLACED_CASE)).isEmpty()

        given().get("/api/v1/aml/cases/$HOLD_CASE/hold-decision/proposal").then().statusCode(200)
            .body("proposedBy", equalTo("alice")).body("reason", equalTo("false positive"))
        confirm(HOLD_CASE, "CLEAR", "false positive").then().statusCode(200)
            .body("status", equalTo("CLEARED")).body("decidedBy", equalTo("bob"))
        val stored = case(HOLD_CASE)
        assertThat(stored.getString("status")).isEqualTo("CLEARED")
        assertThat(stored.getString("decided_by")).isEqualTo("bob")
        assertThat(stored.getString("decision_reason")).isEqualTo("false positive")
        assertThat(statusEvents(HOLD_CASE)).singleElement().satisfies({
            assertThat(
                it,
            ).contains("\"origin\":\"PAYMENT_HOLD_INTAKE\"", "\"newStatus\":\"CLEARED\"", "\"decidedBy\":\"bob\"")
        })
        // Decided once: a second confirmation is refused and writes nothing.
        confirm(HOLD_CASE, "CLEAR", "false positive").then().statusCode(409)
        assertThat(statusEvents(HOLD_CASE)).hasSize(1)
        // Carol's proposal, made after alice withdrew hers, is confirmed as it now stands.
        confirm(REPLACED_CASE, "BLOCK", "second look").then().statusCode(200).body("status", equalTo("BLOCKED"))
        assertThat(statusEvents(REPLACED_CASE)).singleElement().satisfies({
            assertThat(it).contains("\"origin\":\"PAYMENT_HOLD_INTAKE\"")
        })
    }

    private companion object {
        const val HUMAN_CLIENT = "openbank-admin-ui"
        const val OTP_AMR = "[\"pwd\",\"otp\"]"
        const val PWD_AMR = "[\"pwd\"]"
        val PARTY: UUID = UUID.fromString("00000000-0000-4000-8000-00000000e001")
        val ACCOUNT: UUID = UUID.fromString("00000000-0000-4000-8000-00000000e002")
        val HOLD_CASE: UUID = UUID.fromString("00000000-0000-4000-8000-00000000e003")
        val REPLACED_CASE: UUID = UUID.fromString("00000000-0000-4000-8000-00000000e004")
        val V17_CASE: UUID = UUID.fromString("00000000-0000-4000-8000-00000000e006")
        val LEGACY_CASE: UUID = UUID.fromString("00000000-0000-4000-8000-00000000e005")
    }
}
