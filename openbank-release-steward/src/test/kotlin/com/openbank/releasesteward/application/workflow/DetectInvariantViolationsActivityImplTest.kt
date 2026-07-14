// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.application.workflow

import com.openbank.releasesteward.domain.model.OpenApiPrChange
import com.openbank.releasesteward.domain.model.ReleaseInvariantCheckType
import com.openbank.releasesteward.domain.model.ReleaseStewardSnapshot
import com.openbank.releasesteward.domain.model.RepoStateSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DetectInvariantViolationsActivityImplTest {

    private val activity = DetectInvariantViolationsActivityImpl()

    private val emptyRepoState = RepoStateSnapshot(
        releasePleaseConfigPackages = emptySet(),
        releasePleaseManifestKeys = emptySet(),
        modulesWithVersionTxt = emptySet(),
        adminUiPackageJsonVersion = null,
        adminUiVersionTxt = null,
        servicesWithVersionOverride = emptyList(),
    )

    private fun snapshotWith(
        prChanges: List<OpenApiPrChange> = emptyList(),
        mainVersions: Map<String, String> = emptyMap(),
    ) = ReleaseStewardSnapshot(
        repoState = emptyRepoState,
        openApiPrChanges = prChanges,
        mainOpenApiVersions = mainVersions,
    )

    // ADR-0165 incident 4, second half: a PR's proposed info.version must also be checked against
    // main's CURRENT value, not only against other open PRs. This is the gap this fix closes —
    // snapshot.mainOpenApiVersions used to be collected and never consumed.
    @Test
    fun `PR proposing a version not strictly greater than main's current value is flagged`() {
        val change = OpenApiPrChange(
            prNumber = 900,
            prUrl = "https://github.com/JiRaska/open-bank-oss/pull/900",
            service = "openbank-ledger-service",
            proposedInfoVersion = "2.3.0",
        )
        val findings = activity.detect(
            snapshotWith(
                prChanges = listOf(change),
                mainVersions = mapOf("openbank-ledger-service" to "2.3.0"),
            ),
        )

        val regression = findings.single { it.checkType == ReleaseInvariantCheckType.OPENAPI_VERSION_COLLISION }
        assertThat(regression.component).isEqualTo("openbank-ledger-service/openapi.yaml")
        assertThat(regression.prNumber).isEqualTo(900)
        assertThat(regression.title).contains("not strictly greater than main's current value 2.3.0")
    }

    @Test
    fun `PR proposing a version behind main's current value is flagged as a regression`() {
        val change = OpenApiPrChange(
            prNumber = 901,
            prUrl = "https://github.com/JiRaska/open-bank-oss/pull/901",
            service = "openbank-ledger-service",
            proposedInfoVersion = "2.2.0",
        )
        val findings = activity.detect(
            snapshotWith(
                prChanges = listOf(change),
                mainVersions = mapOf("openbank-ledger-service" to "2.3.0"),
            ),
        )

        assertThat(findings.filter { it.checkType == ReleaseInvariantCheckType.OPENAPI_VERSION_COLLISION })
            .hasSize(1)
    }

    @Test
    fun `PR proposing a version strictly ahead of main's current value is NOT flagged`() {
        val change = OpenApiPrChange(
            prNumber = 902,
            prUrl = "https://github.com/JiRaska/open-bank-oss/pull/902",
            service = "openbank-ledger-service",
            proposedInfoVersion = "2.4.0",
        )
        val findings = activity.detect(
            snapshotWith(
                prChanges = listOf(change),
                mainVersions = mapOf("openbank-ledger-service" to "2.3.0"),
            ),
        )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `a service with no known main baseline is NOT flagged by the main-comparison half`() {
        // GitHubOpenPrReadAdapter.mainOpenApiVersion() is a stub that currently returns null for
        // every service, so mainOpenApiVersions can legitimately omit a service that has open PR
        // changes; the check must skip it, not crash or false-positive.
        val change = OpenApiPrChange(
            prNumber = 903,
            prUrl = "https://github.com/JiRaska/open-bank-oss/pull/903",
            service = "openbank-unknown-service",
            proposedInfoVersion = "1.0.0",
        )
        val findings = activity.detect(snapshotWith(prChanges = listOf(change), mainVersions = emptyMap()))

        assertThat(findings).isEmpty()
    }

    @Test
    fun `two open PRs proposing the identical version are still flagged by the pairwise collision check`() {
        val changes = listOf(
            OpenApiPrChange(
                481,
                "https://github.com/JiRaska/open-bank-oss/pull/481",
                "openbank-ledger-service",
                "2.4.0",
            ),
            OpenApiPrChange(
                524,
                "https://github.com/JiRaska/open-bank-oss/pull/524",
                "openbank-ledger-service",
                "2.4.0",
            ),
        )
        val findings = activity.detect(
            snapshotWith(
                prChanges = changes,
                mainVersions = mapOf(
                    "openbank-ledger-service" to "2.3.0",
                ),
            ),
        )

        // Both halves of check 4 can legitimately fire in the same run: the pairwise collision
        // (both PRs proposing 2.4.0) AND, once main's baseline is present, none of these findings
        // is a regression because 2.4.0 > 2.3.0 -- so exactly the one pairwise finding is expected.
        assertThat(findings).hasSize(1)
        assertThat(findings.single().title).contains("propose the IDENTICAL info.version 2.4.0")
    }
}
