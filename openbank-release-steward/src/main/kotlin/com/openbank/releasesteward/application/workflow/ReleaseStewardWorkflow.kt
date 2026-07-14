// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.application.workflow

import com.openbank.releasesteward.domain.model.ReleaseStewardFinding
import com.openbank.releasesteward.domain.model.ReleaseStewardReport
import com.openbank.releasesteward.domain.model.ReleaseStewardSnapshot
import com.openbank.releasesteward.domain.model.RunTrigger
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface ReleaseStewardWorkflow {
    @WorkflowMethod
    fun runCheck(trigger: RunTrigger): ReleaseStewardReport
}

@ActivityInterface
interface CollectRepoStateActivity {
    @ActivityMethod
    fun collect(): ReleaseStewardSnapshot
}

@ActivityInterface
interface DetectInvariantViolationsActivity {
    @ActivityMethod
    fun detect(snapshot: ReleaseStewardSnapshot): List<ReleaseStewardFinding>
}

@ActivityInterface
interface DiagnoseAndProposeActivity {
    @ActivityMethod
    fun diagnose(finding: ReleaseStewardFinding, contextMetrics: Map<String, Double>): ReleaseStewardFinding

    @ActivityMethod
    fun propose(finding: ReleaseStewardFinding): ReleaseStewardFinding
}
