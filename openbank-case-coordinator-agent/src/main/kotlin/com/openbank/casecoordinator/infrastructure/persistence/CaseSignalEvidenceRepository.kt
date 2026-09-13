// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.persistence

import com.openbank.casecoordinator.domain.model.CaseSignalEvidence
import jakarta.enterprise.context.ApplicationScoped
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

data class CaseAuthorizationContext(val caseClass: String, val deliveryMode: String)

@ApplicationScoped
class CaseSignalEvidenceRepository(private val dataSource: DataSource) {
    fun findContext(caseId: String): CaseAuthorizationContext? =
        dataSource.connection.use { connection -> findContext(connection, caseId) }

    private fun findContext(connection: Connection, caseId: String): CaseAuthorizationContext? =
        connection.prepareStatement(
            "SELECT case_class, delivery_mode FROM case_workflow WHERE workflow_id = ?",
        ).use { ps ->
            ps.setString(P1, caseId)
            readContext(ps)
        }

    private fun readContext(statement: PreparedStatement): CaseAuthorizationContext? =
        statement.executeQuery().use { rs ->
            if (rs.next()) CaseAuthorizationContext(rs.getString(P1), rs.getString(P2)) else null
        }

    fun record(evidence: CaseSignalEvidence) {
        dataSource.connection.use { connection -> insert(connection, evidence) }
    }

    /** Locks the case row so concurrent authorizations cannot cross the policy quota. */
    fun tryRecordAuthorized(evidence: CaseSignalEvidence, maxSignalsPerCase: Int): Boolean {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            return try {
                if (!lockCase(connection, evidence.caseId)) {
                    connection.rollback()
                    return false
                }
                if (countAuthorized(connection, evidence.caseId, evidence.agentId) >= maxSignalsPerCase) {
                    connection.rollback()
                    false
                } else {
                    insert(connection, evidence)
                    connection.commit()
                    true
                }
            } catch (failure: SQLException) {
                connection.rollback()
                throw failure
            }
        }
    }

    private fun lockCase(connection: Connection, caseId: String): Boolean =
        connection.prepareStatement("SELECT id FROM case_workflow WHERE id = ? FOR UPDATE").use { statement ->
            statement.setObject(P1, caseUuid(caseId))
            statement.executeQuery().use { it.next() }
        }

    private fun countAuthorized(connection: Connection, caseId: String, agentId: String): Int =
        connection.prepareStatement(
            """
            SELECT COUNT(*) FROM case_signal_evidence
            WHERE case_id = ? AND agent_id = ? AND stage = 'AUTHORIZED'
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(P1, caseUuid(caseId))
            ps.setString(P2, agentId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(P1)
            }
        }

    private fun insert(connection: Connection, evidence: CaseSignalEvidence) {
        connection.prepareStatement(
            """
            INSERT INTO case_signal_evidence
                (signal_id, case_id, agent_id, capability, stage, observed_at,
                 rollout_id, policy_decision_id, policy_reason)
            VALUES (?, ?, ?, ?, ?, ?, NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''))
            ON CONFLICT (signal_id, stage) DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(P1, UUID.fromString(evidence.signalId))
            ps.setObject(P2, caseUuid(evidence.caseId))
            ps.setString(P3, evidence.agentId)
            ps.setString(P4, evidence.capability)
            ps.setString(P5, evidence.stage.name)
            ps.setTimestamp(P6, Timestamp.from(Instant.ofEpochMilli(evidence.observedAtEpochMs)))
            ps.setString(P7, evidence.rolloutId)
            ps.setString(P8, evidence.policyDecisionId)
            ps.setString(P9, evidence.policyReason)
            ps.executeUpdate()
        }
    }

    private fun caseUuid(caseId: String): UUID = UUID.nameUUIDFromBytes(caseId.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val P1 = 1
        const val P2 = 2
        const val P3 = 3
        const val P4 = 4
        const val P5 = 5
        const val P6 = 6
        const val P7 = 7
        const val P8 = 8
        const val P9 = 9
    }
}
