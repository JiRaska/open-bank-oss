// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.infrastructure.persistence

import com.openbank.casecoordinator.application.port.out.CancellableCase
import com.openbank.casecoordinator.application.port.out.CaseKillSwitchStatePort
import com.openbank.casecoordinator.application.port.out.KillSwitchCommand
import jakarta.enterprise.context.ApplicationScoped
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@ApplicationScoped
class JdbcCaseKillSwitchStateAdapter(private val dataSource: DataSource) : CaseKillSwitchStatePort {
    override fun apply(command: KillSwitchCommand) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO case_kill_switch (scope, reason, set_by, source_event_id, set_at, removed_at)
                VALUES (?, ?, ?, ?, ?, NULL)
                ON CONFLICT (scope) DO UPDATE SET
                    reason=EXCLUDED.reason,
                    set_by=EXCLUDED.set_by,
                    source_event_id=EXCLUDED.source_event_id,
                    set_at=EXCLUDED.set_at,
                    removed_at=NULL
                WHERE case_kill_switch.removed_at IS NULL
                   OR case_kill_switch.set_at < EXCLUDED.set_at
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, command.scope)
                statement.setString(P2, command.reason)
                statement.setString(P3, command.setBy)
                statement.setObject(P4, UUID.fromString(command.eventId))
                statement.setTimestamp(P5, Timestamp.from(command.occurredAt))
                statement.executeUpdate()
            }
        }
    }

    override fun clear(scope: String, clearedAt: Instant) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE case_kill_switch
                SET removed_at = ?
                WHERE scope = ? AND removed_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(clearedAt))
                statement.setString(2, scope)
                statement.executeUpdate()
            }
        }
    }

    @Suppress("NestedBlockDepth")
    override fun pilotHaltReason(): String? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT scope, reason FROM case_kill_switch WHERE scope IN ('*', 'rca-investigator') AND removed_at IS NULL ORDER BY scope LIMIT 1",
        ).use { statement ->
            statement.executeQuery().use { result ->
                if (result.next()) "kill switch ${result.getString(1)}: ${result.getString(2)}" else null
            }
        }
    }

    override fun cancellableCases(): List<CancellableCase> = dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            SELECT workflow_id FROM case_workflow
            WHERE case_class = 'INCIDENT_RESPONSE' AND delivery_mode = 'SHADOW'
              AND status IN ('OPEN', 'CONVERGING', 'CONTESTED')
            ORDER BY opened_at
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(CancellableCase(result.getString(1))) }
            }
        }
    }

    // Every failure between the status update and evidence insert must roll back both writes.
    @Suppress("NestedBlockDepth", "TooGenericExceptionCaught")
    override fun recordHalted(caseId: String, command: KillSwitchCommand) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val caseUuid = UUID.nameUUIDFromBytes(caseId.toByteArray(StandardCharsets.UTF_8))
                connection.prepareStatement(
                    """
                    UPDATE case_workflow SET status='HALTED', halted_by=?, halted_at=?, halt_reason=?, halt_scope=?
                    WHERE id=? AND status IN ('OPEN', 'CONVERGING', 'CONTESTED')
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(P1, command.setBy)
                    statement.setTimestamp(P2, Timestamp.from(command.occurredAt))
                    statement.setString(P3, command.reason)
                    statement.setString(P4, command.scope)
                    statement.setObject(P5, caseUuid)
                    if (statement.executeUpdate() == 0) {
                        connection.rollback()
                        return
                    }
                }
                connection.prepareStatement(
                    """
                    INSERT INTO case_signal_evidence
                      (signal_id, case_id, agent_id, authenticated_principal, capability, stage,
                       observed_at, rollout_id, policy_decision_id, policy_reason)
                    VALUES (?, ?, 'rca-investigator', ?, 'case.halt', 'HALTED', ?, NULL, ?, ?)
                    ON CONFLICT (signal_id, stage) DO NOTHING
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(P1, UUID.fromString(command.eventId))
                    statement.setObject(P2, caseUuid)
                    statement.setString(P3, command.setBy)
                    statement.setTimestamp(P4, Timestamp.from(command.occurredAt))
                    statement.setString(P5, command.eventId)
                    statement.setString(P6, command.reason)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            }
        }
    }

    private companion object {
        const val P1 = 1
        const val P2 = 2
        const val P3 = 3
        const val P4 = 4
        const val P5 = 5
        const val P6 = 6
    }
}
