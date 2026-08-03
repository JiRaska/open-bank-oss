// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.releasesteward.domain.model

import java.math.BigDecimal
import java.time.Instant

// One value per release/version-axis invariant this agent re-verifies fleet-wide (ADR-0165).
enum class ReleaseInvariantCheckType {
    MANIFEST_CONFIG_LOCKSTEP,
    ADMIN_UI_VERSION_SYNC,
    APP_VERSION_OVERRIDE,
    OPENAPI_VERSION_COLLISION,
}

enum class FindingSeverity { WARNING, CRITICAL }

enum class FindingStatus { OPEN, DIAGNOSED, PROPOSED, APPROVED, REJECTED, RESOLVED }

/**
 * Direct repo-checkout read of the release/version-axis state (ADR-0165 checks 1-3) — unlike the
 * other control-plane agents, this port reads local files rather than Prometheus/GitHub, since it
 * runs against a repo checkout.
 */
data class RepoStateSnapshot(
    val releasePleaseConfigPackages: Set<String>,
    val releasePleaseManifestKeys: Set<String>,
    val modulesWithVersionTxt: Set<String>,
    val adminUiPackageJsonVersion: String?,
    val adminUiVersionTxt: String?,
    // Services whose application.yaml sets quarkus.application.version explicitly — mirrors
    // check-app-version-override.sh's logic, run proactively fleet-wide (incident 3).
    val servicesWithVersionOverride: List<String>,
)

/** One open PR's proposed `info.version` for a single `openapi.yaml` it touches (check 4). */
data class OpenApiPrChange(val prNumber: Int, val prUrl: String, val service: String, val proposedInfoVersion: String)

/** A single collect-phase snapshot combining the repo-state read and the open-PR read. */
data class ReleaseStewardSnapshot(
    val repoState: RepoStateSnapshot,
    val openApiPrChanges: List<OpenApiPrChange>,
    // service -> current main info.version, used as the collision baseline for check 4.
    val mainOpenApiVersions: Map<String, String>,
)

data class ReleaseStewardFinding(
    val id: String,
    val checkType: ReleaseInvariantCheckType,
    val severity: FindingSeverity,
    val detectedAt: Instant,
    val title: String,
    // The module/service/file this finding is about, e.g. "openbank-transaction-service" or
    // "openbank-ledger-service/src/main/resources/openapi.yaml".
    val component: String,
    val prNumber: Int? = null,
    val prUrl: String? = null,
    val rawMetricValue: BigDecimal,
    val threshold: BigDecimal,
    val rootCause: String? = null,
    val proposalUrl: String? = null,
    val proposedFixDiff: String? = null,
    val status: FindingStatus = FindingStatus.OPEN,
    val diagnosedAt: Instant? = null,
    val proposedAt: Instant? = null,
)

data class ReleaseStewardReport(
    val runId: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val modulesChecked: Int,
    val prsChecked: Int,
    val findingsDetected: List<ReleaseStewardFinding>,
    val findingsProposed: Int,
    val tokensUsed: Int,
    val trigger: RunTrigger,
)

enum class RunTrigger { SCHEDULED, RELEASE_PLEASE_MERGE_WEBHOOK, OPERATOR_MANUAL }
