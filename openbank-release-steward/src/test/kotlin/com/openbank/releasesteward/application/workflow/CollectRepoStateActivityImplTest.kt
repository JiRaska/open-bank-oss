// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.application.workflow

import com.openbank.releasesteward.application.port.out.GitHubOpenPrReadPort
import com.openbank.releasesteward.application.port.out.RepoStateReadPort
import com.openbank.releasesteward.domain.model.OpenApiPrChange
import com.openbank.releasesteward.domain.model.RepoStateSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-JVM cover for the collect activity's own logic — the main-baseline map it builds from the
 * open-PR list. The Vert.x bridge is replaced by [runBlocking] via the `protected open` seam the
 * production class exposes for exactly this.
 */
class CollectRepoStateActivityImplTest {

    private val repoState = mockk<RepoStateReadPort>()
    private val githubOpenPr = mockk<GitHubOpenPrReadPort>()

    private val emptyState = RepoStateSnapshot(
        releasePleaseConfigPackages = setOf("openbank-ledger-service"),
        releasePleaseManifestKeys = setOf("openbank-ledger-service"),
        modulesWithVersionTxt = setOf("openbank-ledger-service"),
        adminUiPackageJsonVersion = "0.91.4",
        adminUiVersionTxt = "0.91.4",
        servicesWithVersionOverride = emptyList(),
    )

    /** The production class only bridges to a Vert.x context here; the collect logic is unchanged. */
    private class DirectCollect(repoState: RepoStateReadPort, githubOpenPr: GitHubOpenPrReadPort) :
        CollectRepoStateActivityImpl(repoState, githubOpenPr) {
        override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
    }

    private fun activity() = DirectCollect(repoState, githubOpenPr)

    private fun change(pr: Int, service: String, version: String) =
        OpenApiPrChange(prNumber = pr, prUrl = "https://example.invalid/pull/$pr", service = service, proposedInfoVersion = version)

    @Test
    fun `the main baseline is looked up once per DISTINCT service, not once per PR change`() {
        coEvery { repoState.snapshot() } returns emptyState
        coEvery { githubOpenPr.listOpenPrsTouchingOpenApi() } returns listOf(
            change(1, "openbank-ledger-service", "1.1.0"),
            change(2, "openbank-ledger-service", "1.2.0"),
            change(3, "openbank-card-service", "2.0.0"),
        )
        coEvery { githubOpenPr.mainOpenApiVersion("openbank-ledger-service") } returns "1.0.0"
        coEvery { githubOpenPr.mainOpenApiVersion("openbank-card-service") } returns "1.9.0"

        val snapshot = activity().collect()

        assertThat(snapshot.mainOpenApiVersions).containsExactlyInAnyOrderEntriesOf(
            mapOf("openbank-ledger-service" to "1.0.0", "openbank-card-service" to "1.9.0"),
        )
        coVerify(exactly = 1) { githubOpenPr.mainOpenApiVersion("openbank-ledger-service") }
    }

    @Test
    fun `a service with no baseline on main is OMITTED from the map rather than mapped to null`() {
        // The detector's regression half keys off presence: a null baseline must leave no entry,
        // or every PR for that service would be compared against nothing.
        coEvery { repoState.snapshot() } returns emptyState
        coEvery { githubOpenPr.listOpenPrsTouchingOpenApi() } returns listOf(
            change(1, "openbank-ledger-service", "1.1.0"),
            change(2, "openbank-brand-new-service", "0.1.0"),
        )
        coEvery { githubOpenPr.mainOpenApiVersion("openbank-ledger-service") } returns "1.0.0"
        coEvery { githubOpenPr.mainOpenApiVersion("openbank-brand-new-service") } returns null

        val snapshot = activity().collect()

        assertThat(snapshot.mainOpenApiVersions).containsOnlyKeys("openbank-ledger-service")
        assertThat(snapshot.openApiPrChanges).hasSize(2)
    }

    @Test
    fun `with no open PRs the baseline map is empty and GitHub is never asked for a version`() {
        coEvery { repoState.snapshot() } returns emptyState
        coEvery { githubOpenPr.listOpenPrsTouchingOpenApi() } returns emptyList()

        val snapshot = activity().collect()

        assertThat(snapshot.mainOpenApiVersions).isEmpty()
        assertThat(snapshot.repoState).isEqualTo(emptyState)
        coVerify(exactly = 0) { githubOpenPr.mainOpenApiVersion(any()) }
    }
}
