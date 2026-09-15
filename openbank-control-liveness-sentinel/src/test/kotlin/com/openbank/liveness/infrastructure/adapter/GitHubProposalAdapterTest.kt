// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.infrastructure.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.liveness.domain.model.ControlMechanism
import com.openbank.liveness.domain.model.FindingSeverity
import com.openbank.liveness.domain.model.LivenessFinding
import com.openbank.liveness.infrastructure.config.LivenessSentinelConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.config.spi.ConfigProviderResolver
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * The degradation contract (ADR-0163): a GitHub side-effect that cannot happen must never lose the
 * finding or fail the workflow -- it returns a descriptive "not opened: ..." marker that is stored
 * as the proposal URL, so a human reading the findings table learns WHY there is no ticket.
 *
 * The token is read through an OPTIONAL `ConfigProvider` lookup deliberately outside the strict
 * `openbank.liveness-sentinel` `@ConfigMapping` prefix (a key nested under it with no matching
 * accessor fails boot with SRCFG00050), so it is driven here with a system property.
 */
class GitHubProposalAdapterTest {

    private val config = mockk<LivenessSentinelConfig> {
        every { githubApiUrl() } returns "http://127.0.0.1:1/"
        every { githubOwner() } returns "JiRaska"
        every { githubRepo() } returns "open-bank-oss"
        every { githubProposalDir() } returns "docs/liveness-sentinel-proposals"
    }

    private fun adapter() = GitHubProposalAdapter(config).also { it.objectMapper = ObjectMapper() }

    private fun withToken(value: String?) {
        if (value == null) System.clearProperty(TOKEN_KEY) else System.setProperty(TOKEN_KEY, value)
        val resolver = ConfigProviderResolver.instance()
        resolver.releaseConfig(resolver.config)
    }

    @AfterEach
    fun clearToken() = withToken(null)

    private fun finding() = LivenessFinding(
        id = "3f1c6f1e-0000-4000-8000-000000000001",
        mechanism = ControlMechanism.M4_RECONCILIATION_DRIFT_SLA,
        severity = FindingSeverity.CRITICAL,
        detectedAt = Instant.parse("2026-08-02T03:15:00Z"),
        title = "Control 'balance-reconciliation' has drifted for 4 consecutive runs",
        affectedControl = "balance-reconciliation",
        rawMetricValue = BigDecimal("4"),
        threshold = BigDecimal("3"),
        rootCause = "The nightly posting job stopped at 02:40.",
    )

    @Test
    fun `an un-seeded token degrades the ticket instead of throwing`(): Unit = runBlocking {
        withToken(null)

        val out = adapter().openTicket(finding(), "The nightly posting job stopped at 02:40.")

        assertThat(out).isEqualTo("not opened: liveness.github.token not seeded")
    }

    @Test
    fun `an un-seeded token degrades the proposal PR instead of throwing`(): Unit = runBlocking {
        withToken(null)

        val out = adapter().openProposalPr(finding(), "--- a/x\n+++ b/x\n")

        assertThat(out).isEqualTo("not opened: liveness.github.token not seeded")
    }

    @Test
    fun `an unreachable GitHub API degrades the ticket to a descriptive marker`(): Unit = runBlocking {
        withToken("fine-grained-token")

        // 127.0.0.1:1 refuses immediately: the HTTP send throws and the catch-all must convert it,
        // not propagate -- a thrown exception here would fail the whole Temporal activity and lose
        // the diagnosis with it.
        val out = adapter().openTicket(finding(), "diagnosis")

        assertThat(out).startsWith("not opened: ")
        assertThat(out).isNotEqualTo("not opened: liveness.github.token not seeded")
    }

    @Test
    fun `an unreachable GitHub API stops the PR flow at the main-SHA read`(): Unit = runBlocking {
        withToken("fine-grained-token")

        val out = adapter().openProposalPr(finding(), "--- a/x\n+++ b/x\n")

        // The first call in the flow is the main-SHA read; failing it must short-circuit before a
        // branch or a commit is attempted.
        assertThat(out).startsWith("not opened: ")
        assertThat(out).doesNotContain("could not commit proposal file")
    }

    private companion object {
        const val TOKEN_KEY = "liveness.github.token"
    }
}
