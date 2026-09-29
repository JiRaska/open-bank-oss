// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.

package com.openbank.casecoordinator.infrastructure.observability

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import javax.sql.DataSource

/**
 * Publishes a point-in-time snapshot of active case counts so the `ShadowPilotCasesStuck`
 * alert and the "Active Cases" Grafana panel observe the database state without ringing on
 * every transient open case. Counts are grouped by case class and status; the MultiGauge is
 * overwritten on every tick so stale tag combinations disappear.
 */
@Startup
@ApplicationScoped
class CaseMetricsScheduler(private val dataSource: DataSource, private val metrics: CaseCoordinatorMetricsService) {

    @Inject
    lateinit var domainMetrics: DomainMetrics

    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun onStart() {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
    }

    @Scheduled(cron = "0 * * * * ?", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    suspend fun refreshActiveCases() {
        val counts = dataSource.connection.use { conn ->
            conn.prepareStatement(ACTIVE_CASE_COUNTS_SQL).use { ps ->
                ps.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) {
                            val caseClass = rs.getString(CASE_CLASS_COLUMN)
                            val status = rs.getString(STATUS_COLUMN)
                            val count = rs.getInt(COUNT_COLUMN)
                            put(caseClass to status, count)
                        }
                    }
                }
            }
        }
        metrics.updateActiveCases(counts)
        liveness?.recordSuccess()
    }

    private companion object {
        const val ACTIVE_CASE_COUNTS_SQL =
            "SELECT LOWER(case_class), LOWER(status), COUNT(*) FROM case_workflow WHERE case_class = 'INCIDENT_RESPONSE' GROUP BY LOWER(case_class), LOWER(status)"
        const val CASE_CLASS_COLUMN = 1
        const val STATUS_COLUMN = 2
        const val COUNT_COLUMN = 3
        const val WORKFLOW_NAME = "case-coordinator-active-cases-refresh"
        val EXPECTED_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
