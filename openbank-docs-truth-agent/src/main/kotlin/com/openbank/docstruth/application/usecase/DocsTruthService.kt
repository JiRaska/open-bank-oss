// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.application.usecase

import com.openbank.docstruth.application.port.incoming.GetFindingsUseCase
import com.openbank.docstruth.application.port.incoming.RunDocsTruthCheckUseCase
import com.openbank.docstruth.application.port.out.FindingRepository
import com.openbank.docstruth.application.workflow.DocsTruthWorkflow
import com.openbank.docstruth.domain.model.DocsTruthFinding
import com.openbank.docstruth.domain.model.DocsTruthReport
import com.openbank.docstruth.domain.model.RunTrigger
import com.openbank.libs.temporal.TemporalConfig
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

@ApplicationScoped
class DocsTruthService(
    private val workflowClient: WorkflowClient,
    private val temporalConfig: TemporalConfig,
    private val findingRepository: FindingRepository,
) : RunDocsTruthCheckUseCase,
    GetFindingsUseCase {

    private val log = Logger.getLogger(DocsTruthService::class.java)

    override suspend fun run(trigger: RunTrigger): DocsTruthReport {
        log.infof("Starting docs-truth-agent check workflow (trigger=%s)", trigger)
        val options = WorkflowOptions.newBuilder()
            .setTaskQueue(temporalConfig.taskQueue())
            .setWorkflowId("docs-truth-agent-check-${System.currentTimeMillis()}")
            .build()
        val workflow = workflowClient.newWorkflowStub(DocsTruthWorkflow::class.java, options)
        return workflow.runCheck(trigger)
    }

    override suspend fun getActive(): List<DocsTruthFinding> = findingRepository.findActive()

    override suspend fun getById(id: String): DocsTruthFinding? = findingRepository.findById(id)
}
