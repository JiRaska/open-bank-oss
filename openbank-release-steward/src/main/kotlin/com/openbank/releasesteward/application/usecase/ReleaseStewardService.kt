// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.application.usecase

import com.openbank.libs.temporal.TemporalConfig
import com.openbank.releasesteward.application.port.incoming.GetFindingsUseCase
import com.openbank.releasesteward.application.port.incoming.RunReleaseStewardCheckUseCase
import com.openbank.releasesteward.application.port.out.FindingRepository
import com.openbank.releasesteward.application.workflow.ReleaseStewardWorkflow
import com.openbank.releasesteward.domain.model.ReleaseStewardFinding
import com.openbank.releasesteward.domain.model.ReleaseStewardReport
import com.openbank.releasesteward.domain.model.RunTrigger
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

@ApplicationScoped
class ReleaseStewardService(
    private val workflowClient: WorkflowClient,
    private val temporalConfig: TemporalConfig,
    private val findingRepository: FindingRepository,
) : RunReleaseStewardCheckUseCase,
    GetFindingsUseCase {

    private val log = Logger.getLogger(ReleaseStewardService::class.java)

    override suspend fun run(trigger: RunTrigger): ReleaseStewardReport {
        log.infof("Starting release-steward check workflow (trigger=%s)", trigger)
        val options = WorkflowOptions.newBuilder()
            .setTaskQueue(temporalConfig.taskQueue())
            .setWorkflowId("release-steward-check-${System.currentTimeMillis()}")
            .build()
        val workflow = workflowClient.newWorkflowStub(ReleaseStewardWorkflow::class.java, options)
        return workflow.runCheck(trigger)
    }

    override suspend fun getActive(): List<ReleaseStewardFinding> = findingRepository.findActive()

    override suspend fun getById(id: String): ReleaseStewardFinding? = findingRepository.findById(id)
}
