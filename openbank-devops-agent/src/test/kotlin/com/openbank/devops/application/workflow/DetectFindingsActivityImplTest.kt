// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.application.workflow

import com.openbank.devops.domain.model.DetectorId
import com.openbank.devops.domain.model.DoraMetric
import com.openbank.devops.domain.model.FindingSeverity
import com.openbank.devops.infrastructure.config.DevOpsConfig
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Detector unit tests (ADR-0119). Pure-function detectors over synthetic signal maps — no
 * Temporal, no Prometheus. The runner-capacity cases pin the behaviour that would have caught
 * the 2026-06-27 openbank-batch incident (0 online runners with jobs assigned -> CRITICAL).
 */
class DetectFindingsActivityImplTest {

    private lateinit var detect: DetectFindingsActivityImpl
    private lateinit var exporter: RecordingSpanExporter
    private lateinit var tracerProvider: SdkTracerProvider

    @BeforeEach
    fun setUp() {
        val config = mockk<DevOpsConfig>()
        every { config.ciFailureRateThreshold() } returns 0.20
        every { config.changeFailureRateThreshold() } returns 0.05
        every { config.runnerQueuePressureThreshold() } returns 0.80
        every { config.ssdlcDriftThreshold() } returns 3
        every { config.incidentRecurrenceThreshold() } returns 3
        exporter = RecordingSpanExporter()
        tracerProvider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
        detect = DetectFindingsActivityImpl(config, tracerProvider.get("test"))
    }

    @Test
    fun `stranded runner pool - jobs assigned but zero running - is CRITICAL`() {
        val findings = detect.detect(
            DetectorId.D3_RUNNER_CAPACITY,
            mapOf("arc_assigned_runners" to 3.0, "arc_running_runners" to 0.0),
        )
        assertThat(findings).hasSize(1)
        val f = findings.single()
        assertThat(f.severity).isEqualTo(FindingSeverity.CRITICAL)
        assertThat(f.affectedResource).isEqualTo("arc-runners")
        assertThat(f.doraMetricImpacted).isEqualTo(DoraMetric.LEAD_TIME_FOR_CHANGES)
        assertThat(f.title).contains("stranded")
    }

    @Test
    fun `healthy runner pool - running keeps up with assigned - yields no finding`() {
        val findings = detect.detect(
            DetectorId.D3_RUNNER_CAPACITY,
            mapOf("arc_assigned_runners" to 1.0, "arc_running_runners" to 2.0),
        )
        assertThat(findings).isEmpty()
    }

    @Test
    fun `runner queue pressure over threshold but pool alive is WARNING`() {
        val findings = detect.detect(
            DetectorId.D3_RUNNER_CAPACITY,
            mapOf("arc_assigned_runners" to 10.0, "arc_running_runners" to 1.0),
        )
        assertThat(findings).hasSize(1)
        assertThat(findings.single().severity).isEqualTo(FindingSeverity.WARNING)
    }

    @Test
    fun `change failure rate proxy over threshold trips a DORA regression finding`() {
        val findings = detect.detect(
            DetectorId.D2_DORA_REGRESSION,
            mapOf("change_failure_rate_proxy" to 0.12),
        )
        assertThat(findings).hasSize(1)
        assertThat(findings.single().doraMetricImpacted).isEqualTo(DoraMetric.CHANGE_FAILURE_RATE)
    }

    @Test
    fun `CI pipeline detector is inert when the failure-rate signal is absent`() {
        // No ci_failure_rate key means the GitHub token isn't seeded -> no signal -> inert.
        val findings = detect.detect(DetectorId.D1_CI_PIPELINE_HEALTH, emptyMap())
        assertThat(findings).isEmpty()
    }

    @Test
    fun `CI pipeline failure rate over threshold trips a finding`() {
        val findings = detect.detect(DetectorId.D1_CI_PIPELINE_HEALTH, mapOf("ci_failure_rate" to 0.45))
        assertThat(findings).hasSize(1)
        assertThat(findings.single().severity).isEqualTo(FindingSeverity.CRITICAL) // 0.45 > 0.20*2
        assertThat(findings.single().affectedResource).isEqualTo("github-actions/workflows")
    }

    @Test
    fun `SSDLC drift - open fleet-health issues over threshold - trips a finding`() {
        val findings = detect.detect(DetectorId.D5_SSDLC_HYGIENE, mapOf("open_fleet_health_issues" to 5.0))
        assertThat(findings).hasSize(1)
        assertThat(findings.single().affectedResource).isEqualTo("github/fleet-health-issues")
    }

    @Test
    fun `SSDLC detector is inert when the signal is absent`() {
        assertThat(detect.detect(DetectorId.D5_SSDLC_HYGIENE, emptyMap())).isEmpty()
    }

    @Test
    fun `firing rollout alert trips a critical deploy-health finding`() {
        val findings = detect.detect(
            DetectorId.D4_DEPLOY_HEALTH,
            mapOf("rollout_alerts_firing" to 1.0),
        )
        assertThat(findings).hasSize(1)
        assertThat(findings.single().severity).isEqualTo(FindingSeverity.CRITICAL)
    }

    @Test
    fun `recurring critical alert at threshold trips the learning-loop finding`() {
        val findings = detect.detect(
            DetectorId.D6_INCIDENT_RECURRENCE,
            mapOf("max_critical_alert_recurrence" to 3.0),
        )
        assertThat(findings).hasSize(1)
        assertThat(findings.single().doraMetricImpacted).isEqualTo(DoraMetric.TIME_TO_RESTORE)
    }

    @Test
    fun `detector evaluation emits an assertion-backed trace contract`() {
        detect.detect(DetectorId.D3_RUNNER_CAPACITY, mapOf("arc_assigned_runners" to 3.0, "arc_running_runners" to 0.0))

        exporter.contract()
            .requiresSpan("devops-agent.detector.evaluate")
            .requiresAttribute("devops-agent.detector.evaluate", "openbank.devops.detector")
            .requiresAttribute("devops-agent.detector.evaluate", "openbank.devops.findings.count")
            .hasNoErrorSpan()
            .verifiedAs("devops-detector-evaluate")
    }
}
