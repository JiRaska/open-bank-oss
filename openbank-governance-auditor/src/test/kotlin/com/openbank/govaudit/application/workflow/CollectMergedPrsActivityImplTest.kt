// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.govaudit.application.workflow

import com.openbank.govaudit.application.port.out.GitHubReadPort
import com.openbank.govaudit.domain.model.MergedPullRequest
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The activity's wire contract is a `Long` epochMilli rather than an `Instant` (see
 * `CollectMergedPrsActivity`), so this class owns the only conversion back. A wrong unit here
 * (seconds read as millis) would silently move the lookback window by decades and the sweep would
 * report "0 PRs audited" — the healthiest-looking possible answer.
 */
class CollectMergedPrsActivityImplTest {

    private val since = slot<Instant>()
    private val githubRead = mockk<GitHubReadPort> {
        coEvery { listMergedPrsSince(capture(since)) } returns emptyList()
    }

    private val activity = object : CollectMergedPrsActivityImpl(githubRead) {
        override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
    }

    @Test
    fun `the epochMilli argument is interpreted as milliseconds, not seconds`() {
        val expected = Instant.parse("2026-07-25T04:30:00Z")

        activity.collect(expected.toEpochMilli())

        assertThat(since.captured).isEqualTo(expected)
    }

    @Test
    fun `the port's PR list is returned unfiltered`() {
        val pr = MergedPullRequest(
            number = 7,
            url = "https://github.com/JiRaska/open-bank-oss/pull/7",
            title = "fix(ledger): correct posting order",
            body = "Closes #7",
            mergedAt = Instant.parse("2026-07-25T03:00:00Z"),
            mergedBy = "someone",
            mergeCommitSha = "0123456789abcdef",
            mergeCommitVerified = true,
            approvalCount = 2,
            changedServices = listOf("openbank-ledger-service"),
            hasMoneyPathLabel = true,
            usedAdminOverride = false,
        )
        coEvery { githubRead.listMergedPrsSince(any()) } returns listOf(pr)

        assertThat(activity.collect(0L)).containsExactly(pr)
    }
}
