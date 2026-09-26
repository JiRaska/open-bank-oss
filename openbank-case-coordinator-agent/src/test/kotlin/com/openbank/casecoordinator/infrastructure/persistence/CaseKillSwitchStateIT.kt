// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.infrastructure.persistence

import com.openbank.casecoordinator.PostgresTestResource
import com.openbank.casecoordinator.application.port.out.KillSwitchCommand
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class CaseKillSwitchStateIT {
    @Inject lateinit var state: JdbcCaseKillSwitchStateAdapter

    @Inject lateinit var dataSource: DataSource

    @BeforeEach
    fun cleanTables() {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM case_kill_switch").executeUpdate()
            connection.prepareStatement("DELETE FROM case_signal_evidence").executeUpdate()
            connection.prepareStatement("DELETE FROM case_workflow").executeUpdate()
        }
    }

    @Test
    fun `halt records no-action evidence without creating a proposal outbox row`() {
        val caseId = "case-kill-switch-it"
        val caseUuid = UUID.nameUUIDFromBytes(caseId.toByteArray(StandardCharsets.UTF_8))
        seedCase(caseId, caseUuid)
        val command = KillSwitchCommand(
            eventId = "22222222-2222-2222-2222-222222222222",
            operation = "agent.killswitch.set",
            scope = "rca-investigator",
            reason = "incident containment",
            setBy = "admin-1",
            occurredAt = Instant.parse("2026-09-22T12:00:00Z"),
        )

        state.apply(command)
        assertThat(state.pilotHaltReason()).contains("incident containment")
        assertThat(state.cancellableCases()).extracting<String> { it.workflowId }.contains(caseId)
        state.recordHalted(caseId, command)

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT status, halted_by, halt_reason FROM case_workflow WHERE id = ?",
            ).use { statement ->
                statement.setObject(1, caseUuid)
                statement.executeQuery().use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getString("status")).isEqualTo("HALTED")
                    assertThat(result.getString("halted_by")).isEqualTo("admin-1")
                    assertThat(result.getString("halt_reason")).isEqualTo("incident containment")
                }
            }
            connection.prepareStatement(
                "SELECT authenticated_principal, stage FROM case_signal_evidence WHERE case_id = ?",
            ).use { statement ->
                statement.setObject(1, caseUuid)
                statement.executeQuery().use { result ->
                    assertThat(result.next()).isTrue()
                    assertThat(result.getString("authenticated_principal")).isEqualTo("admin-1")
                    assertThat(result.getString("stage")).isEqualTo("HALTED")
                }
            }
            connection.prepareStatement("SELECT COUNT(*) FROM case_outbox WHERE aggregate_id = ?").use { statement ->
                statement.setObject(1, caseUuid)
                statement.executeQuery().use { result ->
                    result.next()
                    assertThat(result.getInt(1)).isZero()
                }
            }
        }
    }

    @Test
    fun `clear is monotonic and stale set replay cannot resurrect a cleared halt`() {
        val set = KillSwitchCommand(
            eventId = "33333333-3333-3333-3333-333333333333",
            operation = "agent.killswitch.set",
            scope = "rca-investigator",
            reason = "containment",
            setBy = "admin-1",
            occurredAt = Instant.parse("2026-09-22T12:00:00Z"),
        )
        val clear = KillSwitchCommand(
            eventId = "44444444-4444-4444-4444-444444444444",
            operation = "agent.killswitch.cleared",
            scope = "rca-investigator",
            reason = "",
            setBy = "admin-1",
            occurredAt = Instant.parse("2026-09-22T13:00:00Z"),
        )
        val staleSet = set.copy(eventId = "55555555-5555-5555-5555-555555555555")

        state.apply(set)
        state.clear(set.scope, clear.occurredAt)
        assertThat(state.pilotHaltReason()).isNull()

        state.apply(staleSet)
        assertThat(state.pilotHaltReason()).isNull()
    }

    private fun seedCase(caseId: String, caseUuid: UUID) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO case_workflow
                  (id, workflow_id, case_class, delivery_mode, disposition_target, opened_at,
                   deadline_at, status, budget_tokens, budget_contributions, contested_rate)
                VALUES (?, ?, 'INCIDENT_RESPONSE', 'SHADOW', 'alert:test', NOW(), NOW() + INTERVAL '20 minutes',
                        'OPEN', 200000, 40, 0.0)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, caseUuid)
                statement.setString(2, caseId)
                statement.executeUpdate()
            }
        }
    }
}
