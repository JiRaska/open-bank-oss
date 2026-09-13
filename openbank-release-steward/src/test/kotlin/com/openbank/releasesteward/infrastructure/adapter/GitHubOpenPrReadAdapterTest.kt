// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.infrastructure.adapter

import com.openbank.releasesteward.application.workflow.DetectInvariantViolationsActivityImpl
import com.openbank.releasesteward.domain.model.ReleaseStewardSnapshot
import com.openbank.releasesteward.domain.model.RepoStateSnapshot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The unimplemented read path (ADR-0165 check 4) must UNDER-report, never fabricate.
 *
 * Asserting "it returns an empty list" on its own would be the weak shape this repo keeps warning
 * about, so the second test drives the real detector over the adapter's output and asserts what
 * matters downstream: an unwired read produces no findings, so no operator is ever handed a
 * collision that was invented rather than observed.
 */
class GitHubOpenPrReadAdapterTest {

    private val adapter = GitHubOpenPrReadAdapter()

    @Test
    fun `the unwired read reports no PR changes and no main baseline for any service`(): Unit = runBlocking {
        assertThat(adapter.listOpenPrsTouchingOpenApi()).isEmpty()
        assertThat(adapter.mainOpenApiVersion("openbank-ledger-service")).isNull()
        assertThat(adapter.mainOpenApiVersion("")).isNull()
    }

    @Test
    fun `an unwired read yields ZERO openapi findings rather than a fabricated collision`(): Unit = runBlocking {
        val snapshot = ReleaseStewardSnapshot(
            repoState = RepoStateSnapshot(
                releasePleaseConfigPackages = emptySet(),
                releasePleaseManifestKeys = emptySet(),
                modulesWithVersionTxt = emptySet(),
                adminUiPackageJsonVersion = null,
                adminUiVersionTxt = null,
                servicesWithVersionOverride = emptyList(),
            ),
            openApiPrChanges = adapter.listOpenPrsTouchingOpenApi(),
            mainOpenApiVersions = emptyMap(),
        )

        assertThat(DetectInvariantViolationsActivityImpl().detect(snapshot)).isEmpty()
    }
}
