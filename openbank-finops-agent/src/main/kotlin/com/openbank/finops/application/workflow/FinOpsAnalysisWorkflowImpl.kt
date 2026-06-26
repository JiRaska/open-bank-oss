// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.finops.application.workflow

import com.openbank.finops.domain.model.AnomalySeverity
import com.openbank.finops.domain.model.AnomalyStatus
import com.openbank.finops.domain.model.CostAnomaly
import com.openbank.finops.domain.model.DetectorId
import com.openbank.finops.domain.model.FinOpsRunReport
import com.openbank.finops.domain.model.RunTrigger
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.math.BigDecimal
import java.time.Duration

@Suppress("MagicNumber")
class FinOpsAnalysisWorkflowImpl : FinOpsAnalysisWorkflow {

    private val collectOptions = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(5))
        .build()

    private val diagnoseOptions = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(10))
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
        .build()

    private val collect = Workflow.newActivityStub(CollectMetricsActivity::class.java, collectOptions)
    private val detect = Workflow.newActivityStub(DetectAnomaliesActivity::class.java, collectOptions)
    private val diagnosePropose = Workflow.newActivityStub(DiagnoseAndProposeActivity::class.java, diagnoseOptions)

    override fun runAnalysis(trigger: RunTrigger): FinOpsRunReport {
        val runId = Workflow.randomUUID().toString()
        val startedAt = java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis())

        // Collect metrics and detect anomalies per detector
        val allAnomalies = mutableListOf<CostAnomaly>()
        allAnomalies += detect.detect(DetectorId.D1_NAT_EGRESS, collect.collectNatEgressMetrics())
        allAnomalies += detect.detect(DetectorId.D3_NODE_CHURN, collect.collectKarpenterMetrics())
        allAnomalies += detect.detect(DetectorId.D4_EBS_HEALTH, collect.collectEbsHealthMetrics())
        allAnomalies += detect.detect(DetectorId.D5_CI_RUNNER, collect.collectCiRunnerMetrics())
        allAnomalies += detect.detect(DetectorId.D6_AI_TOKEN_BUDGET, collect.collectAiTokenMetrics())

        // Diagnose and propose IaC fixes for each anomaly (LLM + HITL gate)
        val diagnosed = allAnomalies.map { anomaly ->
            val withDiagnosis = diagnosePropose.diagnose(anomaly, emptyMap())
            if (withDiagnosis.severity == AnomalySeverity.CRITICAL ||
                withDiagnosis.estimatedMonthlySavingUsd != null
            ) {
                diagnosePropose.propose(withDiagnosis)
            } else {
                withDiagnosis
            }
        }

        val completedAt = java.time.Instant.ofEpochMilli(Workflow.currentTimeMillis())

        return FinOpsRunReport(
            runId = runId,
            startedAt = startedAt,
            completedAt = completedAt,
            anomaliesDetected = diagnosed,
            anomaliesProposed = diagnosed.count { it.status == AnomalyStatus.PROPOSED },
            estimatedTotalMonthlySavingUsd = diagnosed
                .mapNotNull { it.estimatedMonthlySavingUsd }
                .fold(BigDecimal.ZERO, BigDecimal::add),
            tokensUsed = 0,
            trigger = trigger,
        )
    }
}
