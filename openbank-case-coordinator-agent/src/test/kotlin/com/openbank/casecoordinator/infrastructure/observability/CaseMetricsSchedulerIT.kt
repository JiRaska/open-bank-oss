// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.infrastructure.observability

import com.openbank.casecoordinator.PostgresTestResource
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class CaseMetricsSchedulerIT {

    @Inject
    lateinit var scheduler: CaseMetricsScheduler

    @Inject
    lateinit var registry: MeterRegistry

    @Inject
    lateinit var dataSource: DataSource

    @BeforeEach
    fun clean() {
        dataSource.connection.use { conn ->
            conn.prepareStatement("DELETE FROM case_signal_evidence").executeUpdate()
            conn.prepareStatement("DELETE FROM case_workflow").executeUpdate()
        }
    }

    @Test
    fun `refreshActiveCases publishes current case counts grouped by class and status`(): Unit = runBlocking {
        val now = Instant.parse("2026-09-22T10:00:00Z")
        insertCase("case-open-1", now, "OPEN")
        insertCase("case-open-2", now, "OPEN")
        insertCase("case-contested-1", now, "CONTESTED")

        scheduler.refreshActiveCases()

        val openValue = registry.get("openbank.casecoordinator.active_cases")
            .tag("status", "open")
            .tag("caseClass", "incident_response")
            .gauge()
            .value()
            .toLong()
        val contestedValue = registry.get("openbank.casecoordinator.active_cases")
            .tag("status", "contested")
            .tag("caseClass", "incident_response")
            .gauge()
            .value()
            .toLong()
        assertThat(openValue).isEqualTo(2)
        assertThat(contestedValue).isEqualTo(1)

        assertThat(
            registry.find("openbank.workflow.last_success.age_seconds")
                .gauges()
                .any { it.id.getTag("workflow") == "case-coordinator-active-cases-refresh" },
        ).isTrue()
        Unit
    }

    private fun insertCase(workflowId: String, openedAt: Instant, status: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO case_workflow
                    (id, workflow_id, case_class, delivery_mode, disposition_target, opened_at, deadline_at, status,
                     budget_tokens, budget_contributions, contested_rate)
                VALUES (?, ?, 'INCIDENT_RESPONSE', 'SHADOW', 'alert-1', ?, ?, ?, 1000, 10, 0.0)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, UUID.nameUUIDFromBytes(workflowId.toByteArray()))
                ps.setString(2, workflowId)
                ps.setTimestamp(3, Timestamp.from(openedAt))
                ps.setTimestamp(4, Timestamp.from(openedAt.plusSeconds(3600)))
                ps.setString(5, status)
                ps.executeUpdate()
            }
        }
    }
}
