// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.domain.model

import java.math.BigDecimal
import java.time.Instant

// One value per governance rule this agent re-verifies against a merged PR (ADR-0164).
enum class GovernanceCheckType {
    APPROVAL_COUNT,
    THREAT_MODEL_PRESENCE,
    GPG_VERIFICATION,
    ISSUE_LINK,
    ADMIN_OVERRIDE_BYPASS,
}

enum class FindingSeverity { WARNING, CRITICAL }

enum class FindingStatus { OPEN, DIAGNOSED, PROPOSED, APPROVED, REJECTED, RESOLVED }

/** A PR merged to `main`, as read from the GitHub API — the unit this agent audits. */
data class MergedPullRequest(
    val number: Int,
    val url: String,
    val title: String,
    val body: String,
    val mergedAt: Instant,
    val mergedBy: String,
    val mergeCommitSha: String,
    val mergeCommitVerified: Boolean,
    val approvalCount: Int,
    val changedServices: List<String>,
    val hasMoneyPathLabel: Boolean,
    val usedAdminOverride: Boolean?,
)

data class GovernanceFinding(
    val id: String,
    val checkType: GovernanceCheckType,
    val severity: FindingSeverity,
    val detectedAt: Instant,
    val title: String,
    val prNumber: Int,
    val prUrl: String,
    val rawMetricValue: BigDecimal,
    val threshold: BigDecimal,
    val rootCause: String? = null,
    val proposalUrl: String? = null,
    val proposedFixDiff: String? = null,
    val status: FindingStatus = FindingStatus.OPEN,
    val diagnosedAt: Instant? = null,
    val proposedAt: Instant? = null,
)

data class GovernanceAuditReport(
    val runId: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val prsAudited: Int,
    val findingsDetected: List<GovernanceFinding>,
    val findingsProposed: Int,
    val tokensUsed: Int,
    val trigger: RunTrigger,
)

enum class RunTrigger { SCHEDULED, PR_MERGED_WEBHOOK, OPERATOR_MANUAL }
