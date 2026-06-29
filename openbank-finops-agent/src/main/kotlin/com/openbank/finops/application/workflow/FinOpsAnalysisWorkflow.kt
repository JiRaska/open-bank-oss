// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

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
