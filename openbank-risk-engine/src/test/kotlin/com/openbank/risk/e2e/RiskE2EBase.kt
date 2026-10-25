// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.risk.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.restassured.module.kotlin.extensions.Extract
import io.restassured.module.kotlin.extensions.Given
import io.restassured.module.kotlin.extensions.Then
import io.restassured.module.kotlin.extensions.When
import jakarta.inject.Inject
import java.time.LocalDate
import javax.sql.DataSource

/**
 * Base for the risk-engine E2E suite: the real HTTP surface against real Postgres with the durable
 * facts (snapshot_run rows, tie-out mismatches) asserted over plain JDBC. Concrete suites carry
 * `@QuarkusTest` + `@TestProfile(RiskE2EProfile::class)` — the annotations do not inherit, and
 * Quarkus needs them on the concrete class to register the test class as a CDI bean.
 */
abstract class RiskE2EBase {

    @Inject
    lateinit var dataSource: DataSource

    @Inject
    lateinit var objectMapper: ObjectMapper

    /** POST /api/v1/risk/snapshots expecting [expectedStatus]; 201/200 both carry the run body. */
    protected fun createSnapshot(asOf: String, expectedStatus: Int): JsonNode = parse(
        Given {
            contentType("application/json")
        } When {
            body("""{"asOf":"$asOf"}""")
            post("/api/v1/risk/snapshots")
        } Then {
            statusCode(expectedStatus)
        } Extract { asString() },
    )

    /** POST with a caller-controlled raw body (validation journeys send missing/malformed asOf). */
    protected fun createSnapshotRaw(body: String, expectedStatus: Int): JsonNode = parse(
        Given {
            contentType("application/json")
        } When {
            body(body)
            post("/api/v1/risk/snapshots")
        } Then {
            statusCode(expectedStatus)
        } Extract { asString() },
    )

    /** GET [path] expecting [expectedStatus]. */
    protected fun getJson(path: String, expectedStatus: Int): JsonNode = parse(
        Given { this } When { get(path) } Then {
            statusCode(expectedStatus)
        } Extract { asString() },
    )

    private fun parse(raw: String): JsonNode =
        if (raw.isBlank()) objectMapper.createObjectNode() else objectMapper.readTree(raw)

    /** Every snapshot_run for an as-of day, newest first — replay journeys assert the count. */
    protected fun runRows(asOf: String): List<RunRow> = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT id, status, position_count, mismatch_count, input_hash, requested_by " +
                "FROM snapshot_run WHERE as_of = ? ORDER BY recorded_at DESC",
        ).use { ps ->
            ps.setObject(1, LocalDate.parse(asOf))
            val rs = ps.executeQuery()
            val rows = mutableListOf<RunRow>()
            while (rs.next()) {
                rows.add(
                    RunRow(
                        id = rs.getString("id"),
                        status = rs.getString("status"),
                        positionCount = rs.getInt("position_count"),
                        mismatchCount = rs.getInt("mismatch_count"),
                        inputHash = rs.getString("input_hash"),
                        requestedBy = rs.getString("requested_by"),
                    ),
                )
            }
            rows
        }
    }

    /** The tie-out mismatches persisted for a run — the UNTIED journey reads them back. */
    protected fun mismatchRows(runId: String): List<MismatchRow> = dataSource.connection.use { conn ->
        conn.prepareStatement(
            "SELECT gl_account_code, currency, ledger_net, positions_net " +
                "FROM snapshot_tie_out_mismatch WHERE run_id = ? ORDER BY id",
        ).use { ps ->
            ps.setObject(1, java.util.UUID.fromString(runId))
            val rs = ps.executeQuery()
            val rows = mutableListOf<MismatchRow>()
            while (rs.next()) {
                rows.add(
                    MismatchRow(
                        glAccountCode = rs.getString("gl_account_code"),
                        currency = rs.getString("currency"),
                        ledgerNet = rs.getBigDecimal("ledger_net"),
                        positionsNet = rs.getBigDecimal("positions_net"),
                    ),
                )
            }
            rows
        }
    }

    /** Positions of a run — proves the position fan-out persisted what the tie-out compared. */
    protected fun positionCount(runId: String): Int = dataSource.connection.use { conn ->
        conn.prepareStatement("SELECT count(*) FROM snapshot_position WHERE run_id = ?").use { ps ->
            ps.setObject(1, java.util.UUID.fromString(runId))
            val rs = ps.executeQuery()
            rs.next()
            rs.getLong(1).toInt()
        }
    }

    protected data class RunRow(
        val id: String,
        val status: String,
        val positionCount: Int,
        val mismatchCount: Int,
        val inputHash: String,
        val requestedBy: String?,
    )

    protected data class MismatchRow(
        val glAccountCode: String?,
        val currency: String,
        val ledgerNet: java.math.BigDecimal,
        val positionsNet: java.math.BigDecimal,
    )
}
