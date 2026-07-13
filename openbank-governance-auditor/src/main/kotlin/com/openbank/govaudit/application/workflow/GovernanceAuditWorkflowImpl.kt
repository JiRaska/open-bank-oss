// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.application.workflow

import com.openbank.govaudit.domain.model.FindingSeverity
import com.openbank.govaudit.domain.model.FindingStatus
import com.openbank.govaudit.domain.model.GovernanceAuditReport
import com.openbank.govaudit.domain.model.RunTrigger
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import java.time.Instant

@Suppress("MagicNumber")
class GovernanceAuditWorkflowImpl : GovernanceAuditWorkflow {

    // Daily catch-up sweep runs every 24h; a 26h lookback window gives one hour of overlap so a
    // slow-to-merge PR near the boundary is never skipped, matching the daily+reactive schedule
    // (ADR-0164). The PR-merged webhook trigger re-audits the single just-merged PR promptly; this
    // window only matters for the scheduled catch-up path.
    private val lookbackMillis = Duration.ofHours(26).toMillis()

    private val collectOptions = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(5))
        .build()

    private val diagnoseOptions = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(10))
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
        .build()

    private val collect = Workflow.newActivityStub(CollectMergedPrsActivity::class.java, collectOptions)
    private val detect = Workflow.newActivityStub(DetectViolationsActivity::class.java, collectOptions)
    private val diagnosePropose = Workflow.newActivityStub(DiagnoseAndProposeActivity::class.java, diagnoseOptions)

    override fun runAudit(trigger: RunTrigger): GovernanceAuditReport {
        val runId = Workflow.randomUUID().toString()
        val startedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis())
        val since = startedAt.minusMillis(lookbackMillis)

        val mergedPrs = collect.collect(since.toEpochMilli())
        val allFindings = mergedPrs.flatMap { pr -> detect.detect(pr) }

        val diagnosed = allFindings.map { finding ->
            val withDiagnosis = diagnosePropose.diagnose(finding, emptyMap())
            if (withDiagnosis.severity == FindingSeverity.CRITICAL) {
                diagnosePropose.propose(withDiagnosis)
            } else {
                withDiagnosis
            }
        }

        val completedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis())

        return GovernanceAuditReport(
            runId = runId,
            startedAt = startedAt,
            completedAt = completedAt,
            prsAudited = mergedPrs.size,
            findingsDetected = diagnosed,
            findingsProposed = diagnosed.count { it.status == FindingStatus.PROPOSED },
            tokensUsed = 0,
            trigger = trigger,
        )
    }
}
