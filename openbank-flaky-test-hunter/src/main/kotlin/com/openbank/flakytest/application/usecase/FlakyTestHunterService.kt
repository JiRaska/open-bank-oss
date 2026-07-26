// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.application.usecase

import com.openbank.flakytest.application.port.incoming.GetFindingsUseCase
import com.openbank.flakytest.application.port.incoming.RunFlakyTestCheckUseCase
import com.openbank.flakytest.application.port.out.FindingRepository
import com.openbank.flakytest.application.workflow.FlakyTestHunterWorkflow
import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.domain.model.FlakyTestReport
import com.openbank.flakytest.domain.model.RunTrigger
import com.openbank.libs.temporal.TemporalConfig
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

@ApplicationScoped
class FlakyTestHunterService(
    private val workflowClient: WorkflowClient,
    private val temporalConfig: TemporalConfig,
    private val findingRepository: FindingRepository,
) : RunFlakyTestCheckUseCase,
    GetFindingsUseCase {

    private val log = Logger.getLogger(FlakyTestHunterService::class.java)

    override suspend fun run(trigger: RunTrigger): FlakyTestReport {
        log.infof("Starting flaky-test-hunter check workflow (trigger=%s)", trigger)
        val options = WorkflowOptions.newBuilder()
            .setTaskQueue(temporalConfig.taskQueue())
            .setWorkflowId("flaky-test-hunter-check-${System.currentTimeMillis()}")
            .build()
        val workflow = workflowClient.newWorkflowStub(FlakyTestHunterWorkflow::class.java, options)
        return workflow.runCheck(trigger)
    }

    override suspend fun getActive(): List<FlakyTestFinding> = findingRepository.findActive()

    override suspend fun getById(id: String): FlakyTestFinding? = findingRepository.findById(id)
}
