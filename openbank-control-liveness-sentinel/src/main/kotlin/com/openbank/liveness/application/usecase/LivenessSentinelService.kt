// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.application.usecase

import com.openbank.libs.temporal.TemporalConfig
import com.openbank.liveness.application.port.incoming.GetFindingsUseCase
import com.openbank.liveness.application.port.incoming.RunLivenessCheckUseCase
import com.openbank.liveness.application.port.out.FindingRepository
import com.openbank.liveness.application.workflow.LivenessCheckWorkflow
import com.openbank.liveness.domain.model.LivenessFinding
import com.openbank.liveness.domain.model.LivenessRunReport
import com.openbank.liveness.domain.model.RunTrigger
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

@ApplicationScoped
class LivenessSentinelService(
    private val workflowClient: WorkflowClient,
    private val temporalConfig: TemporalConfig,
    private val findingRepository: FindingRepository,
) : RunLivenessCheckUseCase,
    GetFindingsUseCase {

    private val log = Logger.getLogger(LivenessSentinelService::class.java)

    override suspend fun run(trigger: RunTrigger): LivenessRunReport {
        log.infof("Starting control-liveness-sentinel check workflow (trigger=%s)", trigger)
        val options = WorkflowOptions.newBuilder()
            .setTaskQueue(temporalConfig.taskQueue())
            .setWorkflowId("liveness-check-${System.currentTimeMillis()}")
            .build()
        val workflow = workflowClient.newWorkflowStub(LivenessCheckWorkflow::class.java, options)
        return workflow.runCheck(trigger)
    }

    override suspend fun getActive(): List<LivenessFinding> = findingRepository.findActive()

    override suspend fun getById(id: String): LivenessFinding? = findingRepository.findById(id)
}
