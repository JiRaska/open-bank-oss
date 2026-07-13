// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.application.workflow

import com.openbank.docstruth.domain.model.DocsTruthFinding
import com.openbank.docstruth.domain.model.DocsTruthReport
import com.openbank.docstruth.domain.model.DocsTruthSnapshot
import com.openbank.docstruth.domain.model.RunTrigger
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface DocsTruthWorkflow {
    @WorkflowMethod
    fun runCheck(trigger: RunTrigger): DocsTruthReport
}

@ActivityInterface
interface CollectRepoScanActivity {
    @ActivityMethod
    fun collect(): DocsTruthSnapshot
}

@ActivityInterface
interface DetectDriftActivity {
    @ActivityMethod
    fun detect(snapshot: DocsTruthSnapshot): List<DocsTruthFinding>
}

@ActivityInterface
interface DiagnoseAndProposeActivity {
    @ActivityMethod
    fun diagnose(finding: DocsTruthFinding, contextMetrics: Map<String, Double>): DocsTruthFinding

    @ActivityMethod
    fun propose(finding: DocsTruthFinding): DocsTruthFinding
}
