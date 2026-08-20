// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.casecoordinator.application

import com.openbank.casecoordinator.domain.model.CaseDeliveryMode
import com.openbank.casecoordinator.infrastructure.config.CaseCoordinatorConfig
import com.openbank.libs.temporal.TemporalConfig
import io.quarkus.runtime.Startup
import io.temporal.api.filter.v1.WorkflowTypeFilter
import io.temporal.api.workflowservice.v1.ListOpenWorkflowExecutionsRequest
import io.temporal.client.WorkflowClient
import jakarta.enterprise.context.ApplicationScoped
import javax.sql.DataSource

/**
 * Refuses a shadow-pilot rollout while a legacy case workflow remains open.
 *
 * A run started before [CaseDeliveryMode] was introduced deserializes without a delivery mode and
 * deliberately defaults to HITL for replay compatibility. Allowing it to finish after the GitOps
 * switch would therefore still publish a human-actionable proposal. The visibility query is a
 * rollout precondition, not a runtime estimate: unavailable Temporal visibility fails startup.
 */
@Startup
@ApplicationScoped
class ShadowPilotPreflight(
    private val workflowClient: WorkflowClient,
    private val temporalConfig: TemporalConfig,
    private val config: CaseCoordinatorConfig,
    private val dataSource: DataSource,
) {

    init {
        if (config.case().deliveryMode() == CaseDeliveryMode.SHADOW) verifyNoLegacyOpenCases()
    }

    private fun verifyNoLegacyOpenCases() {
        val rolloutId = config.case().shadowRolloutId().filter { it.isNotBlank() }.orElseThrow {
            IllegalStateException("Shadow pilot requires a distinct shadowRolloutId")
        }
        if (alreadyCompleted(rolloutId)) return
        check(temporalConfig.enabled()) {
            "Shadow pilot requires Temporal to be enabled for its legacy-workflow preflight"
        }
        val request = ListOpenWorkflowExecutionsRequest.newBuilder()
            .setNamespace(workflowClient.options.namespace)
            .setTypeFilter(WorkflowTypeFilter.newBuilder().setName(WORKFLOW_TYPE).build())
            .build()
        val openRuns = workflowClient.workflowServiceStubs.blockingStub()
            .listOpenWorkflowExecutions(request)
            .executionsCount
        check(openRuns == 0) {
            "Shadow pilot cannot start while $openRuns legacy case workflow run(s) are open"
        }
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO case_shadow_pilot_preflight (rollout_id) VALUES (?) ON CONFLICT DO NOTHING",
            ).use {
                it.setString(1, rolloutId)
                it.executeUpdate()
            }
        }
    }

    private fun alreadyCompleted(rolloutId: String): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT EXISTS (SELECT 1 FROM case_shadow_pilot_preflight WHERE rollout_id = ?)",
        ).use { statement ->
            statement.setString(1, rolloutId)
            statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
        }
    }

    private companion object {
        const val WORKFLOW_TYPE = "CaseWorkflow"
    }
}
