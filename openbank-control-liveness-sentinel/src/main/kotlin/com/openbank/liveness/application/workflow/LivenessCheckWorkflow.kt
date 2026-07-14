// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.application.workflow

import com.openbank.liveness.domain.model.ControlMechanism
import com.openbank.liveness.domain.model.LivenessFinding
import com.openbank.liveness.domain.model.LivenessRunReport
import com.openbank.liveness.domain.model.RunTrigger
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface LivenessCheckWorkflow {
    @WorkflowMethod
    fun runCheck(trigger: RunTrigger): LivenessRunReport
}

@ActivityInterface
interface CollectSignalsActivity {
    @ActivityMethod
    fun collectWatchdogHeartbeats(): Map<String, Double>

    @ActivityMethod
    fun collectEventConsumerReport(): Map<String, Double>

    @ActivityMethod
    fun collectLineageAuditReport(): Map<String, Double>

    @ActivityMethod
    fun collectReconciliationDriftWindows(): Map<String, Double>
}

@ActivityInterface
interface DetectFindingsActivity {
    @ActivityMethod
    fun detect(mechanism: ControlMechanism, signals: Map<String, Double>): List<LivenessFinding>
}

@ActivityInterface
interface DiagnoseAndProposeActivity {
    @ActivityMethod
    fun diagnose(finding: LivenessFinding, contextMetrics: Map<String, Double>): LivenessFinding

    @ActivityMethod
    fun propose(finding: LivenessFinding): LivenessFinding
}
