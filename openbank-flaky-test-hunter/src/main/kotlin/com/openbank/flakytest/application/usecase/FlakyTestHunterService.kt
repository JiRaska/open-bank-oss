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
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@ApplicationScoped
class FlakyTestHunterService(
    private val workflowClient: WorkflowClient,
    private val temporalConfig: TemporalConfig,
    private val findingRepository: FindingRepository,
    private val clock: Clock,
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

    override suspend fun startDetached(trigger: RunTrigger): String {
        val workflowId = scheduledWorkflowId(trigger, Instant.now(clock))
        val options = WorkflowOptions.newBuilder()
            .setTaskQueue(temporalConfig.taskQueue())
            .setWorkflowId(workflowId)
            .build()
        val stub = workflowClient.newWorkflowStub(FlakyTestHunterWorkflow::class.java, options)
        try {
            WorkflowClient.start({ stub.runCheck(trigger) })
            log.infof("Started flaky-test-hunter sweep workflow %s (trigger=%s)", workflowId, trigger)
        } catch (duplicate: WorkflowExecutionAlreadyStarted) {
            // Not an error — the dedupe working. Two pods, or one restarted after the cron fired,
            // compute the same id and Temporal admits only the first.
            log.infof("Sweep workflow %s already running, not starting a second: %s", workflowId, duplicate.message)
        }
        return workflowId
    }

    override suspend fun getActive(): List<FlakyTestFinding> = findingRepository.findActive()

    override suspend fun getById(id: String): FlakyTestFinding? = findingRepository.findById(id)

    companion object {
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

        /**
         * The id that makes a detached start idempotent for the day.
         *
         * Deliberately NOT `System.currentTimeMillis()`, which the operator path uses: an id that
         * can never collide can never dedupe either. The trigger is part of the id so an operator
         * can still force a run on a day the schedule has already used.
         */
        fun scheduledWorkflowId(trigger: RunTrigger, at: Instant): String =
            "flaky-test-hunter-check-${trigger.name.lowercase()}-${DAY.format(at)}"
    }
}
