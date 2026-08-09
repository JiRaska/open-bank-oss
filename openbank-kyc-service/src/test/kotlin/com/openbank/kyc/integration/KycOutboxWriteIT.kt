// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.integration

import com.openbank.kyc.it.PostgresTestResource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource

/**
 * Issue #4007: `kyc_outbox` had a dispatcher, a backlog gauge and `dispatch-enabled: true`, and
 * NOTHING ever wrote to it — every KYC lifecycle event went out through a direct
 * `@Channel("kyc-events-out")` emitter, outside any transaction, so the state change and the event
 * could diverge in either direction (emit fails after commit: the event is lost with no record;
 * emit succeeds and the transaction rolls back: a consumer acts on something that did not happen).
 * KYC_CASE_APPROVED is the expensive one — it is what activates a party downstream.
 *
 * Only a real-DB integration test can prove the fix. A unit test that mocks the repository cannot
 * tell which publisher a use case called — that is exactly why the defect survived a fully green
 * suite for the life of the service. And the repository cannot be called directly either: a
 * `Panache.withTransaction` reactive repo invoked from a bare `@QuarkusTest` thread throws
 * "No current Vertx context found"; only a real HTTP request carries a Vert.x context. So this
 * drives the REST endpoints with RestAssured and reads the rows back over plain JDBC — the
 * `PartyOutboxWriteIT` / `ConsentRevocationOutboxIT` pattern.
 *
 * The dispatcher is switched off for the duration so it cannot mark a row SENT (or drain it)
 * before the assertion observes it — the claim under test is that the row is WRITTEN in the
 * state-change transaction, not what happens to it afterwards.
 */
@QuarkusTest
@QuarkusTestResource(KycOutboxWriteIT.DispatcherOffResource::class)
@QuarkusTestResource(PostgresTestResource::class)
class KycOutboxWriteIT {

    class DispatcherOffResource : QuarkusTestResourceLifecycleManager {
        // `authz.enforce` off as well: there is no OPA sidecar in a test JVM, and the interceptor
        // correctly fails CLOSED (503) without one. The subject here is the outbox write, not the
        // policy decision — KycSecurityTest owns that.
        override fun start(): Map<String, String> = mapOf(
            "openbank.outbox.dispatch-enabled" to "false",
            "authz.enforce" to "false",
        )
        override fun stop() = Unit
    }

    @Inject
    lateinit var dataSource: DataSource

    private fun openCase(partyId: UUID): UUID {
        val id = Given {
            contentType("application/json")
            body("""{"partyId":"$partyId"}""")
        } When {
            post("/api/v1/kyc/cases")
        } Then {
            statusCode(201)
        } Extract {
            jsonPath().getString("id")
        }
        assertThat(id).isNotBlank()
        return UUID.fromString(id)
    }

    private fun passCheck(caseId: UUID, checkType: String) {
        Given {
            contentType("application/json")
            body("""{"status":"PASSED","result":"outbox-it"}""")
        } When {
            put("/api/v1/kyc/cases/$caseId/checks/$checkType")
        } Then {
            statusCode(200)
        }
    }

    // conn.use closes the connection, cascading to its statement/result-set — kept flat to stay
    // within detekt's NestedBlockDepth.
    private fun outboxRows(aggregateId: UUID): List<Triple<String, String, String>> =
        dataSource.connection.use { conn ->
            val ps = conn.prepareStatement(
                "SELECT event_type, payload, status FROM kyc_outbox WHERE aggregate_id = ? ORDER BY created_at",
            )
            ps.setObject(1, aggregateId)
            val rs = ps.executeQuery()
            val rows = mutableListOf<Triple<String, String, String>>()
            while (rs.next()) {
                rows += Triple(rs.getString("event_type"), rs.getString("payload"), rs.getString("status"))
            }
            rows
        }

    private fun caseStatus(caseId: UUID): String? = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement("SELECT status FROM kyc_cases WHERE case_id = ?")
        ps.setObject(1, caseId)
        val rs = ps.executeQuery()
        if (rs.next()) rs.getString("status") else null
    }

    @Test
    @TestSecurity(user = "outbox-it", roles = ["ROLE_ADMIN"])
    fun `opening a case writes KYC_CASE_OPENED to the outbox in the same transaction as the row`() {
        val caseId = openCase(UUID.randomUUID())

        val rows = outboxRows(caseId)
        assertThat(rows).describedAs("kyc_outbox rows for case %s", caseId).hasSize(1)
        val (eventType, payload, status) = rows.single()
        assertThat(eventType).isEqualTo("KYC_CASE_OPENED")
        assertThat(status).isEqualTo("PENDING")
        // The payload is the same flat envelope consumers already parse (KycEvents) — the outbox
        // changed WHERE the event is written, not WHAT is on the wire.
        assertThat(payload).contains("\"eventType\":\"KYC_CASE_OPENED\"")
        assertThat(payload).contains("\"kycCaseId\":\"$caseId\"")
        assertThat(payload).contains("\"status\":\"OPEN\"")
        assertThat(payload).doesNotContain("\"aggregateId\"")
    }

    @Test
    @TestSecurity(user = "outbox-it", roles = ["ROLE_ADMIN", "ROLE_KYC_REVIEWER"])
    fun `an approval writes KYC_CASE_APPROVED alongside the updated row`() {
        val caseId = openCase(UUID.randomUUID())

        // Drive the case to UNDER_REVIEW the way an operator does — all four checks PASSED.
        listOf("IDENTITY", "ADDRESS", "PEP_SCREENING", "SANCTIONS_SCREENING").forEach { passCheck(caseId, it) }

        Given {
            contentType("application/json")
            body("""{"reason":"All documents verified by the outbox IT"}""")
        } When {
            post("/api/v1/kyc/cases/$caseId/approve")
        } Then {
            statusCode(200)
        }

        // Both halves of the guarantee, asserted against the DB rather than against a mock: the
        // state change landed AND its event landed, in the same commit.
        assertThat(caseStatus(caseId)).describedAs("kyc_cases.status after the approval").isEqualTo("APPROVED")

        val types = outboxRows(caseId).map { it.first }
        assertThat(types).containsExactly("KYC_CASE_OPENED", "KYC_CASE_STATUS_CHANGED", "KYC_CASE_APPROVED")
    }
}
