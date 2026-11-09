// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.application.workflow

import com.openbank.devops.domain.model.DetectorId
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.DevOpsRunReport
import com.openbank.devops.domain.model.FindingSeverity
import com.openbank.devops.domain.model.FindingStatus
import com.openbank.devops.domain.model.RunTrigger
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.failure.ActivityFailure
import io.temporal.failure.CanceledFailure
import io.temporal.workflow.Workflow
import java.time.Duration
import java.time.Instant

/**
 * Durable analysis sweep (ADR-0119). collect → detect → diagnose → propose, exactly the
 * finops-agent shape (ADR-0112) so the operational model is consistent across the fleet.
 * A finding is only proposed (a PR/runbook/ticket drafted for HITL) when it is CRITICAL or
 * it puts a DORA metric at risk — low-signal findings stay DIAGNOSED for the dashboard.
 */
@Suppress("MagicNumber")
class DevOpsAnalysisWorkflowImpl : DevOpsAnalysisWorkflow {

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

    private val ciGateProfile = Workflow.newActivityStub(
        CiGateProfileActivity::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(45))
            .setScheduleToCloseTimeout(Duration.ofMinutes(1))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
            .build(),
    )

    private val cpuAdvisory = Workflow.newActivityStub(
        CpuOptimizationAdvisoryActivity::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setScheduleToCloseTimeout(Duration.ofMinutes(1))
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
            .build(),
    )

    override fun runAnalysis(trigger: RunTrigger): DevOpsRunReport {
        val runId = Workflow.randomUUID().toString()
        val startedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis())

        // Collect signals and detect findings per detector.
        val allFindings = mutableListOf<DevOpsFinding>()
        allFindings += detect.detect(DetectorId.D1_CI_PIPELINE_HEALTH, collect.collectCiPipelineSignals())
        allFindings += detect.detect(DetectorId.D2_DORA_REGRESSION, collect.collectDoraSignals())
        allFindings += detect.detect(DetectorId.D3_RUNNER_CAPACITY, collect.collectRunnerCapacitySignals())
        allFindings += detect.detect(DetectorId.D4_DEPLOY_HEALTH, collect.collectDeployHealthSignals())
        allFindings += detect.detect(DetectorId.D5_SSDLC_HYGIENE, collect.collectSsdlcSignals())
        allFindings += detect.detect(DetectorId.D6_INCIDENT_RECURRENCE, collect.collectIncidentRecurrenceSignals())

        // Existing histories retain their original activity sequence.
        val profile = if (Workflow.getVersion("devops-ci-gate-profile", Workflow.DEFAULT_VERSION, 1) == 1) {
            try {
                ciGateProfile.collectGateProfile()
            } catch (failure: ActivityFailure) {
                if (failure.cause is CanceledFailure) throw failure
                // Optional read-only collection must not erase the existing detector report.
                null
            }
        } else {
            null
        }

        // Diagnose (LLM) and, when warranted, draft a durable remediation for the HITL queue.
        val diagnosed = allFindings.map { finding ->
            val withDiagnosis = diagnosePropose.diagnose(finding, emptyMap())
            if (withDiagnosis.severity == FindingSeverity.CRITICAL ||
                withDiagnosis.doraMetricImpacted != null
            ) {
                diagnosePropose.propose(withDiagnosis)
            } else {
                withDiagnosis
            }
        }

        // Separate version marker preserves both pre-profile and profile-only histories.
        val advisory = if (Workflow.getVersion("devops-cpu-advisory", Workflow.DEFAULT_VERSION, 1) == 1 &&
            profile != null
        ) {
            try {
                cpuAdvisory.assess(profile)
            } catch (failure: ActivityFailure) {
                if (failure.cause is CanceledFailure) throw failure
                null
            }
        } else {
            null
        }
        val reportFindings = diagnosed + listOfNotNull(advisory)

        val completedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis())

        return DevOpsRunReport(
            runId = runId,
            startedAt = startedAt,
            completedAt = completedAt,
            findingsDetected = reportFindings,
            findingsProposed = reportFindings.count { it.status == FindingStatus.PROPOSED },
            doraMetricsAtRisk = reportFindings.mapNotNull { it.doraMetricImpacted }.distinct().size,
            tokensUsed = 0,
            trigger = trigger,
            ciGateProfile = profile,
        )
    }
}
