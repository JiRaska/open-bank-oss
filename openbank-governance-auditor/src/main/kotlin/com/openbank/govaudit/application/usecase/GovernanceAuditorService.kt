// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.application.usecase

import com.openbank.govaudit.application.port.incoming.GetFindingsUseCase
import com.openbank.govaudit.application.port.incoming.RunGovernanceAuditUseCase
import com.openbank.govaudit.application.port.out.FindingRepository
import com.openbank.govaudit.application.workflow.GovernanceAuditWorkflow
import com.openbank.govaudit.domain.model.GovernanceAuditReport
import com.openbank.govaudit.domain.model.GovernanceFinding
import com.openbank.govaudit.domain.model.RunTrigger
import com.openbank.libs.temporal.TemporalConfig
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

@ApplicationScoped
class GovernanceAuditorService(
    private val workflowClient: WorkflowClient,
    private val temporalConfig: TemporalConfig,
    private val findingRepository: FindingRepository,
) : RunGovernanceAuditUseCase,
    GetFindingsUseCase {

    private val log = Logger.getLogger(GovernanceAuditorService::class.java)

    override suspend fun run(trigger: RunTrigger): GovernanceAuditReport {
        log.infof("Starting governance-auditor audit workflow (trigger=%s)", trigger)
        val options = WorkflowOptions.newBuilder()
            .setTaskQueue(temporalConfig.taskQueue())
            .setWorkflowId("governance-audit-${System.currentTimeMillis()}")
            .build()
        val workflow = workflowClient.newWorkflowStub(GovernanceAuditWorkflow::class.java, options)
        return workflow.runAudit(trigger)
    }

    override suspend fun getActive(): List<GovernanceFinding> = findingRepository.findActive()

    override suspend fun getById(id: String): GovernanceFinding? = findingRepository.findById(id)
}
