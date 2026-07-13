// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.domain.model

import java.math.BigDecimal
import java.time.Instant

// One enum value per ADR-0160 mechanism.
enum class ControlMechanism {
    M1_EVENT_CONSUMER_LIVENESS,
    M2_LINEAGE_VS_CODE,
    M3_WORKFLOW_WATCHDOG,
    M4_RECONCILIATION_DRIFT_SLA,
}

enum class FindingSeverity { WARNING, CRITICAL }

enum class FindingStatus { OPEN, DIAGNOSED, PROPOSED, APPROVED, REJECTED, RESOLVED }

data class LivenessFinding(
    val id: String,
    val mechanism: ControlMechanism,
    val severity: FindingSeverity,
    val detectedAt: Instant,
    val title: String,
    val affectedControl: String,
    val rawMetricValue: BigDecimal,
    val threshold: BigDecimal,
    val rootCause: String? = null,
    val proposalPrUrl: String? = null,
    val proposedFixDiff: String? = null,
    val status: FindingStatus = FindingStatus.OPEN,
    val diagnosedAt: Instant? = null,
    val proposedAt: Instant? = null,
)

data class LivenessRunReport(
    val runId: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val findingsDetected: List<LivenessFinding>,
    val findingsProposed: Int,
    val tokensUsed: Int,
    val trigger: RunTrigger,
)

enum class RunTrigger { SCHEDULED, ALERT_WEBHOOK, OPERATOR_MANUAL }
