// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.application.workflow

import com.openbank.finops.domain.model.AnomalySeverity
import com.openbank.finops.domain.model.AnomalyStatus
import com.openbank.finops.domain.model.DetectorId
import com.openbank.finops.infrastructure.config.FinOpsConfig
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The threshold arithmetic is the whole detector. Every test here sits ON a boundary or just
 * either side of one, because a detector that fires one notch late is indistinguishable from a
 * quiet estate — the same "empty table reads as healthy" trap the scheduler's KDoc describes.
 */
class DetectAnomaliesActivityImplTest {

    private val config = mockk<FinOpsConfig>().also {
        every { it.natEgressThresholdGb() } returns THRESHOLD_GB
        every { it.nodeChurnThresholdPerHour() } returns CHURN_THRESHOLD
    }
    private val detector = DetectAnomaliesActivityImpl(config)

    private fun gb(count: Double) = count * 1024.0 * 1024.0 * 1024.0

    // --- D1 NAT egress ---------------------------------------------------------------------

    @Test
    fun `a missing NAT metric detects nothing rather than treating absence as zero-or-spike`() {
        assertThat(detector.detect(DetectorId.D1_NAT_EGRESS, emptyMap())).isEmpty()
        assertThat(detector.detect(DetectorId.D1_NAT_EGRESS, mapOf("some_other_metric" to 1e12))).isEmpty()
    }

    @Test
    fun `NAT egress just under the threshold is not an anomaly`() {
        assertThat(
            detector.detect(DetectorId.D1_NAT_EGRESS, mapOf("nat_egress_bytes_total" to gb(49.9))),
        ).isEmpty()
    }

    @Test
    fun `NAT egress exactly at the threshold fires - the comparison is strictly-less`() {
        val found = detector.detect(DetectorId.D1_NAT_EGRESS, mapOf("nat_egress_bytes_total" to gb(50.0)))

        assertThat(found).hasSize(1)
        assertThat(found[0].detector).isEqualTo(DetectorId.D1_NAT_EGRESS)
        assertThat(found[0].severity).isEqualTo(AnomalySeverity.WARNING)
        assertThat(found[0].status).isEqualTo(AnomalyStatus.OPEN)
        assertThat(found[0].affectedResource).isEqualTo("nat-gateway")
        assertThat(found[0].threshold).isEqualByComparingTo(BigDecimal.valueOf(50.0))
        assertThat(found[0].rawMetricValue.toDouble()).isEqualTo(50.0)
    }

    @Test
    fun `NAT severity escalates to CRITICAL only strictly above twice the threshold`() {
        val atDouble = detector.detect(DetectorId.D1_NAT_EGRESS, mapOf("nat_egress_bytes_total" to gb(100.0)))
        val overDouble = detector.detect(DetectorId.D1_NAT_EGRESS, mapOf("nat_egress_bytes_total" to gb(100.1)))

        assertThat(atDouble[0].severity).isEqualTo(AnomalySeverity.WARNING)
        assertThat(overDouble[0].severity).isEqualTo(AnomalySeverity.CRITICAL)
    }

    @Test
    fun `the NAT title reports gigabytes, not the raw byte count`() {
        val found = detector.detect(DetectorId.D1_NAT_EGRESS, mapOf("nat_egress_bytes_total" to gb(64.0)))

        assertThat(found[0].title)
            .startsWith("NAT egress spike: 64")
            .endsWith("GB/h (threshold 50 GB/h)")
    }

    @Test
    fun `the configured NAT threshold is what decides, not a hardcoded 50`() {
        val tight = mockk<FinOpsConfig>()
        every { tight.natEgressThresholdGb() } returns 10

        val found = DetectAnomaliesActivityImpl(tight)
            .detect(DetectorId.D1_NAT_EGRESS, mapOf("nat_egress_bytes_total" to gb(12.0)))

        assertThat(found).hasSize(1)
        assertThat(found[0].threshold).isEqualByComparingTo(BigDecimal.TEN)
    }

    // --- D3 node churn ---------------------------------------------------------------------

    @Test
    fun `node churn reads terminations, and creations alone never fire it`() {
        assertThat(
            detector.detect(DetectorId.D3_NODE_CHURN, mapOf("node_creations_per_hour" to 99.0)),
        ).isEmpty()
    }

    @Test
    fun `node churn fires at the configured threshold and not below it`() {
        assertThat(detector.detect(DetectorId.D3_NODE_CHURN, mapOf("node_terminations_per_hour" to 2.0))).isEmpty()

        val found = detector.detect(DetectorId.D3_NODE_CHURN, mapOf("node_terminations_per_hour" to 3.0))
        assertThat(found).hasSize(1)
        assertThat(found[0].severity).isEqualTo(AnomalySeverity.WARNING)
        assertThat(found[0].affectedResource).isEqualTo("karpenter/nodes")
        assertThat(found[0].threshold).isEqualByComparingTo(BigDecimal.valueOf(3.0))
    }

    // --- D4 EBS health ---------------------------------------------------------------------

    @Test
    fun `a single EBS multi-attach event is already CRITICAL, and zero is not an anomaly`() {
        assertThat(detector.detect(DetectorId.D4_EBS_HEALTH, mapOf("ebs_multi_attach_events" to 0.0))).isEmpty()

        val found = detector.detect(DetectorId.D4_EBS_HEALTH, mapOf("ebs_multi_attach_events" to 1.0))
        assertThat(found).hasSize(1)
        assertThat(found[0].severity).isEqualTo(AnomalySeverity.CRITICAL)
        assertThat(found[0].threshold).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(found[0].title).isEqualTo("EBS Multi-Attach events detected: 1 in last hour")
    }

    // --- D5 CI runner ----------------------------------------------------------------------

    @Test
    fun `CI runner fires at 32 cores and not at 31`() {
        assertThat(detector.detect(DetectorId.D5_CI_RUNNER, mapOf("arc_runner_cpu_cores" to 31.9))).isEmpty()

        val found = detector.detect(DetectorId.D5_CI_RUNNER, mapOf("arc_runner_cpu_cores" to 32.0))
        assertThat(found).hasSize(1)
        assertThat(found[0].severity).isEqualTo(AnomalySeverity.WARNING)
        assertThat(found[0].affectedResource).isEqualTo("arc-runners")
    }

    @Test
    fun `CI runner reads cores, so the runner count alone cannot fire it`() {
        assertThat(detector.detect(DetectorId.D5_CI_RUNNER, mapOf("arc_runner_count" to 500.0))).isEmpty()
    }

    // --- D6 AI token budget ----------------------------------------------------------------

    @Test
    fun `token budget stays quiet below 80 percent and warns from 80 percent`() {
        assertThat(
            detector.detect(DetectorId.D6_AI_TOKEN_BUDGET, mapOf("holmesgpt_tokens_used_today" to 399_999.0)),
        ).isEmpty()

        val warn = detector.detect(DetectorId.D6_AI_TOKEN_BUDGET, mapOf("holmesgpt_tokens_used_today" to 400_000.0))
        assertThat(warn).hasSize(1)
        assertThat(warn[0].severity).isEqualTo(AnomalySeverity.WARNING)
        assertThat(warn[0].threshold).isEqualByComparingTo(BigDecimal.valueOf(400_000.0))
        assertThat(warn[0].title).isEqualTo("AI token budget at 80% (400000 / 500000 tokens today)")
    }

    @Test
    fun `token budget is CRITICAL only strictly over budget, not at exactly 100 percent`() {
        val atBudget = detector.detect(
            DetectorId.D6_AI_TOKEN_BUDGET,
            mapOf("holmesgpt_tokens_used_today" to 500_000.0),
        )
        val overBudget = detector.detect(
            DetectorId.D6_AI_TOKEN_BUDGET,
            mapOf("holmesgpt_tokens_used_today" to 500_001.0),
        )

        assertThat(atBudget[0].severity).isEqualTo(AnomalySeverity.WARNING)
        assertThat(overBudget[0].severity).isEqualTo(AnomalySeverity.CRITICAL)
    }

    // --- unimplemented detector ------------------------------------------------------------

    @Test
    fun `D2 cross-AZ is not implemented and detects nothing whatever it is fed`() {
        assertThat(
            detector.detect(
                DetectorId.D2_CROSS_AZ,
                mapOf("nat_egress_bytes_total" to gb(500.0), "ebs_multi_attach_events" to 9.0),
            ),
        ).isEmpty()
    }

    @Test
    fun `each detector reads only its own metric key`() {
        val everything = mapOf(
            "nat_egress_bytes_total" to gb(80.0),
            "node_terminations_per_hour" to 9.0,
            "ebs_multi_attach_events" to 4.0,
            "arc_runner_cpu_cores" to 64.0,
            "holmesgpt_tokens_used_today" to 600_000.0,
        )
        DetectorId.entries.filter { it != DetectorId.D2_CROSS_AZ }.forEach { id ->
            assertThat(detector.detect(id, everything))
                .describedAs("detector %s over a full metric map", id)
                .hasSize(1)
            assertThat(detector.detect(id, everything)[0].detector).isEqualTo(id)
        }
    }

    @Test
    fun `ids are unique per detection so two findings never collide in the repository`() {
        val metrics = mapOf("ebs_multi_attach_events" to 2.0)
        val first = detector.detect(DetectorId.D4_EBS_HEALTH, metrics)[0]
        val second = detector.detect(DetectorId.D4_EBS_HEALTH, metrics)[0]

        assertThat(first.id).isNotEqualTo(second.id)
    }

    private companion object {
        const val THRESHOLD_GB = 50
        const val CHURN_THRESHOLD = 3
    }
}
