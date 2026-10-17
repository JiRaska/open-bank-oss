// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.agent.infrastructure.persistence

import com.openbank.agent.application.port.out.AgentMergeRecordRepository
import com.openbank.agent.domain.mergerecord.AgentMergeRecord
import jakarta.enterprise.context.ApplicationScoped
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import javax.sql.DataSource

/**
 * Plain Agroal JDBC adapter for [AgentMergeRecordRepository] (`agent_merge_record`, V6 migration).
 * Sync JDBC for the same reason as [JdbcAgentProposalRepository] — this service cannot drive
 * reactive Panache and the ingest path is a synchronous webhook anyway.
 *
 * Deduplication is a single statement: `ON CONFLICT (pr_number) DO NOTHING` — the GitHub webhook
 * can redeliver, and a redelivery must surface the already-stored row rather than fail or
 * overwrite it. No read-modify-write anywhere: the guard and the write are one statement, so two
 * concurrent deliveries of the same PR can never both report `inserted = true`.
 */
@ApplicationScoped
class JdbcAgentMergeRecordRepository(private val dataSource: DataSource) : AgentMergeRecordRepository {

    @Suppress("MagicNumber") // positional JDBC bind indexes
    override fun insertOrGetExisting(record: AgentMergeRecord): AgentMergeRecordRepository.InsertResult {
        val inserted = dataSource.connection.use { c ->
            c.prepareStatement(
                """
                INSERT INTO agent_merge_record
                  (id, agent_id, pr_number, pr_url, head_branch, merged_by, merged_at, title, proposal_id, recorded_at, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (pr_number) DO NOTHING
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, record.id)
                ps.setString(2, record.agentId)
                ps.setLong(3, record.prNumber)
                ps.setString(4, record.prUrl)
                ps.setString(5, record.headBranch)
                ps.setString(6, record.mergedBy)
                ps.setTimestamp(7, Timestamp.from(record.mergedAt))
                ps.setString(8, record.title)
                ps.setObject(9, record.proposalId)
                ps.setTimestamp(10, Timestamp.from(record.recordedAt))
                ps.setString(11, record.source)
                ps.executeUpdate()
            }
        }
        if (inserted > 0) return AgentMergeRecordRepository.InsertResult(record, inserted = true)
        // Redelivery: the row already exists. Fetch it so the caller can return the stored
        // record verbatim (title/merged_by are NOT overwritten by the duplicate delivery).
        val existing = findByPrNumber(record.prNumber)
            ?: error("ON CONFLICT missed for pr_number=${record.prNumber} yet no row is stored")
        return AgentMergeRecordRepository.InsertResult(existing, inserted = false)
    }

    override fun list(agentId: String?): List<AgentMergeRecord> = dataSource.connection.use { c ->
        c.prepareStatement(
            "SELECT * FROM agent_merge_record" +
                (if (agentId != null) " WHERE agent_id = ?" else "") +
                " ORDER BY merged_at DESC",
        ).use { ps ->
            agentId?.let { ps.setString(1, it) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toMergeRecord()) } }
        }
    }

    private fun findByPrNumber(prNumber: Long): AgentMergeRecord? = dataSource.connection.use { c ->
        c.prepareStatement("SELECT * FROM agent_merge_record WHERE pr_number = ?").use { ps ->
            ps.setLong(1, prNumber)
            ps.executeQuery().use { rs -> rs.firstMergeRecordOrNull() }
        }
    }

    private fun ResultSet.firstMergeRecordOrNull(): AgentMergeRecord? = if (next()) toMergeRecord() else null

    private fun ResultSet.toMergeRecord() = AgentMergeRecord(
        id = getObject("id", UUID::class.java),
        agentId = getString("agent_id"),
        prNumber = getLong("pr_number"),
        prUrl = getString("pr_url"),
        headBranch = getString("head_branch"),
        mergedBy = getString("merged_by"),
        mergedAt = getTimestamp("merged_at").toInstant(),
        title = getString("title"),
        proposalId = getObject("proposal_id", UUID::class.java),
        recordedAt = getTimestamp("recorded_at").toInstant(),
        source = getString("source"),
    )
}
