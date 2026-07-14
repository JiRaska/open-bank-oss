// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class ReleaseStewardModelsTest {

    @Test
    fun `ReleaseStewardFinding defaults to OPEN status`() {
        val finding = ReleaseStewardFinding(
            id = "test-id",
            checkType = ReleaseInvariantCheckType.MANIFEST_CONFIG_LOCKSTEP,
            severity = FindingSeverity.CRITICAL,
            detectedAt = Instant.now(),
            title = "openbank-transaction-service has version.txt but is NOT registered",
            component = "openbank-transaction-service",
            rawMetricValue = BigDecimal.ZERO,
            threshold = BigDecimal.ONE,
        )
        assertThat(finding.status).isEqualTo(FindingStatus.OPEN)
        assertThat(finding.rootCause).isNull()
        assertThat(finding.proposalUrl).isNull()
        assertThat(finding.prNumber).isNull()
    }

    @Test
    fun `ReleaseStewardReport counts proposed findings`() {
        val now = Instant.now()
        val finding = ReleaseStewardFinding(
            id = "f1",
            checkType = ReleaseInvariantCheckType.OPENAPI_VERSION_COLLISION,
            severity = FindingSeverity.CRITICAL,
            detectedAt = now,
            title = "2 open PRs touch openbank-ledger-service/openapi.yaml concurrently",
            component = "openbank-ledger-service/openapi.yaml",
            prNumber = 481,
            prUrl = "https://github.com/JiRaska/open-bank-oss/pull/481",
            rawMetricValue = BigDecimal.valueOf(2),
            threshold = BigDecimal.ONE,
            status = FindingStatus.PROPOSED,
        )
        val report = ReleaseStewardReport(
            runId = "run-1",
            startedAt = now,
            completedAt = now,
            modulesChecked = 43,
            prsChecked = 2,
            findingsDetected = listOf(finding),
            findingsProposed = 1,
            tokensUsed = 0,
            trigger = RunTrigger.SCHEDULED,
        )
        assertThat(report.findingsProposed).isEqualTo(1)
        assertThat(report.findingsDetected).hasSize(1)
        assertThat(report.modulesChecked).isEqualTo(43)
        assertThat(report.prsChecked).isEqualTo(2)
    }

    @Test
    fun `ReleaseInvariantCheckType enum covers all four ADR-0165 checks`() {
        assertThat(ReleaseInvariantCheckType.values()).containsExactlyInAnyOrder(
            ReleaseInvariantCheckType.MANIFEST_CONFIG_LOCKSTEP,
            ReleaseInvariantCheckType.ADMIN_UI_VERSION_SYNC,
            ReleaseInvariantCheckType.APP_VERSION_OVERRIDE,
            ReleaseInvariantCheckType.OPENAPI_VERSION_COLLISION,
        )
    }

    @Test
    fun `OpenApiPrChange carries the PR's proposed info-version for a single service`() {
        val change = OpenApiPrChange(
            prNumber = 524,
            prUrl = "https://github.com/JiRaska/open-bank-oss/pull/524",
            service = "openbank-ledger-service",
            proposedInfoVersion = "2.3.0",
        )
        assertThat(change.service).isEqualTo("openbank-ledger-service")
        assertThat(change.proposedInfoVersion).isEqualTo("2.3.0")
    }

    @Test
    fun `RepoStateSnapshot admin-ui fields are nullable when the files are absent`() {
        val snapshot = RepoStateSnapshot(
            releasePleaseConfigPackages = setOf("openbank-ledger-service"),
            releasePleaseManifestKeys = setOf("openbank-ledger-service"),
            modulesWithVersionTxt = setOf("openbank-ledger-service"),
            adminUiPackageJsonVersion = null,
            adminUiVersionTxt = null,
            servicesWithVersionOverride = emptyList(),
        )
        assertThat(snapshot.adminUiPackageJsonVersion).isNull()
        assertThat(snapshot.adminUiVersionTxt).isNull()
    }
}
