// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.application.usecase

import com.openbank.finops.application.port.incoming.GetAnomaliesUseCase
import com.openbank.finops.application.port.incoming.RunFinOpsAnalysisUseCase
import com.openbank.finops.application.port.out.AnomalyRepository
import com.openbank.finops.application.workflow.FinOpsAnalysisWorkflow
import com.openbank.finops.domain.model.CostAnomaly
import com.openbank.finops.domain.model.FinOpsRunReport
import com.openbank.finops.domain.model.RunTrigger
import com.openbank.libs.temporal.TemporalConfig
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

@ApplicationScoped
class FinOpsService(
    private val workflowClient: WorkflowClient,
    private val temporalConfig: TemporalConfig,
    private val anomalyRepository: AnomalyRepository,
) : RunFinOpsAnalysisUseCase,
    GetAnomaliesUseCase {

    private val log = Logger.getLogger(FinOpsService::class.java)

    override suspend fun run(trigger: RunTrigger): FinOpsRunReport {
        log.infof("Starting FinOps analysis workflow (trigger=%s)", trigger)
        val options = WorkflowOptions.newBuilder()
            .setTaskQueue(temporalConfig.taskQueue())
            .setWorkflowId("finops-analysis-${System.currentTimeMillis()}")
            .build()
        val workflow = workflowClient.newWorkflowStub(FinOpsAnalysisWorkflow::class.java, options)
        return workflow.runAnalysis(trigger)
    }

    override suspend fun getActive(): List<CostAnomaly> = anomalyRepository.findActive()

    override suspend fun getById(id: String): CostAnomaly? = anomalyRepository.findById(id)
}
