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
import java.math.BigDecimal

/** ADR-0165 check 4 — the pairwise open-PR collision branch and its two detail wordings. */
class OpenApiCollisionDetailTest {

    private val activity = DetectInvariantViolationsActivityImpl()

    private fun detect(changes: List<OpenApiPrChange>, mainVersions: Map<String, String> = emptyMap()) =
        activity.detect(
            ReleaseStewardSnapshot(
                repoState = RepoStateSnapshot(
                    releasePleaseConfigPackages = emptySet(),
                    releasePleaseManifestKeys = emptySet(),
                    modulesWithVersionTxt = emptySet(),
                    adminUiPackageJsonVersion = null,
                    adminUiVersionTxt = null,
                    servicesWithVersionOverride = emptyList(),
                ),
                openApiPrChanges = changes,
                mainOpenApiVersions = mainVersions,
            ),
        )

    private fun change(pr: Int, service: String, version: String) =
        OpenApiPrChange(prNumber = pr, prUrl = "https://github.com/JiRaska/open-bank-oss/pull/$pr", service = service, proposedInfoVersion = version)

    @Test
    fun `two PRs proposing DIFFERENT versions for one spec get the diff-base-race wording`() {
        val findings = detect(listOf(change(10, "openbank-ledger-service", "1.3.0"), change(11, "openbank-ledger-service", "1.4.0")))

        val finding = findings.single { it.checkType == ReleaseInvariantCheckType.OPENAPI_VERSION_COLLISION }
        assertThat(finding.title).contains("different proposed versions").contains("1.3.0", "1.4.0")
        assertThat(finding.title).doesNotContain("IDENTICAL")
        assertThat(finding.component).isEqualTo("openbank-ledger-service/openapi.yaml")
        assertThat(finding.prNumber).isEqualTo(10)
        assertThat(finding.prUrl).isEqualTo("https://github.com/JiRaska/open-bank-oss/pull/10")
        assertThat(finding.rawMetricValue).isEqualByComparingTo(BigDecimal.valueOf(2))
        assertThat(finding.threshold).isEqualByComparingTo(BigDecimal.ONE)
    }

    @Test
    fun `one PR touching two specs is not a collision for either`() {
        val findings = detect(
            listOf(change(10, "openbank-ledger-service", "1.3.0"), change(10, "openbank-card-service", "2.0.0")),
        )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `the SAME PR listed twice for one spec is not a collision - the count is over distinct PRs`() {
        // A PR can legitimately appear twice for one service (two touched files); only two DISTINCT
        // PR numbers race.
        val findings = detect(listOf(change(10, "openbank-ledger-service", "1.3.0"), change(10, "openbank-ledger-service", "1.3.0")))

        assertThat(findings).isEmpty()
    }

    @Test
    fun `three racing PRs are reported once with all three numbers and a count of three`() {
        val findings = detect(
            listOf(
                change(30, "openbank-ledger-service", "1.3.0"),
                change(10, "openbank-ledger-service", "1.3.0"),
                change(20, "openbank-ledger-service", "1.3.0"),
            ),
        )

        val finding = findings.single()
        assertThat(finding.title).contains("3 open PRs (#10, #20, #30)")
        assertThat(finding.rawMetricValue).isEqualByComparingTo(BigDecimal.valueOf(3))
    }

    @Test
    fun `a collision and a regression on the same spec are reported as separate findings`() {
        // Both PRs sit at or below main, so each also fails the main-comparison half.
        val findings = detect(
            listOf(change(10, "openbank-ledger-service", "1.3.0"), change(11, "openbank-ledger-service", "1.2.0")),
            mainVersions = mapOf("openbank-ledger-service" to "1.3.0"),
        )

        assertThat(findings).hasSize(3)
        assertThat(findings.count { it.title.contains("open PRs") }).isEqualTo(1)
        assertThat(findings.count { it.title.contains("not strictly greater") }).isEqualTo(2)
    }

    @Test
    fun `an equal-length version compares segment by segment, not lexically`() {
        // "1.10.0" > "1.9.0" numerically but < lexically — a string compare would flag this.
        val findings = detect(
            listOf(change(10, "openbank-ledger-service", "1.10.0")),
            mainVersions = mapOf("openbank-ledger-service" to "1.9.0"),
        )

        assertThat(findings).isEmpty()
    }

    @Test
    fun `a shorter version string is padded with zeros rather than treated as smaller`() {
        // "2" == "2.0.0", so it is NOT strictly greater and must be flagged.
        val flagged = detect(
            listOf(change(10, "openbank-ledger-service", "2")),
            mainVersions = mapOf("openbank-ledger-service" to "2.0.0"),
        )
        assertThat(flagged).hasSize(1)

        val ok = detect(
            listOf(change(10, "openbank-ledger-service", "2.0.1")),
            mainVersions = mapOf("openbank-ledger-service" to "2"),
        )
        assertThat(ok).isEmpty()
    }
}
