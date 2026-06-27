// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.devops.application.workflow

import com.openbank.devops.domain.model.DetectorId
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.DevOpsRunReport
import com.openbank.devops.domain.model.FindingSeverity
import com.openbank.devops.domain.model.FindingStatus
import com.openbank.devops.domain.model.RunTrigger
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
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

    override fun runAnalysis(trigger: RunTrigger): DevOpsRunReport {
        val runId = Workflow.randomUUID().toString()
        val startedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis())

        // Collect signals and detect findings per detector.
        val allFindings = mutableListOf<DevOpsFinding>()
        allFindings += detect.detect(DetectorId.D1_CI_PIPELINE_HEALTH, collect.collectCiPipelineSignals())
        allFindings += detect.detect(DetectorId.D2_DORA_REGRESSION, collect.collectDoraSignals())
        allFindings += detect.detect(DetectorId.D3_RUNNER_CAPACITY, collect.collectRunnerCapacitySignals())
        allFindings += detect.detect(DetectorId.D4_DEPLOY_HEALTH, collect.collectDeployHealthSignals())
        allFindings += detect.detect(DetectorId.D6_INCIDENT_RECURRENCE, collect.collectIncidentRecurrenceSignals())

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

        val completedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis())

        return DevOpsRunReport(
            runId = runId,
            startedAt = startedAt,
            completedAt = completedAt,
            findingsDetected = diagnosed,
            findingsProposed = diagnosed.count { it.status == FindingStatus.PROPOSED },
            doraMetricsAtRisk = diagnosed.mapNotNull { it.doraMetricImpacted }.distinct().size,
            tokensUsed = 0,
            trigger = trigger,
        )
    }
}
