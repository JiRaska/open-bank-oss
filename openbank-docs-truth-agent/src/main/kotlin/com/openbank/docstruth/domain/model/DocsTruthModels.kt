// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.docstruth.domain.model

import java.math.BigDecimal
import java.time.Instant

// One value per ADR-status-vs-code drift check this agent re-verifies fleet-wide (ADR-0166).
enum class DocsTruthCheckType {
    SHIPPED_ARTIFACT_MISSING,
    PLANNED_ARTIFACT_ALREADY_SHIPPED,
    ENFORCEMENT_STATUS_MISMATCH,
}

enum class FindingSeverity { WARNING, CRITICAL }

enum class FindingStatus { OPEN, DIAGNOSED, PROPOSED, APPROVED, REJECTED, RESOLVED }

// Parsed from an ADR's own `Delivery-Status:` line. NOT_TRACKED covers `N/A — decision-only`,
// `Superseded`, or a missing/unparseable line.
enum class AdrDeliveryStatus { PLANNED, PARTIAL, SHIPPED, COMPLETE, NOT_TRACKED }

/**
 * One backtick-quoted artifact reference an ADR makes (a class name, file path, or script name),
 * tagged with whether the surrounding sentence explicitly claims it does NOT exist yet — the
 * exact textual shape of the ADR-0139/ADR-0140 "not yet implemented" claim that had already been
 * shipped as `OnlineFeatureStore`.
 */
data class ClaimedArtifact(val name: String, val claimedNotYetBuilt: Boolean)

/** One backtick-quoted gate/script name an ADR or doc claims is (or is not) enforced. */
data class ClaimedEnforcement(val gateName: String, val claimedEnforced: Boolean)

/** A single parsed `docs/adr` ADR record — the unit of comparison for all three checks. */
data class AdrRecord(
    val id: String,
    val path: String,
    val title: String,
    val deliveryStatus: AdrDeliveryStatus,
    val claimedArtifacts: List<ClaimedArtifact>,
    val claimedEnforcements: List<ClaimedEnforcement>,
)

/** Whether a claimed artifact was actually found anywhere in the repo (excluding `docs/adr`
 * itself, to avoid trivially matching the ADR's own prose), and where. */
data class ArtifactExistence(val artifact: String, val exists: Boolean, val matchedPaths: List<String>)

/**
 * A single collect-phase snapshot: every parsed ADR, the existence result for every artifact any
 * ADR claims, and `rules.yaml`'s best-effort `enforced:` text for every gate/script any ADR
 * claims a status for.
 */
data class DocsTruthSnapshot(
    val adrRecords: List<AdrRecord>,
    val artifactExistence: Map<String, ArtifactExistence>,
    val gateEnforcementStatus: Map<String, String>,
)

data class DocsTruthFinding(
    val id: String,
    val checkType: DocsTruthCheckType,
    val severity: FindingSeverity,
    val detectedAt: Instant,
    val title: String,
    // The ADR this finding is about, e.g. "ADR-0139" or "ADR-0114".
    val component: String,
    val adrPath: String,
    val rawMetricValue: BigDecimal,
    val threshold: BigDecimal,
    val rootCause: String? = null,
    val proposalUrl: String? = null,
    val proposedFixDiff: String? = null,
    val status: FindingStatus = FindingStatus.OPEN,
    val diagnosedAt: Instant? = null,
    val proposedAt: Instant? = null,
)

data class DocsTruthReport(
    val runId: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val adrsScanned: Int,
    val findingsDetected: List<DocsTruthFinding>,
    val findingsProposed: Int,
    val tokensUsed: Int,
    val trigger: RunTrigger,
)

enum class RunTrigger { SCHEDULED, ADR_MERGE_WEBHOOK, OPERATOR_MANUAL }
