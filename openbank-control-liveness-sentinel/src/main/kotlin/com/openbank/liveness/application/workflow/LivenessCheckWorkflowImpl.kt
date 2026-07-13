// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.application.workflow

import com.openbank.liveness.domain.model.ControlMechanism
import com.openbank.liveness.domain.model.FindingSeverity
import com.openbank.liveness.domain.model.FindingStatus
import com.openbank.liveness.domain.model.LivenessFinding
import com.openbank.liveness.domain.model.LivenessRunReport
import com.openbank.liveness.domain.model.RunTrigger
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

@Suppress("MagicNumber")
class LivenessCheckWorkflowImpl : LivenessCheckWorkflow {

    private val collectOptions = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(5))
        .build()

    private val diagnoseOptions = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(10))
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
        .build()

    private val collect = Workflow.newActivityStub(CollectSignalsActivity::class.java, collectOptions)
    private val detect = Workflow.newActivityStub(DetectFindingsActivity::class.java, collectOptions)
    private val diagnosePropose = Workflow.newActivityStub(DiagnoseAndProposeActivity::class.java, diagnoseOptions)

    override fun runCheck(trigger: RunTrigger): LivenessRunReport {
        val runId = Workflow.randomUUID().toString()
        val startedAt = java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis())

        val allFindings = mutableListOf<LivenessFinding>()
        allFindings += detect.detect(ControlMechanism.M3_WORKFLOW_WATCHDOG, collect.collectWatchdogHeartbeats())
        allFindings += detect.detect(ControlMechanism.M1_EVENT_CONSUMER_LIVENESS, collect.collectEventConsumerReport())
        allFindings += detect.detect(ControlMechanism.M2_LINEAGE_VS_CODE, collect.collectLineageAuditReport())
        allFindings += detect.detect(
            ControlMechanism.M4_RECONCILIATION_DRIFT_SLA,
            collect.collectReconciliationDriftWindows(),
        )

        val diagnosed = allFindings.map { finding ->
            val withDiagnosis = diagnosePropose.diagnose(finding, emptyMap())
            if (withDiagnosis.severity == FindingSeverity.CRITICAL) {
                diagnosePropose.propose(withDiagnosis)
            } else {
                withDiagnosis
            }
        }

        val completedAt = java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis())

        return LivenessRunReport(
            runId = runId,
            startedAt = startedAt,
            completedAt = completedAt,
            findingsDetected = diagnosed,
            findingsProposed = diagnosed.count { it.status == FindingStatus.PROPOSED },
            tokensUsed = 0,
            trigger = trigger,
        )
    }
}
