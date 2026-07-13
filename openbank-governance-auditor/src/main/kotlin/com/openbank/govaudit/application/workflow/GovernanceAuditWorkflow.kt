// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.application.workflow

import com.openbank.govaudit.domain.model.GovernanceAuditReport
import com.openbank.govaudit.domain.model.GovernanceFinding
import com.openbank.govaudit.domain.model.MergedPullRequest
import com.openbank.govaudit.domain.model.RunTrigger
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface GovernanceAuditWorkflow {
    @WorkflowMethod
    fun runAudit(trigger: RunTrigger): GovernanceAuditReport
}

@ActivityInterface
interface CollectMergedPrsActivity {
    // epochMilli, not Instant: Temporal payload converters serialize java.time types fine, but the
    // primitive keeps this activity's wire contract dependency-free for any future non-JVM worker.
    @ActivityMethod
    fun collect(sinceEpochMilli: Long): List<MergedPullRequest>
}

@ActivityInterface
interface DetectViolationsActivity {
    @ActivityMethod
    fun detect(pr: MergedPullRequest): List<GovernanceFinding>
}

@ActivityInterface
interface DiagnoseAndProposeActivity {
    @ActivityMethod
    fun diagnose(finding: GovernanceFinding, contextMetrics: Map<String, Double>): GovernanceFinding

    @ActivityMethod
    fun propose(finding: GovernanceFinding): GovernanceFinding
}
