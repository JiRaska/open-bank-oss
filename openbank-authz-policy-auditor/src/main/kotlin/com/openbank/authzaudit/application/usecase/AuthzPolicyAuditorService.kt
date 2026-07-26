// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.authzaudit.application.usecase

import com.openbank.authzaudit.application.port.incoming.GetFindingsUseCase
import com.openbank.authzaudit.application.port.incoming.RunAuthzPolicyCheckUseCase
import com.openbank.authzaudit.application.port.out.FindingRepository
import com.openbank.authzaudit.application.workflow.AuthzPolicyAuditorWorkflow
import com.openbank.authzaudit.domain.model.AuthzPolicyFinding
import com.openbank.authzaudit.domain.model.AuthzPolicyReport
import com.openbank.authzaudit.domain.model.RunTrigger
import com.openbank.libs.temporal.TemporalConfig
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

@ApplicationScoped
class AuthzPolicyAuditorService(
    private val workflowClient: WorkflowClient,
    private val temporalConfig: TemporalConfig,
    private val findingRepository: FindingRepository,
) : RunAuthzPolicyCheckUseCase,
    GetFindingsUseCase {

    private val log = Logger.getLogger(AuthzPolicyAuditorService::class.java)

    override suspend fun run(trigger: RunTrigger): AuthzPolicyReport {
        log.infof("Starting authz-policy-auditor check workflow (trigger=%s)", trigger)
        val options = WorkflowOptions.newBuilder()
            .setTaskQueue(temporalConfig.taskQueue())
            .setWorkflowId("authz-policy-auditor-check-${System.currentTimeMillis()}")
            .build()
        val workflow = workflowClient.newWorkflowStub(AuthzPolicyAuditorWorkflow::class.java, options)
        return workflow.runCheck(trigger)
    }

    override suspend fun getActive(): List<AuthzPolicyFinding> = findingRepository.findActive()

    override suspend fun getById(id: String): AuthzPolicyFinding? = findingRepository.findById(id)
}
