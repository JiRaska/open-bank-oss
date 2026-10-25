// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.vop.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import java.security.MessageDigest
import java.time.Instant
import javax.sql.DataSource

/**
 * Base for the vop E2E suite: the real HTTP surface against real Postgres with the durable
 * evidence rows asserted over plain JDBC. Concrete suites carry `@QuarkusTest` +
 * `@TestProfile(VopE2EProfile::class)` — the annotations do not inherit, and Quarkus needs them on
 * the concrete class to register the test class as a CDI bean.
 */
abstract class VopE2EBase {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var objectMapper: ObjectMapper

    /** POST /api/v1/vop/verify with [body] expecting [expectedStatus]. */
    protected fun verify(body: Map<String, Any?>, expectedStatus: Int): JsonNode = parse(
        Given {
            contentType("application/json")
            body(body)
        } When { post("/api/v1/vop/verify") } Then {
            statusCode(expectedStatus)
        } Extract { asString() },
    )

    /** The evidence row recorded for this exact iban/name pair, or null when nothing was recorded. */
    protected fun evidenceRow(iban: String, suppliedName: String): EvidenceRow? = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT outcome, no_data_reason, requested_by, verified_at FROM vop_verification " +
                "WHERE iban_hash = ? AND supplied_name_hash = ?",
        ).use { ps ->
            ps.setString(1, sha256(iban))
            ps.setString(2, sha256(suppliedName))
            val rs = ps.executeQuery()
            if (rs.next()) {
                mapRow(rs.getString("outcome"), rs.getString("no_data_reason"), rs)
            } else {
                null
            }
        }
    }

    /** How many evidence rows carry [outcome] — the throttle journey asserts refusals record nothing. */
    protected fun evidenceCountFor(outcome: String, requestedBy: String): Int = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT count(*) FROM vop_verification WHERE outcome = ? AND requested_by = ?",
        ).use { ps ->
            ps.setString(1, outcome)
            ps.setString(2, requestedBy)
            val rs = ps.executeQuery()
            rs.next()
            rs.getLong(1).toInt()
        }
    }

    private fun mapRow(outcome: String, noDataReason: String?, rs: java.sql.ResultSet): EvidenceRow = EvidenceRow(
        outcome = outcome,
        noDataReason = noDataReason,
        requestedBy = rs.getString("requested_by"),
        verifiedAt = rs.getTimestamp("verified_at").toInstant(),
    )

    private fun parse(raw: String): JsonNode =
        if (raw.isBlank()) objectMapper.createObjectNode() else objectMapper.readTree(raw)

    /** The same hash the service stores: SHA-256 over the trimmed, lowercased value (ADR-0171 §7). */
    protected fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.trim().lowercase().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** The evidence record the verification must have committed (ADR-0171 §7, GDPR-hashed). */
    data class EvidenceRow(
        val outcome: String,
        val noDataReason: String?,
        val requestedBy: String,
        val verifiedAt: Instant,
    )
}
