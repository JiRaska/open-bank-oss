// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.productcatalog.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.RestAssured.given
import io.restassured.response.Response
import jakarta.inject.Inject
import java.util.UUID
import javax.sql.DataSource

/**
 * Base for the product-catalog E2E suite: the real HTTP surface against real Postgres with the
 * durable facts (catalog_revisions rows, the catalog_audit trail, the catalog_outbox change
 * events) asserted over plain JDBC. Concrete suites carry `@QuarkusTest` +
 * `@TestProfile(CatalogE2EProfile::class)` — the annotations do not inherit, and Quarkus needs
 * them on the concrete class to register the test class as a CDI bean.
 */
abstract class CatalogE2EBase {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var objectMapper: ObjectMapper

    /** POST [path] with a JSON body expecting [expectedStatus]; returns body + the ETag header. */
    protected fun postJson(
        path: String,
        body: String,
        expectedStatus: Int,
        ifMatch: String? = null,
    ): Pair<JsonNode, String?> = parse(
        given().contentType("application/json")
            .apply { if (ifMatch != null) header("If-Match", ifMatch) }
            .body(body)
            .`when`().post(path)
            .then().statusCode(expectedStatus)
            .extract().response(),
    )

    /** PUT with the mandatory If-Match optimistic-lock header. */
    protected fun putJson(path: String, body: String, ifMatch: String, expectedStatus: Int): Pair<JsonNode, String?> =
        parse(
            given().contentType("application/json").header("If-Match", ifMatch).body(body)
                .`when`().put(path)
                .then().statusCode(expectedStatus)
                .extract().response(),
        )

    /** GET [path] expecting [expectedStatus]. */
    protected fun getJson(path: String, expectedStatus: Int): JsonNode = parse(
        given().`when`().get(path)
            .then().statusCode(expectedStatus)
            .extract().response(),
    ).first

    private fun parse(response: Response): Pair<JsonNode, String?> {
        val raw = response.asString()
        val node = if (raw.isBlank()) objectMapper.createObjectNode() else objectMapper.readTree(raw)
        return node to response.header("ETag")
    }

    /** The persisted revision row, or null when the id was never authored. */
    protected fun revisionRow(revisionId: UUID): RevisionRow? = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT offering_id, revision_no, state, maker_id, checker_id, reason, content_hash, " +
                "lock_version FROM catalog_revisions WHERE id = ?",
        ).use { ps ->
            ps.setObject(1, revisionId)
            val rs = ps.executeQuery()
            if (!rs.next()) {
                null
            } else {
                RevisionRow(
                    offeringId = UUID.fromString(rs.getString("offering_id")),
                    revisionNo = rs.getLong("revision_no"),
                    state = rs.getString("state"),
                    makerId = rs.getString("maker_id"),
                    checkerId = rs.getString("checker_id"),
                    reason = rs.getString("reason"),
                    contentHash = rs.getString("content_hash"),
                    lockVersion = rs.getLong("lock_version"),
                )
            }
        }
    }

    /** Every audit-trail entry for an aggregate, in occurrence order — the lifecycle evidence. */
    protected fun auditActions(aggregateId: UUID): List<String> = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT action FROM catalog_audit WHERE aggregate_id = ? ORDER BY occurred_at, id",
        ).use { ps ->
            ps.setObject(1, aggregateId)
            val rs = ps.executeQuery()
            val rows = mutableListOf<String>()
            while (rs.next()) {
                rows.add(rs.getString("action"))
            }
            rows
        }
    }

    /** Change-outbox envelopes for an aggregate; the relay is scheduler-disabled, so PENDING. */
    protected fun outboxEvents(aggregateId: UUID): List<String> = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT event_type FROM catalog_outbox WHERE aggregate_id = ? ORDER BY occurred_at, id",
        ).use { ps ->
            ps.setObject(1, aggregateId)
            val rs = ps.executeQuery()
            val rows = mutableListOf<String>()
            while (rs.next()) {
                rows.add(rs.getString("event_type"))
            }
            rows
        }
    }

    protected data class RevisionRow(
        val offeringId: UUID,
        val revisionNo: Long,
        val state: String,
        val makerId: String,
        val checkerId: String?,
        val reason: String?,
        val contentHash: String?,
        val lockVersion: Long,
    )
}
