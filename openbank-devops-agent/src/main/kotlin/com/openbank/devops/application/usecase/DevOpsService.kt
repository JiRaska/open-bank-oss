// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.application.usecase

import com.openbank.devops.application.port.incoming.DecideFindingUseCase
import com.openbank.devops.application.port.incoming.GetFindingsUseCase
import com.openbank.devops.application.port.incoming.RunDevOpsAnalysisUseCase
import com.openbank.devops.application.port.out.FindingRepository
import com.openbank.devops.application.workflow.DevOpsAnalysisWorkflow
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.DevOpsRunReport
import com.openbank.devops.domain.model.FindingStatus
import com.openbank.devops.domain.model.RunTrigger
import com.openbank.libs.temporal.TemporalConfig
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

@ApplicationScoped
class DevOpsService(
    private val workflowClient: WorkflowClient,
    private val temporalConfig: TemporalConfig,
    private val findingRepository: FindingRepository,
) : RunDevOpsAnalysisUseCase,
    GetFindingsUseCase,
    DecideFindingUseCase {

    private val log = Logger.getLogger(DevOpsService::class.java)

    // CodeQL java/log-injection: id is caller-supplied and flows straight into the log line
    // below. Strip CR/LF so an attacker can't forge additional log lines (CWE-117).
    private fun String?.sanitizeForLog(): String = (this ?: "-").replace('\n', '_').replace('\r', '_')

    override suspend fun run(trigger: RunTrigger): DevOpsRunReport {
        log.infof("Starting DevOps analysis workflow (trigger=%s)", trigger)
        val options = WorkflowOptions.newBuilder()
            .setTaskQueue(temporalConfig.taskQueue())
            .setWorkflowId("devops-analysis-${System.currentTimeMillis()}")
            .build()
        val workflow = workflowClient.newWorkflowStub(DevOpsAnalysisWorkflow::class.java, options)
        return workflow.runAnalysis(trigger)
    }

    override suspend fun getActive(): List<DevOpsFinding> = findingRepository.findActive()

    override suspend fun getById(id: String): DevOpsFinding? = findingRepository.findById(id)

    override suspend fun approve(id: String): DevOpsFinding? = transition(id, FindingStatus.APPROVED)

    override suspend fun reject(id: String): DevOpsFinding? = transition(id, FindingStatus.REJECTED)

    private suspend fun transition(id: String, status: FindingStatus): DevOpsFinding? {
        val finding = findingRepository.findById(id) ?: return null
        log.infof("HITL %s on finding %s by operator", status, id.sanitizeForLog())
        return findingRepository.update(finding.copy(status = status))
    }
}
