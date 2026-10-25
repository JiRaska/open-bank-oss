// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyc.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import java.util.UUID
import javax.sql.DataSource

/**
 * Base for the KYC E2E suite: drives the real HTTP API with RestAssured and asserts the durable
 * evidence over plain JDBC, exactly the pattern of `KycOutboxWriteIT` / `PartyOutboxWriteIT`.
 *
 * The outbox dispatcher is off for the suite ([KycE2EProfile]), so every lifecycle event stays a
 * `PENDING` row in `kyc_outbox` — assertable without racing a relay. `authz.enforce` is off too,
 * so `@RolesAllowed` is the only gate in play and each test picks its identity per endpoint.
 * Concrete suites carry `@QuarkusTest` + `@TestProfile(KycE2EProfile::class)` — the annotations
 * do not inherit, and Quarkus needs them on the concrete class to register it as a CDI bean.
 */
abstract class KycE2EBase {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var objectMapper: ObjectMapper

    /** A `kyc_cases` row as the E2E assertions need it. */
    data class CaseRow(
        val status: String,
        val riskLevel: String,
        val reviewedBy: String?,
        val notes: String?,
        val subjectType: String,
    )

    /** The checks of a case as served by `GET /cases/{id}` (`checks[].checkType` / `status`). */
    protected fun checksViaApi(caseId: UUID): Map<String, String> {
        val body = Given { this } When {
            get("/api/v1/kyc/cases/$caseId")
        } Then {
            statusCode(200)
        } Extract {
            asString()
        }
        val checks = objectMapper.readTree(body).get("checks")
        val byType = mutableMapOf<String, String>()
        checks.forEach { byType[it.get("checkType").asText()] = it.get("status").asText() }
        return byType
    }

    protected fun openCase(partyId: UUID): UUID {
        val id = Given {
            contentType("application/json")
            body("""{"partyId":"$partyId"}""")
        } When {
            post("/api/v1/kyc/cases")
        } Then {
            statusCode(201)
        } Extract {
            path<String>("id")
        }
        return UUID.fromString(id)
    }

    protected fun passCheck(caseId: UUID, checkType: String) {
        Given {
            contentType("application/json")
            body("""{"status":"PASSED","result":"e2e-verified"}""")
        } When {
            put("/api/v1/kyc/cases/$caseId/checks/$checkType")
        } Then {
            statusCode(200)
        }
    }

    // conn.use closes the connection, cascading to its statement/result-set — kept flat to stay
    // within detekt's NestedBlockDepth, same as KycOutboxWriteIT.
    protected fun caseRow(caseId: UUID): CaseRow = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement(
            "SELECT status, risk_level, reviewed_by, notes, subject_type FROM kyc_cases WHERE case_id = ?",
        )
        ps.setObject(1, caseId)
        val rs = ps.executeQuery()
        if (!rs.next()) error("no kyc_cases row for $caseId")
        CaseRow(
            status = rs.getString("status"),
            riskLevel = rs.getString("risk_level"),
            reviewedBy = rs.getString("reviewed_by"),
            notes = rs.getString("notes"),
            subjectType = rs.getString("subject_type"),
        )
    }

    protected fun outboxRows(aggregateId: UUID): List<Triple<String, String, String>> =
        dataSource.connection.use { conn ->
            val ps = conn.prepareStatement(
                "SELECT event_type, payload, status FROM kyc_outbox WHERE aggregate_id = ? " +
                    "ORDER BY created_at, id",
            )
            ps.setObject(1, aggregateId)
            val rs = ps.executeQuery()
            val rows = mutableListOf<Triple<String, String, String>>()
            while (rs.next()) {
                rows += Triple(
                    rs.getString("event_type"),
                    rs.getString("payload"),
                    rs.getString("status"),
                )
            }
            rows
        }

    /** Event types persisted for the case, e.g. `["KYC_CASE_OPENED", "KYC_CASE_APPROVED"]`. */
    protected fun outboxEventTypes(aggregateId: UUID): List<String> = outboxRows(aggregateId).map { it.first }

    /** Payload of the single persisted row of [eventType], or null when absent. */
    protected fun outboxPayload(aggregateId: UUID, eventType: String): String? =
        outboxRows(aggregateId).firstOrNull { it.first == eventType }?.second

    /** True once the party has a case row at all (the Kafka journeys poll this). */
    protected fun caseExistsForParty(partyId: UUID): Boolean = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement("SELECT 1 FROM kyc_cases WHERE party_id = ? LIMIT 1")
        ps.setObject(1, partyId)
        ps.executeQuery().next()
    }

    /** Case id of the party's single case; fails the test when there is none (yet). */
    protected fun caseIdForParty(partyId: UUID): UUID = caseIdsForParty(partyId).single()

    /** All case ids of the party — replay tests assert there is exactly one. */
    protected fun caseIdsForParty(partyId: UUID): List<UUID> = dataSource.connection.use { conn ->
        val ps = conn.prepareStatement("SELECT case_id FROM kyc_cases WHERE party_id = ? ORDER BY id")
        ps.setObject(1, partyId)
        val rs = ps.executeQuery()
        val ids = mutableListOf<UUID>()
        while (rs.next()) ids += rs.getObject("case_id", UUID::class.java)
        ids
    }
}
