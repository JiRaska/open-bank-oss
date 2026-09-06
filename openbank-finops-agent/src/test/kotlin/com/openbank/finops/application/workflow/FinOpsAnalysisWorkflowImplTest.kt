// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.finops.application.workflow

import com.openbank.finops.domain.model.AnomalySeverity
import com.openbank.finops.domain.model.AnomalyStatus
import com.openbank.finops.domain.model.CostAnomaly
import com.openbank.finops.domain.model.DetectorId
import com.openbank.finops.domain.model.RunTrigger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.temporal.workflow.Workflow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The orchestration decisions, driven over stubbed activities.
 *
 * The interesting ones are the HITL gate — only CRITICAL findings, or ones that already carry a
 * saving estimate, are proposed — and the two report aggregates, which are the numbers a human
 * reads. `anomaliesProposed` counting `status == PROPOSED` rather than "propose was called" is
 * exactly what keeps a refused proposal (#5897) out of the total.
 */
class FinOpsAnalysisWorkflowImplTest {

    private val collect = mockk<CollectMetricsActivity>(relaxed = true)
    private val detect = mockk<DetectAnomaliesActivity>()
    private val diagnosePropose = mockk<DiagnoseAndProposeActivity>()

    @BeforeEach
    fun stubWorkflowStatics() {
        mockkStatic(Workflow::class)
        every { Workflow.newActivityStub(CollectMetricsActivity::class.java, any()) } returns collect
        every { Workflow.newActivityStub(DetectAnomaliesActivity::class.java, any()) } returns detect
        every { Workflow.newActivityStub(DiagnoseAndProposeActivity::class.java, any()) } returns diagnosePropose
        every { Workflow.randomUUID() } returns UUID.fromString("00000000-0000-4000-8000-000000000001")
        every { Workflow.currentTimeMillis() } returnsMany listOf(1_000L, 61_000L)
        every { detect.detect(any(), any()) } returns emptyList()
    }

    @AfterEach
    fun unstub() = unmockkStatic(Workflow::class)

    private fun anomaly(
        id: String,
        detector: DetectorId,
        severity: AnomalySeverity,
        saving: BigDecimal? = null,
        status: AnomalyStatus = AnomalyStatus.OPEN,
    ) = CostAnomaly(
        id = id,
        detector = detector,
        severity = severity,
        detectedAt = Instant.EPOCH,
        title = id,
        rawMetricValue = BigDecimal.TEN,
        threshold = BigDecimal.ONE,
        affectedResource = "res",
        estimatedMonthlySavingUsd = saving,
        status = status,
    )

    @Test
    fun `a quiet estate produces an empty report rather than no report at all`() {
        val report = FinOpsAnalysisWorkflowImpl().runAnalysis(RunTrigger.SCHEDULED)

        assertThat(report.anomaliesDetected).isEmpty()
        assertThat(report.anomaliesProposed).isZero()
        assertThat(report.estimatedTotalMonthlySavingUsd).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(report.trigger).isEqualTo(RunTrigger.SCHEDULED)
        assertThat(report.runId).isEqualTo("00000000-0000-4000-8000-000000000001")
        assertThat(report.startedAt).isEqualTo(Instant.ofEpochMilli(1_000L))
        assertThat(report.completedAt).isEqualTo(Instant.ofEpochMilli(61_000L))
    }

    @Test
    fun `the five wired detectors are each asked once, and D2 cross-AZ is not run at all`() {
        FinOpsAnalysisWorkflowImpl().runAnalysis(RunTrigger.SCHEDULED)

        verify(exactly = 1) { detect.detect(DetectorId.D1_NAT_EGRESS, any()) }
        verify(exactly = 1) { detect.detect(DetectorId.D3_NODE_CHURN, any()) }
        verify(exactly = 1) { detect.detect(DetectorId.D4_EBS_HEALTH, any()) }
        verify(exactly = 1) { detect.detect(DetectorId.D5_CI_RUNNER, any()) }
        verify(exactly = 1) { detect.detect(DetectorId.D6_AI_TOKEN_BUDGET, any()) }
        verify(exactly = 0) { detect.detect(DetectorId.D2_CROSS_AZ, any()) }
        verify(exactly = 1) { collect.collectNatEgressMetrics() }
        verify(exactly = 1) { collect.collectKarpenterMetrics() }
        verify(exactly = 1) { collect.collectEbsHealthMetrics() }
        verify(exactly = 1) { collect.collectCiRunnerMetrics() }
        verify(exactly = 1) { collect.collectAiTokenMetrics() }
    }

    @Test
    fun `each detector is fed its OWN collected metrics, never another detector's`() {
        every { collect.collectNatEgressMetrics() } returns mapOf("nat" to 1.0)
        every { collect.collectKarpenterMetrics() } returns mapOf("karpenter" to 1.0)

        FinOpsAnalysisWorkflowImpl().runAnalysis(RunTrigger.SCHEDULED)

        verify { detect.detect(DetectorId.D1_NAT_EGRESS, mapOf("nat" to 1.0)) }
        verify { detect.detect(DetectorId.D3_NODE_CHURN, mapOf("karpenter" to 1.0)) }
    }

    @Test
    fun `a WARNING with no saving estimate is diagnosed but never proposed`() {
        val warning = anomaly("w1", DetectorId.D3_NODE_CHURN, AnomalySeverity.WARNING)
        every { detect.detect(DetectorId.D3_NODE_CHURN, any()) } returns listOf(warning)
        every { diagnosePropose.diagnose(warning, any()) } returns
            warning.copy(status = AnomalyStatus.DIAGNOSED, rootCause = "cause")

        val report = FinOpsAnalysisWorkflowImpl().runAnalysis(RunTrigger.SCHEDULED)

        verify(exactly = 0) { diagnosePropose.propose(any()) }
        assertThat(report.anomaliesDetected).singleElement()
            .extracting("status").isEqualTo(AnomalyStatus.DIAGNOSED)
        assertThat(report.anomaliesProposed).isZero()
    }

    @Test
    fun `a CRITICAL finding is proposed`() {
        val critical = anomaly("c1", DetectorId.D4_EBS_HEALTH, AnomalySeverity.CRITICAL)
        val diagnosed = critical.copy(status = AnomalyStatus.DIAGNOSED, rootCause = "cause")
        every { detect.detect(DetectorId.D4_EBS_HEALTH, any()) } returns listOf(critical)
        every { diagnosePropose.diagnose(critical, any()) } returns diagnosed
        every { diagnosePropose.propose(diagnosed) } returns
            diagnosed.copy(status = AnomalyStatus.PROPOSED, proposalPrUrl = "https://example/pr/1")

        val report = FinOpsAnalysisWorkflowImpl().runAnalysis(RunTrigger.SCHEDULED)

        verify(exactly = 1) { diagnosePropose.propose(diagnosed) }
        assertThat(report.anomaliesProposed).isEqualTo(1)
    }

    @Test
    fun `a WARNING that carries a saving estimate is proposed too`() {
        val warning = anomaly("w2", DetectorId.D5_CI_RUNNER, AnomalySeverity.WARNING)
        val diagnosed = warning.copy(
            status = AnomalyStatus.DIAGNOSED,
            estimatedMonthlySavingUsd = BigDecimal.valueOf(120),
        )
        every { detect.detect(DetectorId.D5_CI_RUNNER, any()) } returns listOf(warning)
        every { diagnosePropose.diagnose(warning, any()) } returns diagnosed
        every { diagnosePropose.propose(diagnosed) } returns diagnosed.copy(status = AnomalyStatus.PROPOSED)

        val report = FinOpsAnalysisWorkflowImpl().runAnalysis(RunTrigger.SCHEDULED)

        verify(exactly = 1) { diagnosePropose.propose(diagnosed) }
        assertThat(report.anomaliesProposed).isEqualTo(1)
    }

    @Test
    fun `the gate reads the DIAGNOSED copy, so a saving discovered during diagnosis promotes it`() {
        // The pre-diagnosis anomaly is a plain WARNING with no estimate — it would not qualify.
        val warning = anomaly("w3", DetectorId.D1_NAT_EGRESS, AnomalySeverity.WARNING)
        val diagnosed = warning.copy(
            severity = AnomalySeverity.CRITICAL,
            status = AnomalyStatus.DIAGNOSED,
        )
        every { detect.detect(DetectorId.D1_NAT_EGRESS, any()) } returns listOf(warning)
        every { diagnosePropose.diagnose(warning, any()) } returns diagnosed
        every { diagnosePropose.propose(diagnosed) } returns diagnosed.copy(status = AnomalyStatus.PROPOSED)

        FinOpsAnalysisWorkflowImpl().runAnalysis(RunTrigger.SCHEDULED)

        verify(exactly = 1) { diagnosePropose.propose(diagnosed) }
    }

    @Test
    fun `a proposal that was refused is not counted, even though propose was called`() {
        val critical = anomaly("c2", DetectorId.D4_EBS_HEALTH, AnomalySeverity.CRITICAL)
        val diagnosed = critical.copy(status = AnomalyStatus.DIAGNOSED)
        every { detect.detect(DetectorId.D4_EBS_HEALTH, any()) } returns listOf(critical)
        every { diagnosePropose.diagnose(critical, any()) } returns diagnosed
        // The refusal branch: propose returns the anomaly unchanged, still DIAGNOSED.
        every { diagnosePropose.propose(diagnosed) } returns diagnosed

        val report = FinOpsAnalysisWorkflowImpl().runAnalysis(RunTrigger.SCHEDULED)

        verify(exactly = 1) { diagnosePropose.propose(diagnosed) }
        assertThat(report.anomaliesProposed).isZero()
    }

    @Test
    fun `savings sum across findings and a null estimate contributes zero, not a failure`() {
        val a = anomaly("a", DetectorId.D1_NAT_EGRESS, AnomalySeverity.CRITICAL)
        val b = anomaly("b", DetectorId.D3_NODE_CHURN, AnomalySeverity.CRITICAL)
        val aDone = a.copy(status = AnomalyStatus.PROPOSED, estimatedMonthlySavingUsd = BigDecimal.valueOf(150.50))
        val bDone = b.copy(status = AnomalyStatus.PROPOSED, estimatedMonthlySavingUsd = null)
        every { detect.detect(DetectorId.D1_NAT_EGRESS, any()) } returns listOf(a)
        every { detect.detect(DetectorId.D3_NODE_CHURN, any()) } returns listOf(b)
        every { diagnosePropose.diagnose(a, any()) } returns a.copy(status = AnomalyStatus.DIAGNOSED)
        every { diagnosePropose.diagnose(b, any()) } returns b.copy(status = AnomalyStatus.DIAGNOSED)
        every { diagnosePropose.propose(a.copy(status = AnomalyStatus.DIAGNOSED)) } returns aDone
        every { diagnosePropose.propose(b.copy(status = AnomalyStatus.DIAGNOSED)) } returns bDone

        val report = FinOpsAnalysisWorkflowImpl().runAnalysis(RunTrigger.ALERT_WEBHOOK)

        assertThat(report.anomaliesDetected).hasSize(2)
        assertThat(report.anomaliesProposed).isEqualTo(2)
        assertThat(report.estimatedTotalMonthlySavingUsd).isEqualByComparingTo(BigDecimal.valueOf(150.50))
        assertThat(report.trigger).isEqualTo(RunTrigger.ALERT_WEBHOOK)
    }

    @Test
    fun `tokensUsed is reported as zero - the agent does not yet meter its own LLM spend`() {
        val report = FinOpsAnalysisWorkflowImpl().runAnalysis(RunTrigger.OPERATOR_MANUAL)

        assertThat(report.tokensUsed).isZero()
    }
}
