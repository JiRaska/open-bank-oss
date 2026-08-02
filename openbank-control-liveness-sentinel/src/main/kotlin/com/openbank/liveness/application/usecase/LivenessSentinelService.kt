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
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowOptions
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@ApplicationScoped
class LivenessSentinelService(
    private val workflowClient: WorkflowClient,
    private val temporalConfig: TemporalConfig,
    private val findingRepository: FindingRepository,
    private val clock: Clock,
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

    override suspend fun startDetached(trigger: RunTrigger): String {
        val workflowId = scheduledWorkflowId(trigger, Instant.now(clock))
        val options = WorkflowOptions.newBuilder()
            .setTaskQueue(temporalConfig.taskQueue())
            .setWorkflowId(workflowId)
            .build()
        val stub = workflowClient.newWorkflowStub(LivenessCheckWorkflow::class.java, options)
        try {
            WorkflowClient.start({ stub.runCheck(trigger) })
            log.infof("Started control-liveness-sentinel check workflow %s (trigger=%s)", workflowId, trigger)
        } catch (duplicate: WorkflowExecutionAlreadyStarted) {
            // Not an error: this is the dedupe working. Two pods, or one pod restarted after the
            // cron fired, both compute the same id and Temporal admits only the first.
            log.infof("Check workflow %s already running, not starting a second: %s", workflowId, duplicate.message)
        }
        return workflowId
    }

    override suspend fun getActive(): List<LivenessFinding> = findingRepository.findActive()

    override suspend fun getById(id: String): LivenessFinding? = findingRepository.findById(id)

    companion object {
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

        /**
         * The id that makes a detached start idempotent for the day.
         *
         * Deliberately NOT `System.currentTimeMillis()`, which the operator-triggered path uses:
         * a millisecond-unique id can never collide, so it can never dedupe either. The trigger is
         * part of the id so an operator can still force a run on a day the schedule has already
         * used -- the schedule must not be able to block a human.
         */
        fun scheduledWorkflowId(trigger: RunTrigger, at: Instant): String =
            "liveness-check-${trigger.name.lowercase()}-${DAY.format(at)}"
    }
}
