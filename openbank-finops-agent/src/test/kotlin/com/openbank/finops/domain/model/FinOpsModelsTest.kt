// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class FinOpsModelsTest {

    @Test
    fun `CostAnomaly defaults to OPEN status`() {
        val anomaly = CostAnomaly(
            id = "test-id",
            detector = DetectorId.D1_NAT_EGRESS,
            severity = AnomalySeverity.WARNING,
            detectedAt = Instant.now(),
            title = "Test anomaly",
            rawMetricValue = BigDecimal.valueOf(55.0),
            threshold = BigDecimal.valueOf(50.0),
            affectedResource = "nat-gateway",
        )
        assertThat(anomaly.status).isEqualTo(AnomalyStatus.OPEN)
        assertThat(anomaly.rootCause).isNull()
        assertThat(anomaly.proposalPrUrl).isNull()
    }

    @Test
    fun `FinOpsRunReport accumulates total saving`() {
        val now = Instant.now()
        val anomaly = CostAnomaly(
            id = "a1",
            detector = DetectorId.D3_NODE_CHURN,
            severity = AnomalySeverity.CRITICAL,
            detectedAt = now,
            title = "Node churn",
            rawMetricValue = BigDecimal.valueOf(5.0),
            threshold = BigDecimal.valueOf(3.0),
            affectedResource = "karpenter/nodes",
            estimatedMonthlySavingUsd = BigDecimal.valueOf(200.0),
            status = AnomalyStatus.PROPOSED,
        )
        val report = FinOpsRunReport(
            runId = "run-1",
            startedAt = now,
            completedAt = now,
            anomaliesDetected = listOf(anomaly),
            anomaliesProposed = 1,
            estimatedTotalMonthlySavingUsd = BigDecimal.valueOf(200.0),
            tokensUsed = 0,
            trigger = RunTrigger.SCHEDULED,
        )
        assertThat(report.anomaliesProposed).isEqualTo(1)
        assertThat(report.estimatedTotalMonthlySavingUsd).isEqualByComparingTo(BigDecimal.valueOf(200.0))
    }

    @Test
    fun `DetectorId enum contains all expected detectors`() {
        assertThat(DetectorId.values()).containsExactlyInAnyOrder(
            DetectorId.D1_NAT_EGRESS,
            DetectorId.D2_CROSS_AZ,
            DetectorId.D3_NODE_CHURN,
            DetectorId.D4_EBS_HEALTH,
            DetectorId.D5_CI_RUNNER,
            DetectorId.D6_AI_TOKEN_BUDGET,
        )
    }
}
