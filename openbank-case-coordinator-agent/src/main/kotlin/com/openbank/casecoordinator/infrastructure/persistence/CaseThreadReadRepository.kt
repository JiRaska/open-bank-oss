// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.openbank.casecoordinator.domain.model.CaseRow
import com.openbank.casecoordinator.domain.model.ContributionRow
import com.openbank.casecoordinator.domain.model.ProposalEventRow
import jakarta.enterprise.context.ApplicationScoped
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

/**
 * JDBC read side of the case thread model. Read-only; called from `@Blocking` REST endpoints, so
 * no Vert.x context is required (unlike the reactive Panache write side, which cannot run there —
 * see CaseActivitiesImpl for the same split on the Temporal worker threads).
 */
@ApplicationScoped
class CaseThreadReadRepository(private val dataSource: DataSource, private val objectMapper: ObjectMapper) {

    fun listCases(status: String?, limit: Int): List<CaseRow> {
        val where = if (status == null) "" else " WHERE w.status = ?"
        return query(
            CASE_SELECT + where + " ORDER BY w.opened_at DESC LIMIT ?",
            { ps ->
                var p = P1
                if (status != null) ps.setString(p++, status)
                ps.setInt(p, limit)
            },
            { rs -> rs.toCaseRow() },
        )
    }

    fun findCase(workflowId: String): CaseRow? = query(
        "$CASE_SELECT WHERE w.workflow_id = ?",
        { ps -> ps.setString(P1, workflowId) },
        { rs -> rs.toCaseRow() },
    ).firstOrNull()

    fun listContributions(workflowId: String): List<ContributionRow> = query(
        """
        SELECT c.agent_id, c.contributed_at, c.summary, c.evidence_refs,
               c.draft_version, c.preemption_vote, c.contested
        FROM case_contribution c
        JOIN case_workflow w ON w.id = c.case_id
        WHERE w.workflow_id = ?
        ORDER BY c.contributed_at, c.id
        """.trimIndent(),
        { ps -> ps.setString(P1, workflowId) },
        { rs -> rs.toContributionRow() },
    )

    fun listProposalEvents(workflowId: String): List<ProposalEventRow> = query(
        """
        SELECT o.event_id, o.event_type, o.created_at
        FROM case_outbox o
        JOIN case_workflow w ON w.id = o.aggregate_id
        WHERE w.workflow_id = ?
        ORDER BY o.created_at
        """.trimIndent(),
        { ps -> ps.setString(P1, workflowId) },
        { rs ->
            ProposalEventRow(
                proposalId = rs.getObject("event_id", UUID::class.java).toString(),
                proposalType = rs.getString("event_type"),
                emittedAtEpochMs = rs.getTimestamp("created_at").toInstant().toEpochMilli(),
            )
        },
    )

    private fun <T> query(sql: String, bind: (PreparedStatement) -> Unit, mapRow: (ResultSet) -> T): List<T> =
        dataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { ps ->
                bind(ps)
                ps.executeQuery().use { rs -> collectRows(rs, mapRow) }
            }
        }

    private fun <T> collectRows(rs: ResultSet, mapRow: (ResultSet) -> T): List<T> {
        val rows = mutableListOf<T>()
        while (rs.next()) rows.add(mapRow(rs))
        return rows
    }

    private fun ResultSet.toCaseRow(): CaseRow = CaseRow(
        workflowId = getString("workflow_id"),
        caseClass = getString("case_class"),
        dispositionTarget = getString("disposition_target"),
        status = getString("status"),
        openedAtEpochMs = getTimestamp("opened_at").toInstant().toEpochMilli(),
        deadlineAtEpochMs = getTimestamp("deadline_at").toInstant().toEpochMilli(),
        contestedRate = getBigDecimal("contested_rate").toDouble(),
        contributionCount = getInt("contribution_count"),
    )

    private fun ResultSet.toContributionRow(): ContributionRow {
        val refsJson = getString("evidence_refs")
        return ContributionRow(
            agentId = getString("agent_id"),
            contributedAtEpochMs = getTimestamp("contributed_at").toInstant().toEpochMilli(),
            summary = getString("summary"),
            evidenceRefs = if (refsJson == null) emptyList() else objectMapper.readValue(refsJson),
            draftVersion = getObject("draft_version") as? Int,
            superseded = getString("preemption_vote") == SUPERSEDED_VOTE,
            contested = getBoolean("contested"),
        )
    }

    private companion object {
        const val P1 = 1

        const val CASE_SELECT = """
            SELECT w.workflow_id, w.case_class, w.disposition_target, w.status,
                   w.opened_at, w.deadline_at, w.contested_rate,
                   (SELECT COUNT(*) FROM case_contribution c WHERE c.case_id = w.id) AS contribution_count
            FROM case_workflow w
        """

        /** Written by CaseActivitiesImpl.recordContributions for contributions on a superseded draft (D5). */
        const val SUPERSEDED_VOTE = "SUPERSEDED"
    }
}
