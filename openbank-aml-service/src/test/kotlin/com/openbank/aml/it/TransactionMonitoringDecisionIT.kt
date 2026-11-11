// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * ADR-0371 §5, §7, §10 through real HTTP and a real database: who decided is the authenticated
 * caller, the opener alone cannot decide, a monitoring case does not exist for an operator, and a
 * closing decision carries a structured outcome. Ordered: each step is one principal's request.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TransactionMonitoringDecisionIT : MonitoringDecisionITSupport() {

    private fun propose(outcomeCode: String) = given().contentType(ContentType.JSON)
        .body(mapOf("disposition" to "CLEAR", "outcomeCode" to outcomeCode, "note" to "synthetic"))
        .post("/api/v1/aml/monitoring/cases/$MONITORING_CASE/disposition-proposals")

    private fun proposalId(): String =
        rows("SELECT proposal_id FROM aml_case_disposition_proposal WHERE case_id = $1", MONITORING_CASE)
            .single().getUUID("proposal_id").toString()

    private fun decide(approve: Boolean) = given().contentType(ContentType.JSON)
        .body(mapOf("approve" to approve, "reason" to "reviewed"))
        .post("/api/v1/aml/monitoring/disposition-proposals/${proposalId()}/decision")

    @Test
    @Order(1)
    @TestSecurity(user = "alice", roles = ["ROLE_COMPLIANCE"])
    fun `(1) an outcome code the vocabulary does not hold is refused and nothing is proposed`() {
        seedCase(MONITORING_CASE, PARTY, "TRANSACTION_MONITORING")
        seedCase(ONBOARDING_CASE, PARTY, "CUSTOMER_ONBOARDING")
        propose("LOOKS_FINE").then().statusCode(400)
        assertThat(rows("SELECT 1 FROM aml_case_disposition_proposal WHERE case_id = $1", MONITORING_CASE)).isEmpty()
    }

    @Test
    @Order(2)
    @TestSecurity(user = "alice", roles = ["ROLE_COMPLIANCE"])
    fun `(2) the opener proposes clearing, as herself, with a structured outcome`() {
        propose("FALSE_POSITIVE_EXPLAINED_ACTIVITY").then().statusCode(201)
            .body("proposedBy", equalTo("alice"))
            .body("state", equalTo("PROPOSED"))
            .body("outcome.code", equalTo("FALSE_POSITIVE_EXPLAINED_ACTIVITY"))
            .body("outcome.vocabularyId", equalTo("aml.decision-outcomes"))
        propose("FALSE_POSITIVE_EXPLAINED_ACTIVITY").then().statusCode(409)
        assertThat(case(MONITORING_CASE).getString("status")).isEqualTo("OPEN")
    }

    @Test
    @Order(3)
    @TestSecurity(user = "alice", roles = ["ROLE_COMPLIANCE"])
    fun `(3) the opener alone cannot decide, by approving or through the plain decision endpoint`() {
        decide(approve = true).then().statusCode(422)
        // The body names someone else as decider; it is ignored, and the endpoint refuses anyway.
        given().contentType(ContentType.JSON)
            .body(mapOf("targetStatus" to "CLEARED", "decisionReason" to "fine", "decidedBy" to "bob"))
            .put("/api/v1/aml/cases/$MONITORING_CASE/decision").then().statusCode(409)
        val stored = case(MONITORING_CASE)
        assertThat(stored.getString("status")).isEqualTo("OPEN")
        assertThat(stored.getString("decided_by")).isNull()
        assertThat(rows("SELECT state FROM aml_case_disposition_proposal WHERE case_id = $1", MONITORING_CASE).single().getString("state"))
            .isEqualTo("PROPOSED")
    }

    @Test
    @Order(4)
    @TestSecurity(user = "olga", roles = ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_VIEWER"])
    fun `(4) for an operator the monitoring case does not exist and the decision endpoints are forbidden`() {
        given().get("/api/v1/aml/cases/$MONITORING_CASE").then().statusCode(404)
        given().get("/api/v1/aml/cases/$ONBOARDING_CASE").then().statusCode(200)
        val listed = given().queryParam("partyId", PARTY.toString()).get("/api/v1/aml/cases").then().statusCode(200)
            .extract().jsonPath().getList<String>("id")
        assertThat(listed).contains(ONBOARDING_CASE.toString()).doesNotContain(MONITORING_CASE.toString())
        given().contentType(ContentType.JSON).body(mapOf("targetStatus" to "UNDER_REVIEW"))
            .put("/api/v1/aml/cases/$MONITORING_CASE/decision").then().statusCode(404)
        decide(approve = true).then().statusCode(403)
        given().get("/api/v1/aml/monitoring/disposition-proposals").then().statusCode(403)
        assertThat(case(MONITORING_CASE).getString("status")).isEqualTo("OPEN")
    }

    @Test
    @Order(5)
    @TestSecurity(user = "customer-1", roles = ["ROLE_CUSTOMER"])
    fun `(5) a customer principal is denied every monitoring surface`() {
        given().get("/api/v1/aml/cases/$MONITORING_CASE").then().statusCode(403)
        given().get("/api/v1/aml/monitoring/disposition-proposals").then().statusCode(403)
        propose("CONFIRMED_SUSPICION").then().statusCode(403)
    }

    @Test
    @Order(6)
    @TestSecurity(user = "bob", roles = ["ROLE_COMPLIANCE"])
    fun `(6) a second, different person approves and the case records him as the decider`() {
        given().get("/api/v1/aml/monitoring/disposition-proposals").then().statusCode(200)
            .body("caseId", org.hamcrest.Matchers.hasItem(MONITORING_CASE.toString()))
        decide(approve = true).then().statusCode(200)
            .body("state", equalTo("EXECUTED"))
            .body("decidedBy", equalTo("bob"))
            .body("proposedBy", equalTo("alice"))
        val stored = case(MONITORING_CASE)
        assertThat(stored.getString("status")).isEqualTo("CLEARED")
        assertThat(stored.getString("decided_by")).isEqualTo("bob")
        assertThat(stored.getString("decision_reason")).contains("FALSE_POSITIVE_EXPLAINED_ACTIVITY", "alice")
        val label = rows(
            "SELECT outcome_code, outcome_revision, outcome_hash, decided_by FROM aml_case_disposition_proposal WHERE case_id = $1",
            MONITORING_CASE,
        ).single()
        assertThat(label.getString("outcome_code")).isEqualTo("FALSE_POSITIVE_EXPLAINED_ACTIVITY")
        assertThat(label.getString("outcome_hash")).matches("[0-9a-f]{64}")
        decide(approve = true).then().statusCode(422)
    }

    @Test
    @Order(7)
    @TestSecurity(user = "alice", roles = ["ROLE_COMPLIANCE"])
    fun `(7) on any case the decider is the caller, whatever name the request body carries`() {
        given().contentType(ContentType.JSON)
            .body(mapOf("targetStatus" to "CLEARED", "decisionReason" to "no adverse match", "decidedBy" to "mallory"))
            .put("/api/v1/aml/cases/$ONBOARDING_CASE/decision").then().statusCode(200)
            .body("decidedBy", equalTo("alice"))
        assertThat(case(ONBOARDING_CASE).getString("decided_by")).isEqualTo("alice")
    }

    private companion object {
        val PARTY: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c101")
        val MONITORING_CASE: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c102")
        val ONBOARDING_CASE: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c103")
    }
}
