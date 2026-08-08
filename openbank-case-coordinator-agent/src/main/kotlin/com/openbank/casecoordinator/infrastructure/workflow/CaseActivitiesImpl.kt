// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.infrastructure.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.casecoordinator.application.port.out.CaseCoordinatorLlmPort
import com.openbank.casecoordinator.application.workflow.CasePersistenceActivity
import com.openbank.casecoordinator.application.workflow.CaseProposalActivity
import com.openbank.casecoordinator.application.workflow.CaseSynthesisActivity
import com.openbank.casecoordinator.domain.model.CaseStart
import com.openbank.casecoordinator.domain.model.Contribution
import com.openbank.libs.persistence.outbox.OutboxStatus
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.runBlocking
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Case workflow activities. Plain JDBC (Agroal) rather than reactive Panache: Temporal activity
 * threads are plain worker threads with no Vert.x context, and reactive Panache would throw
 * HR000068 there — the same reason agent-service keeps JdbcAgentProposalRepository. The outbox
 * DISPATCH side (CaseOutboxDispatcher) runs on the Quarkus scheduler with a Vert.x context and
 * reads through the Panache entity as usual.
 */
@ApplicationScoped
class CaseActivitiesImpl(
    private val llm: CaseCoordinatorLlmPort,
    private val dataSource: DataSource,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) : CaseSynthesisActivity,
    CaseProposalActivity,
    CasePersistenceActivity {

    override fun synthesize(caseId: String, caseClass: String, contributions: List<Contribution>): String? {
        val context = buildString {
            append("case_id=").append(caseId).append("; class=").append(caseClass).append('\n')
            contributions.forEachIndexed { i, c ->
                append("--- contribution ").append(i + 1).append(" by ").append(c.agentId)
                if (c.contested) append(" [CONTESTED]")
                append(" ---\n").append(c.summary).append('\n')
                if (c.evidenceRefs.isNotEmpty()) {
                    append(
                        "evidence: ",
                    ).append(c.evidenceRefs.joinToString(", ")).append('\n')
                }
            }
        }
        return runBlocking { llm.synthesizeConvergence(context) }
    }

    override fun emitProposal(caseId: String, proposalType: String, summary: String, contested: Boolean): String {
        val eventId = UUID.randomUUID()
        val now = Instant.now(clock)
        val payload = objectMapper.writeValueAsString(
            mapOf(
                "caseId" to caseId,
                "proposalType" to proposalType,
                "summary" to summary,
                "contested" to contested,
                "occurredAt" to now.toString(),
            ),
        )
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO case_outbox
                    (event_id, aggregate_id, event_type, payload, status, attempt_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(P1, eventId)
                ps.setObject(P2, caseUuid(caseId))
                ps.setString(P3, proposalType)
                ps.setString(P4, payload)
                ps.setString(P5, OutboxStatus.PENDING.name)
                ps.setTimestamp(P6, Timestamp.from(now))
                ps.setTimestamp(P7, Timestamp.from(now))
                ps.executeUpdate()
            }
        }
        return eventId.toString()
    }

    override fun recordCaseOpened(start: CaseStart, openedAtEpochMs: Long) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO case_workflow
                    (id, case_class, disposition_target, opened_at, deadline_at, status,
                     budget_tokens, budget_contributions, contested_rate)
                VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, 0.0)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(P1, caseUuid(start.caseId))
                ps.setString(P2, start.caseClass.name)
                ps.setString(P3, start.dispositionTarget)
                ps.setTimestamp(P4, Timestamp.from(Instant.ofEpochMilli(openedAtEpochMs)))
                ps.setTimestamp(P5, Timestamp.from(Instant.ofEpochMilli(start.deadlineEpochMs)))
                ps.setInt(P6, TOKENS_PER_CASE)
                ps.setInt(P7, start.maxContributions)
                ps.executeUpdate()
            }
        }
    }

    override fun recordContributions(caseId: String, contributions: List<Contribution>) {
        if (contributions.isEmpty()) return
        val finalDraft = contributions.maxOf { it.draftVersion }
        val now = Timestamp.from(Instant.now(clock))
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO case_contribution (id, case_id, agent_id, contributed_at, tokens_used, preemption_vote)
                VALUES (?, ?, ?, ?, 0, ?)
                """.trimIndent(),
            ).use { ps ->
                contributions.forEach { c ->
                    ps.setObject(P1, UUID.randomUUID())
                    ps.setObject(P2, caseUuid(caseId))
                    ps.setString(P3, c.agentId)
                    ps.setTimestamp(P4, now)
                    // A contribution on a superseded draft is recorded history (D5), flagged so
                    // the Phase 2 thread view can render the fork instead of a flat list.
                    ps.setString(P5, if (c.draftVersion < finalDraft) "SUPERSEDED" else null)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            val contested = contributions.count { it.contested }
            conn.prepareStatement(
                "UPDATE case_workflow SET contested_rate = ? WHERE id = ?",
            ).use { ps ->
                ps.setBigDecimal(P1, (contested.toDouble() / contributions.size).toBigDecimal())
                ps.setObject(P2, caseUuid(caseId))
                ps.executeUpdate()
            }
        }
    }

    override fun recordCaseClosed(caseId: String, status: String, closedAtEpochMs: Long) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("UPDATE case_workflow SET status = ? WHERE id = ?").use { ps ->
                ps.setString(P1, status)
                ps.setObject(P2, caseUuid(caseId))
                ps.executeUpdate()
            }
        }
    }

    private companion object {
        /** incident-response budget from agents.yaml case_classes; token accounting lands with evals. */
        const val TOKENS_PER_CASE = 200_000

        const val P1 = 1
        const val P2 = 2
        const val P3 = 3
        const val P4 = 4
        const val P5 = 5
        const val P6 = 6
        const val P7 = 7

        /** Deterministic correlation UUID for a workflow id — the V1 schema keys on UUID. */
        fun caseUuid(caseId: String): UUID {
            val bytes = caseId.toByteArray(StandardCharsets.UTF_8)
            return UUID.nameUUIDFromBytes(bytes)
        }
    }
}
