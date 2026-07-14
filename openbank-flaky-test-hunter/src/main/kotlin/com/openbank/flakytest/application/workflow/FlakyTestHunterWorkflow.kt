// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.flakytest.application.workflow

import com.openbank.flakytest.domain.model.FlakyTestFinding
import com.openbank.flakytest.domain.model.FlakyTestReport
import com.openbank.flakytest.domain.model.RunTrigger
import com.openbank.flakytest.domain.model.TestScanSnapshot
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface FlakyTestHunterWorkflow {
    @WorkflowMethod
    fun runCheck(trigger: RunTrigger): FlakyTestReport
}

@ActivityInterface
interface CollectTestScanActivity {
    @ActivityMethod
    fun collect(): TestScanSnapshot
}

@ActivityInterface
interface DetectDriftActivity {
    @ActivityMethod
    fun detect(snapshot: TestScanSnapshot): List<FlakyTestFinding>
}

@ActivityInterface
interface DiagnoseAndProposeActivity {
    @ActivityMethod
    fun diagnose(finding: FlakyTestFinding, contextMetrics: Map<String, Double>): FlakyTestFinding

    @ActivityMethod
    fun propose(finding: FlakyTestFinding): FlakyTestFinding
}
