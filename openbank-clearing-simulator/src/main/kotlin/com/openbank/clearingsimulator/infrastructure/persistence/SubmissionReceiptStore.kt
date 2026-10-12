// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.clearingsimulator.infrastructure.persistence

import com.openbank.clearingsimulator.application.ClearingSimulatorService
import io.agroal.api.AgroalDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.ClientErrorException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection

/** One immutable, committed simulator verdict per rail-scoped submission identity. */
@ApplicationScoped
class SubmissionReceiptStore {
    @Inject
    lateinit var dataSource: AgroalDataSource

    @Inject
    lateinit var simulator: ClearingSimulatorService

    fun submit(rail: String, submissionId: String, xml: String): String {
        if (rail !in RAILS || !SUBMISSION_ID.matches(submissionId)) {
            throw BadRequestException("Invalid rail or submission identity")
        }
        // Fingerprint the exact received bytes. A changed request must never inherit an old
        // acceptance, including changes that leave the parsed ISO 20022 fields equivalent.
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(xml.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val candidate = simulator.clear(xml).statusReportXml

        val receipt = dataSource.connection.use { connection ->
            insertIfAbsent(connection, rail, submissionId, digest, candidate)
            readReceipt(connection, rail, submissionId)
        }
        if (receipt.digest != digest) {
            throw ClientErrorException("Submission identity already used for another request", CONFLICT_STATUS)
        }
        return receipt.response
    }

    private fun insertIfAbsent(connection: Connection, rail: String, id: String, digest: String, response: String) {
        // PostgreSQL's unique index serializes concurrent submissions across every pod.
        // The original response commits before this method returns to HTTP.
        connection.prepareStatement(
            """INSERT INTO clearing_submission_receipt
                (rail, submission_id, request_sha256, status_report_xml)
                VALUES (?, ?, ?, ?) ON CONFLICT (rail, submission_id) DO NOTHING""",
        ).use { statement ->
            statement.setString(1, rail)
            statement.setString(2, id)
            statement.setString(DIGEST_COLUMN, digest)
            statement.setString(RESPONSE_COLUMN, response)
            statement.executeUpdate()
        }
    }

    private fun readReceipt(connection: Connection, rail: String, id: String): Receipt = connection.prepareStatement(
        """SELECT request_sha256, status_report_xml FROM clearing_submission_receipt
               WHERE rail = ? AND submission_id = ?""",
    ).use { statement ->
        statement.setString(1, rail)
        statement.setString(2, id)
        statement.executeQuery().use { result ->
            check(result.next()) { "Committed clearing receipt missing" }
            Receipt(result.getString(1), result.getString(2))
        }
    }

    private data class Receipt(val digest: String, val response: String)

    private companion object {
        const val DIGEST_COLUMN = 3
        const val RESPONSE_COLUMN = 4
        const val CONFLICT_STATUS = 409
        val RAILS = setOf("SEPA_INSTANT", "SEPA_CREDIT", "DOMESTIC", "SWIFT")
        val SUBMISSION_ID = Regex("[A-Za-z0-9._:-]{1,70}")
    }
}
