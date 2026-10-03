// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.application.workflow

import com.openbank.releasesteward.domain.model.FindingSeverity
import com.openbank.releasesteward.domain.model.FindingStatus
import com.openbank.releasesteward.domain.model.ReleaseInvariantCheckType
import com.openbank.releasesteward.domain.model.ReleaseStewardSnapshot
import com.openbank.releasesteward.domain.model.RepoStateSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Covers ADR-0165 checks 1-3 of [DetectInvariantViolationsActivityImpl] — manifest/config
 * lockstep, admin-ui version sync and the explicit `quarkus.application.version` override.
 * The sibling test class covers check 4 (openapi collisions/regressions).
 */
class DetectInvariantViolationsRepoChecksTest {

    private val activity = DetectInvariantViolationsActivityImpl()

    private fun detect(
        config: Set<String> = emptySet(),
        manifest: Set<String> = emptySet(),
        versionTxt: Set<String> = emptySet(),
        adminUiPackageJson: String? = null,
        adminUiVersionTxt: String? = null,
        overrides: List<String> = emptyList(),
    ) = activity.detect(
        ReleaseStewardSnapshot(
            repoState = RepoStateSnapshot(
                releasePleaseConfigPackages = config,
                releasePleaseManifestKeys = manifest,
                modulesWithVersionTxt = versionTxt,
                adminUiPackageJsonVersion = adminUiPackageJson,
                adminUiVersionTxt = adminUiVersionTxt,
                servicesWithVersionOverride = overrides,
            ),
            openApiPrChanges = emptyList(),
            mainOpenApiVersions = emptyMap(),
        ),
    )

    @Test
    fun `a fully consistent repo state produces no findings at all`() {
        val findings = detect(
            config = setOf("openbank-ledger-service"),
            manifest = setOf("openbank-ledger-service"),
            versionTxt = setOf("openbank-ledger-service"),
            adminUiPackageJson = "0.91.4",
            adminUiVersionTxt = "0.91.4",
        )
        assertThat(findings).isEmpty()
    }

    @Test
    fun `a module with version-txt missing from BOTH registries yields two CRITICAL findings`() {
        val findings = detect(versionTxt = setOf("openbank-new-service"))

        assertThat(findings).hasSize(2)
        assertThat(findings).allSatisfy {
            assertThat(it.checkType).isEqualTo(ReleaseInvariantCheckType.MANIFEST_CONFIG_LOCKSTEP)
            assertThat(it.severity).isEqualTo(FindingSeverity.CRITICAL)
            assertThat(it.component).isEqualTo("openbank-new-service")
            assertThat(it.status).isEqualTo(FindingStatus.OPEN)
            // "registered" is the threshold; the observed value is "not registered".
            assertThat(it.rawMetricValue).isEqualByComparingTo(BigDecimal.ZERO)
            assertThat(it.threshold).isEqualByComparingTo(BigDecimal.ONE)
        }
        assertThat(findings.map { it.title })
            .anyMatch { it.contains("release-please-config.json packages") }
            .anyMatch { it.contains(".release-please-manifest.json") }
    }

    @Test
    fun `an orphan registry entry is only a WARNING and inverts the metric-threshold pair`() {
        val findings = detect(config = setOf("openbank-ghost"), manifest = setOf("openbank-ghost"))

        assertThat(findings).hasSize(2)
        assertThat(findings).allSatisfy {
            assertThat(it.severity).isEqualTo(FindingSeverity.WARNING)
            assertThat(it.rawMetricValue).isEqualByComparingTo(BigDecimal.ONE)
            assertThat(it.threshold).isEqualByComparingTo(BigDecimal.ZERO)
        }
        assertThat(findings.map { it.title })
            .anyMatch { it.contains("remove the package entry") }
            .anyMatch { it.contains("remove the manifest entry") }
    }

    @Test
    fun `a module in config but not manifest is reported with the side it is missing from`() {
        // version.txt present for both so the missing/orphan checks stay silent and only the
        // lockstep comparison can speak.
        val findings = detect(
            config = setOf("openbank-a", "openbank-b"),
            manifest = setOf("openbank-a"),
            versionTxt = setOf("openbank-a", "openbank-b"),
        )

        assertThat(findings).hasSize(2)
        val lockstep = findings.single { it.title.contains("lockstep") }
        assertThat(lockstep.component).isEqualTo("openbank-b")
        assertThat(lockstep.title).contains("config but not manifest")
        assertThat(lockstep.severity).isEqualTo(FindingSeverity.CRITICAL)
    }

    @Test
    fun `a module in manifest but not config is reported from the other side`() {
        val findings = detect(
            config = setOf("openbank-a"),
            manifest = setOf("openbank-a", "openbank-b"),
            versionTxt = setOf("openbank-a", "openbank-b"),
        )

        val lockstep = findings.single { it.title.contains("lockstep") }
        assertThat(lockstep.component).isEqualTo("openbank-b")
        assertThat(lockstep.title).contains("manifest but not config")
    }

    @Test
    fun `lockstep findings are emitted in sorted component order`() {
        val findings = detect(
            config = setOf("openbank-zebra", "openbank-alpha"),
            manifest = emptySet(),
            versionTxt = setOf("openbank-zebra", "openbank-alpha"),
        )

        val lockstepComponents = findings.filter { it.title.contains("lockstep") }.map { it.component }
        assertThat(lockstepComponents).containsExactly("openbank-alpha", "openbank-zebra")
    }

    @Test
    fun `admin-ui version drift is flagged as a WARNING naming both sides`() {
        val findings = detect(adminUiPackageJson = "0.91.5", adminUiVersionTxt = "0.91.4")

        val finding = findings.single()
        assertThat(finding.checkType).isEqualTo(ReleaseInvariantCheckType.ADMIN_UI_VERSION_SYNC)
        assertThat(finding.severity).isEqualTo(FindingSeverity.WARNING)
        assertThat(finding.component).isEqualTo("openbank-admin-ui")
        assertThat(finding.title).contains("version.txt=0.91.4").contains("package.json=0.91.5")
    }

    @Test
    fun `admin-ui with only one of the two files present is NOT flagged`() {
        // A half-read (file absent) must not read as drift — the check needs both sides.
        assertThat(detect(adminUiPackageJson = "0.91.5", adminUiVersionTxt = null)).isEmpty()
        assertThat(detect(adminUiPackageJson = null, adminUiVersionTxt = "0.91.4")).isEmpty()
    }

    @Test
    fun `an explicit quarkus application version override is CRITICAL, one finding per service`() {
        val findings = detect(overrides = listOf("openbank-zulu", "openbank-alpha"))

        assertThat(findings.map { it.component }).containsExactly("openbank-alpha", "openbank-zulu")
        assertThat(findings).allSatisfy {
            assertThat(it.checkType).isEqualTo(ReleaseInvariantCheckType.APP_VERSION_OVERRIDE)
            assertThat(it.severity).isEqualTo(FindingSeverity.CRITICAL)
            assertThat(it.rawMetricValue).isEqualByComparingTo(BigDecimal.ONE)
            assertThat(it.threshold).isEqualByComparingTo(BigDecimal.ZERO)
            assertThat(it.prNumber).isNull()
            assertThat(it.prUrl).isNull()
        }
    }

    @Test
    fun `every finding gets its own distinct id`() {
        val findings = detect(overrides = listOf("openbank-a", "openbank-b", "openbank-c"))

        assertThat(findings.map { it.id }.distinct()).hasSize(3)
    }
}
