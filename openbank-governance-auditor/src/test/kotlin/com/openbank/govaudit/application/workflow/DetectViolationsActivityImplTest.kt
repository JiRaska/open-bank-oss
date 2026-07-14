// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.application.workflow

import com.openbank.govaudit.application.port.out.GitHubReadPort
import com.openbank.govaudit.application.port.out.GovernanceRulesPort
import com.openbank.govaudit.domain.model.FindingSeverity
import com.openbank.govaudit.domain.model.GovernanceCheckType
import com.openbank.govaudit.domain.model.MergedPullRequest
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class DetectViolationsActivityImplTest {

    private val governanceRules = mockk<GovernanceRulesPort> {
        coEvery { moneyPathServices() } returns setOf("openbank-ledger-service", "openbank-payment-service")
        coEvery { defaultApprovals() } returns 1
        coEvery { moneyPathApprovals() } returns 2
    }
    private val githubRead = mockk<GitHubReadPort> {
        coEvery { threatModelExists(any()) } returns true
    }

    // runOnVertxContext normally needs a live Vertx duplicated context (VertxContextSupport);
    // overriding it with runBlocking lets detect() run synchronously in a plain unit test.
    private val activity = object : DetectViolationsActivityImpl(governanceRules, githubRead) {
        override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
    }

    private fun compliantPr(overrides: MergedPullRequest.() -> MergedPullRequest = { this }): MergedPullRequest =
        MergedPullRequest(
            number = 100,
            url = "https://github.com/JiRaska/open-bank-oss/pull/100",
            title = "fix(notification): correct retry backoff",
            body = "Closes #42",
            mergedAt = Instant.parse("2026-07-01T00:00:00Z"),
            mergedBy = "jiri.raska",
            mergeCommitSha = "abcdef1234567890",
            mergeCommitVerified = true,
            approvalCount = 1,
            changedServices = listOf("openbank-notification-service"),
            hasMoneyPathLabel = false,
            usedAdminOverride = false,
        ).overrides()

    @Test
    fun `a fully compliant non-money-path PR produces no findings`() {
        val findings = activity.detect(compliantPr())
        assertThat(findings).isEmpty()
    }

    @Test
    fun `money-path PR missing a threat model produces a CRITICAL THREAT_MODEL_PRESENCE finding`() {
        coEvery { githubRead.threatModelExists("openbank-ledger-service") } returns false
        val pr = compliantPr {
            copy(changedServices = listOf("openbank-ledger-service"), hasMoneyPathLabel = true, approvalCount = 2)
        }

        val findings = activity.detect(pr)

        assertThat(findings).hasSize(1)
        assertThat(findings.single().checkType).isEqualTo(GovernanceCheckType.THREAT_MODEL_PRESENCE)
        assertThat(findings.single().severity).isEqualTo(FindingSeverity.CRITICAL)
    }

    @Test
    fun `PR merged with fewer approvals than rules yaml requires produces a CRITICAL APPROVAL_COUNT finding`() {
        val pr = compliantPr { copy(approvalCount = 0) }

        val findings = activity.detect(pr)

        assertThat(findings).hasSize(1)
        assertThat(findings.single().checkType).isEqualTo(GovernanceCheckType.APPROVAL_COUNT)
        assertThat(findings.single().severity).isEqualTo(FindingSeverity.CRITICAL)
    }

    @Test
    fun `an unsigned merge commit produces a WARNING GPG_VERIFICATION finding, not a hard block`() {
        val pr = compliantPr { copy(mergeCommitVerified = false) }

        val findings = activity.detect(pr)

        assertThat(findings).hasSize(1)
        assertThat(findings.single().checkType).isEqualTo(GovernanceCheckType.GPG_VERIFICATION)
        assertThat(findings.single().severity).isEqualTo(FindingSeverity.WARNING)
    }

    @Test
    fun `a PR body with no Closes-or-Refs issue link produces an ISSUE_LINK finding`() {
        val pr = compliantPr { copy(body = "This PR has no issue reference at all.") }

        val findings = activity.detect(pr)

        assertThat(findings).hasSize(1)
        assertThat(findings.single().checkType).isEqualTo(GovernanceCheckType.ISSUE_LINK)
    }

    @Test
    fun `an apparent admin-override bypass produces a CRITICAL ADMIN_OVERRIDE_BYPASS finding`() {
        val pr = compliantPr { copy(usedAdminOverride = true) }

        val findings = activity.detect(pr)

        assertThat(findings).hasSize(1)
        assertThat(findings.single().checkType).isEqualTo(GovernanceCheckType.ADMIN_OVERRIDE_BYPASS)
        assertThat(findings.single().severity).isEqualTo(FindingSeverity.CRITICAL)
    }

    @Test
    fun `an undeterminable admin-override signal (null) produces no finding, not a false positive`() {
        val pr = compliantPr { copy(usedAdminOverride = null) }

        val findings = activity.detect(pr)

        assertThat(findings).isEmpty()
    }
}
