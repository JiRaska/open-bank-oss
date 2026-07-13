// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.application.workflow

import com.openbank.govaudit.application.port.out.GitHubReadPort
import com.openbank.govaudit.application.port.out.GovernanceRulesPort
import com.openbank.govaudit.domain.model.FindingSeverity
import com.openbank.govaudit.domain.model.FindingStatus
import com.openbank.govaudit.domain.model.GovernanceCheckType
import com.openbank.govaudit.domain.model.GovernanceFinding
import com.openbank.govaudit.domain.model.MergedPullRequest
import com.openbank.libs.domain.identifiers.Ids
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.math.BigDecimal
import java.time.Instant

// The PR body's "Linked issues" section per the PR template (rules.yaml: issues.link_in_pr).
private val LINKED_ISSUE_PATTERN = Regex("""(?i)\b(closes|refs|fixes|resolves)\s+#\d+""")

@ApplicationScoped
open class DetectViolationsActivityImpl(
    private val governanceRules: GovernanceRulesPort,
    private val githubRead: GitHubReadPort,
) : DetectViolationsActivity {

    override fun detect(pr: MergedPullRequest): List<GovernanceFinding> = runOnVertxContext {
        val moneyPathServices = governanceRules.moneyPathServices()
        val touchedMoneyPathServices = pr.changedServices.filter { it in moneyPathServices }
        val isMoneyPath = pr.hasMoneyPathLabel || touchedMoneyPathServices.isNotEmpty()
        val requiredApprovals = if (isMoneyPath) {
            governanceRules.moneyPathApprovals()
        } else {
            governanceRules.defaultApprovals()
        }

        buildList {
            addAll(checkApprovalCount(pr, requiredApprovals))
            addAll(checkThreatModel(pr, isMoneyPath, touchedMoneyPathServices))
            addAll(checkGpgVerification(pr))
            addAll(checkIssueLink(pr))
            addAll(checkAdminOverrideBypass(pr))
        }
    }

    private fun checkApprovalCount(pr: MergedPullRequest, requiredApprovals: Int): List<GovernanceFinding> {
        if (pr.approvalCount >= requiredApprovals) return emptyList()
        return listOf(
            newFinding(
                pr = pr,
                checkType = GovernanceCheckType.APPROVAL_COUNT,
                severity = FindingSeverity.CRITICAL,
                title = "PR #${pr.number} merged with ${pr.approvalCount} approval(s), " +
                    "$requiredApprovals required (rules.yaml: review)",
                rawMetricValue = BigDecimal.valueOf(pr.approvalCount.toLong()),
                threshold = BigDecimal.valueOf(requiredApprovals.toLong()),
            ),
        )
    }

    private suspend fun checkThreatModel(
        pr: MergedPullRequest,
        isMoneyPath: Boolean,
        touchedMoneyPathServices: List<String>,
    ): List<GovernanceFinding> {
        if (!isMoneyPath) return emptyList()
        val missing = touchedMoneyPathServices.filterNot { githubRead.threatModelExists(it) }
        return missing.map { service ->
            newFinding(
                pr = pr,
                checkType = GovernanceCheckType.THREAT_MODEL_PRESENCE,
                severity = FindingSeverity.CRITICAL,
                title = "PR #${pr.number} touches money-path service '$service' with no " +
                    "docs/threat-models/$service.md (ADR-0030 D2)",
                rawMetricValue = BigDecimal.ZERO,
                threshold = BigDecimal.ONE,
            )
        }
    }

    private fun checkGpgVerification(pr: MergedPullRequest): List<GovernanceFinding> {
        if (pr.mergeCommitVerified) return emptyList()
        return listOf(
            newFinding(
                pr = pr,
                checkType = GovernanceCheckType.GPG_VERIFICATION,
                severity = FindingSeverity.WARNING,
                title = "PR #${pr.number} merge commit ${pr.mergeCommitSha.take(SHA_PREFIX_LEN)} is not GPG-verified",
                rawMetricValue = BigDecimal.ZERO,
                threshold = BigDecimal.ONE,
            ),
        )
    }

    private fun checkIssueLink(pr: MergedPullRequest): List<GovernanceFinding> {
        if (LINKED_ISSUE_PATTERN.containsMatchIn(pr.body)) return emptyList()
        return listOf(
            newFinding(
                pr = pr,
                checkType = GovernanceCheckType.ISSUE_LINK,
                severity = FindingSeverity.WARNING,
                title = "PR #${pr.number} body has no Closes/Refs #<n> linked issue (rules.yaml: issues.link_in_pr)",
                rawMetricValue = BigDecimal.ZERO,
                threshold = BigDecimal.ONE,
            ),
        )
    }

    // Best-effort (ADR-0164 Negative): the GitHub API does not reliably expose whether `--admin`
    // was passed to `gh pr merge`. usedAdminOverride is null when the adapter cannot determine it
    // from available review-decision / merge-method metadata — a null is NOT evidence of
    // compliance, just an untested signal, so it deliberately produces no finding either way.
    private fun checkAdminOverrideBypass(pr: MergedPullRequest): List<GovernanceFinding> {
        if (pr.usedAdminOverride != true) return emptyList()
        return listOf(
            newFinding(
                pr = pr,
                checkType = GovernanceCheckType.ADMIN_OVERRIDE_BYPASS,
                severity = FindingSeverity.CRITICAL,
                title = "PR #${pr.number} appears to have been merged via an admin/override bypass " +
                    "of a required check",
                rawMetricValue = BigDecimal.ONE,
                threshold = BigDecimal.ZERO,
            ),
        )
    }

    private fun newFinding(
        pr: MergedPullRequest,
        checkType: GovernanceCheckType,
        severity: FindingSeverity,
        title: String,
        rawMetricValue: BigDecimal,
        threshold: BigDecimal,
    ) = GovernanceFinding(
        id = Ids.newId().toString(),
        checkType = checkType,
        severity = severity,
        detectedAt = Instant.now(),
        title = title,
        prNumber = pr.number,
        prUrl = pr.url,
        rawMetricValue = rawMetricValue,
        threshold = threshold,
        status = FindingStatus.OPEN,
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }

    companion object {
        private const val SHA_PREFIX_LEN = 8
    }
}
