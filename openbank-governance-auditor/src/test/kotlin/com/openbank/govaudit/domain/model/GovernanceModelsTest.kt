// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class GovernanceModelsTest {

    @Test
    fun `GovernanceFinding defaults to OPEN status`() {
        val finding = GovernanceFinding(
            id = "test-id",
            checkType = GovernanceCheckType.APPROVAL_COUNT,
            severity = FindingSeverity.CRITICAL,
            detectedAt = Instant.now(),
            title = "PR #42 merged with 0 approvals, 2 required",
            prNumber = 42,
            prUrl = "https://github.com/JiRaska/open-bank-oss/pull/42",
            rawMetricValue = BigDecimal.ZERO,
            threshold = BigDecimal.valueOf(2),
        )
        assertThat(finding.status).isEqualTo(FindingStatus.OPEN)
        assertThat(finding.rootCause).isNull()
        assertThat(finding.proposalUrl).isNull()
    }

    @Test
    fun `GovernanceAuditReport counts proposed findings`() {
        val now = Instant.now()
        val finding = GovernanceFinding(
            id = "f1",
            checkType = GovernanceCheckType.THREAT_MODEL_PRESENCE,
            severity = FindingSeverity.CRITICAL,
            detectedAt = now,
            title = "Missing threat model",
            prNumber = 7,
            prUrl = "https://github.com/JiRaska/open-bank-oss/pull/7",
            rawMetricValue = BigDecimal.ZERO,
            threshold = BigDecimal.ONE,
            status = FindingStatus.PROPOSED,
        )
        val report = GovernanceAuditReport(
            runId = "run-1",
            startedAt = now,
            completedAt = now,
            prsAudited = 1,
            findingsDetected = listOf(finding),
            findingsProposed = 1,
            tokensUsed = 0,
            trigger = RunTrigger.SCHEDULED,
        )
        assertThat(report.findingsProposed).isEqualTo(1)
        assertThat(report.findingsDetected).hasSize(1)
        assertThat(report.prsAudited).isEqualTo(1)
    }

    @Test
    fun `GovernanceCheckType enum covers all five ADR-0164 checks`() {
        assertThat(GovernanceCheckType.values()).containsExactlyInAnyOrder(
            GovernanceCheckType.APPROVAL_COUNT,
            GovernanceCheckType.THREAT_MODEL_PRESENCE,
            GovernanceCheckType.GPG_VERIFICATION,
            GovernanceCheckType.ISSUE_LINK,
            GovernanceCheckType.ADMIN_OVERRIDE_BYPASS,
        )
    }

    @Test
    fun `MergedPullRequest usedAdminOverride is nullable to represent an undeterminable signal`() {
        val pr = MergedPullRequest(
            number = 1,
            url = "https://github.com/JiRaska/open-bank-oss/pull/1",
            title = "test",
            body = "Closes #1",
            mergedAt = Instant.now(),
            mergedBy = "octocat",
            mergeCommitSha = "abc123",
            mergeCommitVerified = true,
            approvalCount = 2,
            changedServices = emptyList(),
            hasMoneyPathLabel = false,
            usedAdminOverride = null,
        )
        assertThat(pr.usedAdminOverride).isNull()
    }
}
