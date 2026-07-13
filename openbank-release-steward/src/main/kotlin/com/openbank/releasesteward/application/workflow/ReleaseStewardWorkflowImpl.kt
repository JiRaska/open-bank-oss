// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.application.workflow

import com.openbank.releasesteward.domain.model.FindingSeverity
import com.openbank.releasesteward.domain.model.FindingStatus
import com.openbank.releasesteward.domain.model.ReleaseStewardReport
import com.openbank.releasesteward.domain.model.RunTrigger
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import java.time.Instant

@Suppress("MagicNumber")
class ReleaseStewardWorkflowImpl : ReleaseStewardWorkflow {

    private val collectOptions = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(5))
        .build()

    private val diagnoseOptions = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofMinutes(10))
        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
        .build()

    private val collect = Workflow.newActivityStub(CollectRepoStateActivity::class.java, collectOptions)
    private val detect = Workflow.newActivityStub(DetectInvariantViolationsActivity::class.java, collectOptions)
    private val diagnosePropose = Workflow.newActivityStub(DiagnoseAndProposeActivity::class.java, diagnoseOptions)

    override fun runCheck(trigger: RunTrigger): ReleaseStewardReport {
        val runId = Workflow.randomUUID().toString()
        val startedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis())

        val snapshot = collect.collect()
        val findings = detect.detect(snapshot)

        val diagnosed = findings.map { finding ->
            val withDiagnosis = diagnosePropose.diagnose(finding, emptyMap())
            if (withDiagnosis.severity == FindingSeverity.CRITICAL) {
                diagnosePropose.propose(withDiagnosis)
            } else {
                withDiagnosis
            }
        }

        val completedAt = Instant.ofEpochMilli(Workflow.currentTimeMillis())

        return ReleaseStewardReport(
            runId = runId,
            startedAt = startedAt,
            completedAt = completedAt,
            modulesChecked = snapshot.repoState.modulesWithVersionTxt.size,
            prsChecked = snapshot.openApiPrChanges.map { it.prNumber }.distinct().size,
            findingsDetected = diagnosed,
            findingsProposed = diagnosed.count { it.status == FindingStatus.PROPOSED },
            tokensUsed = 0,
            trigger = trigger,
        )
    }
}
