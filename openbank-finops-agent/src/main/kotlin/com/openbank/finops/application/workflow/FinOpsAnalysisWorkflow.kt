// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.finops.application.workflow

import com.openbank.finops.domain.model.CostAnomaly
import com.openbank.finops.domain.model.DetectorId
import com.openbank.finops.domain.model.FinOpsRunReport
import com.openbank.finops.domain.model.RunTrigger
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface FinOpsAnalysisWorkflow {
    @WorkflowMethod
    fun runAnalysis(trigger: RunTrigger): FinOpsRunReport
}

@ActivityInterface
interface CollectMetricsActivity {
    @ActivityMethod
    fun collectNatEgressMetrics(): Map<String, Double>

    @ActivityMethod
    fun collectKarpenterMetrics(): Map<String, Double>

    @ActivityMethod
    fun collectEbsHealthMetrics(): Map<String, Double>

    @ActivityMethod
    fun collectCiRunnerMetrics(): Map<String, Double>

    @ActivityMethod
    fun collectAiTokenMetrics(): Map<String, Double>
}

@ActivityInterface
interface DetectAnomaliesActivity {
    @ActivityMethod
    fun detect(detectorId: DetectorId, metrics: Map<String, Double>): List<CostAnomaly>
}

@ActivityInterface
interface DiagnoseAndProposeActivity {
    @ActivityMethod
    fun diagnose(anomaly: CostAnomaly, contextMetrics: Map<String, Double>): CostAnomaly

    @ActivityMethod
    fun propose(anomaly: CostAnomaly): CostAnomaly
}
