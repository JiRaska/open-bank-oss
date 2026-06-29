// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.application.workflow

import com.openbank.finops.domain.model.AnomalySeverity
import com.openbank.finops.domain.model.AnomalyStatus
import com.openbank.finops.domain.model.CostAnomaly
import com.openbank.finops.domain.model.DetectorId
import com.openbank.finops.infrastructure.config.FinOpsConfig
import com.openbank.libs.domain.identifiers.Ids
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Instant

@ApplicationScoped
class DetectAnomaliesActivityImpl(private val config: FinOpsConfig) : DetectAnomaliesActivity {

    override fun detect(detectorId: DetectorId, metrics: Map<String, Double>): List<CostAnomaly> = when (detectorId) {
        DetectorId.D1_NAT_EGRESS -> detectNatEgress(metrics)
        DetectorId.D3_NODE_CHURN -> detectNodeChurn(metrics)
        DetectorId.D4_EBS_HEALTH -> detectEbsHealth(metrics)
        DetectorId.D5_CI_RUNNER -> detectCiRunner(metrics)
        DetectorId.D6_AI_TOKEN_BUDGET -> detectAiTokenBudget(metrics)
        else -> emptyList()
    }

    @Suppress("MagicNumber")
    private fun detectNatEgress(metrics: Map<String, Double>): List<CostAnomaly> {
        val egressBytes = metrics["nat_egress_bytes_total"] ?: return emptyList()
        val egressGb = egressBytes / (1024.0 * 1024.0 * 1024.0)
        val thresholdGb = config.natEgressThresholdGb().toDouble()
        if (egressGb < thresholdGb) return emptyList()
        return listOf(
            CostAnomaly(
                id = Ids.newId().toString(),
                detector = DetectorId.D1_NAT_EGRESS,
                severity = if (egressGb > thresholdGb * 2) AnomalySeverity.CRITICAL else AnomalySeverity.WARNING,
                detectedAt = Instant.now(),
                title = "NAT egress spike: %.1f GB/h (threshold %.0f GB/h)".format(egressGb, thresholdGb),
                rawMetricValue = BigDecimal.valueOf(egressGb),
                threshold = BigDecimal.valueOf(thresholdGb),
                affectedResource = "nat-gateway",
                status = AnomalyStatus.OPEN,
            ),
        )
    }

    private fun detectNodeChurn(metrics: Map<String, Double>): List<CostAnomaly> {
        val terminationsPerHour = metrics["node_terminations_per_hour"] ?: return emptyList()
        val threshold = config.nodeChurnThresholdPerHour().toDouble()
        if (terminationsPerHour < threshold) return emptyList()
        return listOf(
            CostAnomaly(
                id = Ids.newId().toString(),
                detector = DetectorId.D3_NODE_CHURN,
                severity = AnomalySeverity.WARNING,
                detectedAt = Instant.now(),
                title = "High Karpenter node churn: %.0f terminations/h".format(terminationsPerHour),
                rawMetricValue = BigDecimal.valueOf(terminationsPerHour),
                threshold = BigDecimal.valueOf(threshold),
                affectedResource = "karpenter/nodes",
                status = AnomalyStatus.OPEN,
            ),
        )
    }

    @Suppress("MagicNumber")
    private fun detectEbsHealth(metrics: Map<String, Double>): List<CostAnomaly> {
        val multiAttachEvents = metrics["ebs_multi_attach_events"] ?: return emptyList()
        if (multiAttachEvents < 1.0) return emptyList()
        return listOf(
            CostAnomaly(
                id = Ids.newId().toString(),
                detector = DetectorId.D4_EBS_HEALTH,
                severity = AnomalySeverity.CRITICAL,
                detectedAt = Instant.now(),
                title = "EBS Multi-Attach events detected: %.0f in last hour".format(multiAttachEvents),
                rawMetricValue = BigDecimal.valueOf(multiAttachEvents),
                threshold = BigDecimal.ZERO,
                affectedResource = "ebs/persistent-volumes",
                status = AnomalyStatus.OPEN,
            ),
        )
    }

    @Suppress("MagicNumber")
    private fun detectCiRunner(metrics: Map<String, Double>): List<CostAnomaly> {
        val runnerCores = metrics["arc_runner_cpu_cores"] ?: return emptyList()
        if (runnerCores < 32.0) return emptyList()
        return listOf(
            CostAnomaly(
                id = Ids.newId().toString(),
                detector = DetectorId.D5_CI_RUNNER,
                severity = AnomalySeverity.WARNING,
                detectedAt = Instant.now(),
                title = "CI runner over-provisioned: %.0f cores allocated".format(runnerCores),
                rawMetricValue = BigDecimal.valueOf(runnerCores),
                threshold = BigDecimal.valueOf(32.0),
                affectedResource = "arc-runners",
                status = AnomalyStatus.OPEN,
            ),
        )
    }

    @Suppress("MagicNumber")
    private fun detectAiTokenBudget(metrics: Map<String, Double>): List<CostAnomaly> {
        val tokensToday = metrics["holmesgpt_tokens_used_today"] ?: return emptyList()
        val budgetTokens = 500_000.0
        if (tokensToday < budgetTokens * 0.8) return emptyList()
        return listOf(
            CostAnomaly(
                id = Ids.newId().toString(),
                detector = DetectorId.D6_AI_TOKEN_BUDGET,
                severity = if (tokensToday > budgetTokens) AnomalySeverity.CRITICAL else AnomalySeverity.WARNING,
                detectedAt = Instant.now(),
                title = "AI token budget at %.0f%% (%.0f / %.0f tokens today)".format(
                    tokensToday / budgetTokens * 100,
                    tokensToday,
                    budgetTokens,
                ),
                rawMetricValue = BigDecimal.valueOf(tokensToday),
                threshold = BigDecimal.valueOf(budgetTokens * 0.8),
                affectedResource = "holmesgpt/daily-budget",
                status = AnomalyStatus.OPEN,
            ),
        )
    }
}
