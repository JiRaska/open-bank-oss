// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.aml.it

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.Instant
import java.util.UUID

/**
 * The suspicious-transaction report through real HTTP and a real database (ADR-0371 §6, §7, §10):
 * it cannot reach FILED without a second approver — not through the API and not in storage — a
 * customer and an operator are denied, and its events carry an id and a state and nothing else.
 */
@QuarkusTest
@QuarkusTestResource(PostgresRedisTestResource::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SuspiciousTransactionReportIT : MonitoringDecisionITSupport() {

    private fun reportId(): String = rows("SELECT report_id FROM aml_str WHERE party_id = $1", PARTY).single().getUUID("report_id").toString()

    private fun state(): String = rows("SELECT state FROM aml_str WHERE party_id = $1", PARTY).single().getString("state")

    private fun draftBody(party: UUID, case: UUID) = mapOf(
        "partyId" to party.toString(),
        "narrative" to "Synthetic narrative for the test.",
        "caseIds" to listOf(case.toString()),
        "movements" to listOf(mapOf("journalEntryId" to JOURNAL.toString(), "accountId" to ACCOUNT.toString(), "currency" to "CZK")),
    )

    private fun file(reference: String?, outcomeCode: String) = given().contentType(ContentType.JSON)
        .body(mapOf("filingReference" to reference, "filedAt" to Instant.now().toString(), "outcomeCode" to outcomeCode))
        .post("/api/v1/aml/reports/${reportId()}/filing")

    @Test
    @Order(1)
    @TestSecurity(user = "alice", roles = ["ROLE_COMPLIANCE"])
    fun `(1) a report rests only on the reported party's own monitoring cases and movements`() {
        seedCase(CASE, PARTY, "TRANSACTION_MONITORING", packMonitoring = true)
        seedCase(OTHER_PARTY_CASE, OTHER_PARTY, "TRANSACTION_MONITORING", packMonitoring = true)
        seedCase(RAIL_CASE, PARTY, "TRANSACTION_MONITORING")
        rows(
            """
            INSERT INTO aml_booked_movement
              (journal_entry_id, account_id, currency, direction, amount, transaction_id, entry_date, booked_at,
               recorded_at, party_id, rail, counted_at)
            VALUES ($1, $2, 'CZK', 'OUT', 950.00, $3, current_date, now(), now(), $4, 'DOMESTIC', now())
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            JOURNAL,
            ACCOUNT,
            UUID.fromString("00000000-0000-4000-8000-00000000c2f0"),
            PARTY,
        )
        given().contentType(ContentType.JSON).body(draftBody(PARTY, OTHER_PARTY_CASE))
            .post("/api/v1/aml/reports").then().statusCode(400)
        // A rail-opened case of the same party is not a monitoring case and cannot carry a report.
        given().contentType(ContentType.JSON).body(draftBody(PARTY, RAIL_CASE))
            .post("/api/v1/aml/reports").then().statusCode(400)
        given().contentType(ContentType.JSON).body(draftBody(OTHER_PARTY, OTHER_PARTY_CASE))
            .post("/api/v1/aml/reports").then().statusCode(400)
        assertThat(rows("SELECT 1 FROM aml_str")).isEmpty()

        given().contentType(ContentType.JSON).body(draftBody(PARTY, CASE))
            .post("/api/v1/aml/reports").then().statusCode(201)
            .body("state", equalTo("DRAFT"))
            .body("draftedBy", equalTo("alice"))
            .body("approvedBy", nullValue())
            .body("movements[0].currency", equalTo("CZK"))
    }

    @Test
    @Order(2)
    @TestSecurity(user = "alice", roles = ["ROLE_COMPLIANCE"])
    fun `(2) the drafter can neither approve nor file her own report`() {
        given().post("/api/v1/aml/reports/${reportId()}/approval").then().statusCode(422)
        file("REF-SYNTHETIC-1", "CONFIRMED_SUSPICION").then().statusCode(422)
        assertThat(state()).isEqualTo("DRAFT")
    }

    @Test
    @Order(3)
    fun `(3) storage refuses FILED without an approver, whatever the application does`() {
        assertThatThrownBy {
            rows(
                """
                UPDATE aml_str SET state = 'FILED', filing_reference = 'X', filed_at = now(),
                       filing_recorded_by = 'alice', outcome_code = 'CONFIRMED_SUSPICION'
                 WHERE party_id = $1
                """.trimIndent(),
                PARTY,
            )
        }.hasMessageContaining("ck_aml_str_filed")
        assertThatThrownBy {
            rows("UPDATE aml_str SET state = 'APPROVED', approved_by = drafted_by, approved_at = now() WHERE party_id = $1", PARTY)
        }.hasMessageContaining("ck_aml_str_four_eyes")
        assertThat(state()).isEqualTo("DRAFT")
    }

    @Test
    @Order(4)
    @TestSecurity(user = "customer-1", roles = ["ROLE_CUSTOMER"])
    fun `(4) a customer principal reading or touching a report is denied`() {
        given().get("/api/v1/aml/reports/${reportId()}").then().statusCode(403)
        given().get("/api/v1/aml/reports").then().statusCode(403)
        given().post("/api/v1/aml/reports/${reportId()}/approval").then().statusCode(403)
    }

    @Test
    @Order(5)
    @TestSecurity(user = "olga", roles = ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_VIEWER", "ROLE_API"])
    fun `(5) an operator, an admin and a service role are denied too`() {
        given().get("/api/v1/aml/reports/${reportId()}").then().statusCode(403)
        given().get("/api/v1/aml/reports").then().statusCode(403)
        given().post("/api/v1/aml/reports/${reportId()}/approval").then().statusCode(403)
        assertThat(state()).isEqualTo("DRAFT")
    }

    @Test
    @Order(6)
    @TestSecurity(user = "bob", roles = ["ROLE_COMPLIANCE"])
    fun `(6) a second person approves, then records the filing with a reference and a structured outcome`() {
        given().get("/api/v1/aml/reports/${reportId()}").then().statusCode(200).body("state", equalTo("DRAFT"))
        given().post("/api/v1/aml/reports/${reportId()}/approval").then().statusCode(200)
            .body("state", equalTo("APPROVED"))
            .body("approvedBy", equalTo("bob"))

        file("REF-SYNTHETIC-1", "NOT_AN_OUTCOME").then().statusCode(400)
        file(null, "CONFIRMED_SUSPICION").then().statusCode(400)
        assertThat(state()).isEqualTo("APPROVED")

        file("REF-SYNTHETIC-1", "CONFIRMED_SUSPICION").then().statusCode(200)
            .body("state", equalTo("FILED"))
            .body("filingReference", equalTo("REF-SYNTHETIC-1"))
            .body("filingRecordedBy", equalTo("bob"))
            .body("outcome.code", equalTo("CONFIRMED_SUSPICION"))
        val stored = rows("SELECT approved_by, drafted_by, filed_at, filing_recorded_at FROM aml_str WHERE party_id = $1", PARTY).single()
        assertThat(stored.getString("approved_by")).isEqualTo("bob")
        assertThat(stored.getString("drafted_by")).isEqualTo("alice")
        assertThat(stored.getOffsetDateTime("filed_at")).isNotNull()

        given().contentType(ContentType.JSON).body(mapOf("outcomeCode" to "INSUFFICIENT_INFORMATION"))
            .post("/api/v1/aml/reports/${reportId()}/withdrawal").then().statusCode(422)
        given().queryParam("state", "FILED").get("/api/v1/aml/reports").then().statusCode(200)
            .body("size()", equalTo(1))
    }

    @Test
    @Order(7)
    fun `(7) each transition wrote one event that carries the report id and the state and nothing else`() {
        val events = rows(
            "SELECT payload FROM aml_outbox WHERE event_type = 'aml.report.state_changed.v1' ORDER BY created_at",
        ).map { ObjectMapper().readTree(it.getString("payload")) }
        assertThat(events.map { it["state"].asText() }).containsExactly("DRAFT", "APPROVED", "FILED")
        assertThat(events).allSatisfy { event ->
            assertThat(event.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder("reportId", "state", "occurredAt")
            assertThat(event["reportId"].asText()).isEqualTo(reportId())
        }
    }

    private companion object {
        val PARTY: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c201")
        val CASE: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c202")
        val OTHER_PARTY: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c203")
        val OTHER_PARTY_CASE: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c204")
        val JOURNAL: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c205")
        val ACCOUNT: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c206")
        val RAIL_CASE: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c207")
    }
}
