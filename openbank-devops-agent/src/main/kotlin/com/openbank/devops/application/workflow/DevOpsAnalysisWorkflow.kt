// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.devops.application.workflow

import com.openbank.devops.domain.model.DetectorId
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.DevOpsRunReport
import com.openbank.devops.domain.model.RunTrigger
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface DevOpsAnalysisWorkflow {
    @WorkflowMethod
    fun runAnalysis(trigger: RunTrigger): DevOpsRunReport
}

@ActivityInterface
interface CollectSignalsActivity {
    @ActivityMethod
    fun collectCiPipelineSignals(): Map<String, Double>

    @ActivityMethod
    fun collectDoraSignals(): Map<String, Double>

    @ActivityMethod
    fun collectRunnerCapacitySignals(): Map<String, Double>

    @ActivityMethod
    fun collectDeployHealthSignals(): Map<String, Double>

    @ActivityMethod
    fun collectIncidentRecurrenceSignals(): Map<String, Double>
}

@ActivityInterface
interface DetectFindingsActivity {
    @ActivityMethod
    fun detect(detectorId: DetectorId, signals: Map<String, Double>): List<DevOpsFinding>
}

@ActivityInterface
interface DiagnoseAndProposeActivity {
    @ActivityMethod
    fun diagnose(finding: DevOpsFinding, contextSignals: Map<String, Double>): DevOpsFinding

    @ActivityMethod
    fun propose(finding: DevOpsFinding): DevOpsFinding
}
