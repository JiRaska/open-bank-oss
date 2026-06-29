// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.domain.model

import java.math.BigDecimal
import java.time.Instant

enum class DetectorId { D1_NAT_EGRESS, D2_CROSS_AZ, D3_NODE_CHURN, D4_EBS_HEALTH, D5_CI_RUNNER, D6_AI_TOKEN_BUDGET }

enum class AnomalySeverity { WARNING, CRITICAL }

enum class AnomalyStatus { OPEN, DIAGNOSED, PROPOSED, APPROVED, REJECTED, RESOLVED }

data class CostAnomaly(
    val id: String,
    val detector: DetectorId,
    val severity: AnomalySeverity,
    val detectedAt: Instant,
    val title: String,
    val rawMetricValue: BigDecimal,
    val threshold: BigDecimal,
    val affectedResource: String,
    val rootCause: String? = null,
    val proposalPrUrl: String? = null,
    val proposedIacDiff: String? = null,
    val estimatedMonthlySavingUsd: BigDecimal? = null,
    val status: AnomalyStatus = AnomalyStatus.OPEN,
    val diagnosedAt: Instant? = null,
    val proposedAt: Instant? = null,
)

data class FinOpsRunReport(
    val runId: String,
    val startedAt: Instant,
    val completedAt: Instant,
    val anomaliesDetected: List<CostAnomaly>,
    val anomaliesProposed: Int,
    val estimatedTotalMonthlySavingUsd: BigDecimal,
    val tokensUsed: Int,
    val trigger: RunTrigger,
)

enum class RunTrigger { SCHEDULED, ALERT_WEBHOOK, OPERATOR_MANUAL }
