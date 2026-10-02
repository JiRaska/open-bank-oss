// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.application.workflow

import com.openbank.devops.domain.model.DetectorId
import com.openbank.devops.domain.model.FindingSeverity
import com.openbank.devops.domain.model.FindingStatus
import com.openbank.devops.domain.model.RemediationKind
import com.openbank.devops.infrastructure.config.DevOpsConfig
import com.openbank.libs.testing.trace.RecordingSpanExporter
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The exact threshold edges of the ADR-0119 detectors, and the severity split.
 *
 * Every detector compares with `<` (return) — so the threshold value itself FIRES. That off-by-one
 * is the difference between "3 recurrences is the trigger" (what the config comment promises) and
 * "4 is", and nothing else in the suite pins it. The severity split (`> threshold * 2`) is the
 * other unpinned edge: it decides whether a human is paged or a dashboard row appears.
 */
class DetectFindingsBoundaryTest {

    private lateinit var detect: DetectFindingsActivityImpl
    private lateinit var config: DevOpsConfig
    private lateinit var exporter: RecordingSpanExporter

    @BeforeEach
    fun setUp() {
        config = mockk()
        every { config.ciFailureRateThreshold() } returns 0.20
        every { config.changeFailureRateThreshold() } returns 0.05
        every { config.runnerQueuePressureThreshold() } returns 0.80
        every { config.ssdlcDriftThreshold() } returns 3
        every { config.incidentRecurrenceThreshold() } returns 3
        exporter = RecordingSpanExporter()
        val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
        detect = DetectFindingsActivityImpl(config, provider.get("test"))
    }

    @Test
    fun `a CI failure rate exactly at the threshold fires`() {
        assertThat(detect.detect(DetectorId.D1_CI_PIPELINE_HEALTH, mapOf("ci_failure_rate" to 0.20))).hasSize(1)
    }

    @Test
    fun `a CI failure rate a hair under the threshold does not`() {
        assertThat(detect.detect(DetectorId.D1_CI_PIPELINE_HEALTH, mapOf("ci_failure_rate" to 0.199))).isEmpty()
    }

    @Test
    fun `exactly double the CI threshold is still only a WARNING`() {
        // The split is `> threshold * 2`, not `>=`.
        val f = detect.detect(DetectorId.D1_CI_PIPELINE_HEALTH, mapOf("ci_failure_rate" to 0.40)).single()
        assertThat(f.severity).isEqualTo(FindingSeverity.WARNING)
        assertThat(f.remediationKind).isEqualTo(RemediationKind.PULL_REQUEST)
        assertThat(f.status).isEqualTo(FindingStatus.OPEN)
        assertThat(f.rawMetricValue).isEqualByComparingTo(BigDecimal("0.40"))
        assertThat(f.threshold).isEqualByComparingTo(BigDecimal("0.20"))
    }

    @Test
    fun `just over double the CI threshold escalates to CRITICAL`() {
        assertThat(
            detect.detect(DetectorId.D1_CI_PIPELINE_HEALTH, mapOf("ci_failure_rate" to 0.401)).single().severity,
        ).isEqualTo(FindingSeverity.CRITICAL)
    }

    @Test
    fun `the CFR proxy escalates only above double the threshold`() {
        assertThat(
            detect.detect(DetectorId.D2_DORA_REGRESSION, mapOf("change_failure_rate_proxy" to 0.10))
                .single().severity,
        ).isEqualTo(FindingSeverity.WARNING)
        assertThat(
            detect.detect(DetectorId.D2_DORA_REGRESSION, mapOf("change_failure_rate_proxy" to 0.101))
                .single().severity,
        ).isEqualTo(FindingSeverity.CRITICAL)
    }

    @Test
    fun `a CFR proxy of exactly zero produces no finding`() {
        assertThat(detect.detect(DetectorId.D2_DORA_REGRESSION, mapOf("change_failure_rate_proxy" to 0.0))).isEmpty()
    }

    @Test
    fun `SSDLC drift fires at the configured count, not one above it`() {
        assertThat(detect.detect(DetectorId.D5_SSDLC_HYGIENE, mapOf("open_fleet_health_issues" to 3.0))).hasSize(1)
        assertThat(detect.detect(DetectorId.D5_SSDLC_HYGIENE, mapOf("open_fleet_health_issues" to 2.0))).isEmpty()
    }

    @Test
    fun `incident recurrence fires at the configured count, not one above it`() {
        assertThat(detect.detect(DetectorId.D6_INCIDENT_RECURRENCE, mapOf("max_critical_alert_recurrence" to 3.0)))
            .hasSize(1)
        assertThat(detect.detect(DetectorId.D6_INCIDENT_RECURRENCE, mapOf("max_critical_alert_recurrence" to 2.0)))
            .isEmpty()
        assertThat(
            detect.detect(DetectorId.D6_INCIDENT_RECURRENCE, mapOf("max_critical_alert_recurrence" to 9.0))
                .single().remediationKind,
        ).isEqualTo(RemediationKind.RUNBOOK_UPDATE)
    }

    @Test
    fun `a runner pool with jobs assigned and a missing running series is treated as stranded`() {
        // arc_running_runners absent defaults to 0.0 — the series can vanish when the scale set has
        // no pods at all, which is exactly the stranded case, so the default must not mask it.
        val f = detect.detect(DetectorId.D3_RUNNER_CAPACITY, mapOf("arc_assigned_runners" to 2.0)).single()
        assertThat(f.severity).isEqualTo(FindingSeverity.CRITICAL)
        assertThat(f.rawMetricValue).isEqualByComparingTo(BigDecimal("2"))
    }

    @Test
    fun `an idle pool with zero assigned and zero running is not stranded`() {
        assertThat(
            detect.detect(
                DetectorId.D3_RUNNER_CAPACITY,
                mapOf("arc_assigned_runners" to 0.0, "arc_running_runners" to 0.0),
            ),
        ).isEmpty()
    }

    @Test
    fun `queue pressure exactly at the threshold fires as a WARNING`() {
        // 4 assigned / (4 running + 1) = 0.8 == threshold.
        val f = detect.detect(
            DetectorId.D3_RUNNER_CAPACITY,
            mapOf("arc_assigned_runners" to 4.0, "arc_running_runners" to 4.0),
        ).single()
        assertThat(f.severity).isEqualTo(FindingSeverity.WARNING)
        assertThat(f.title).contains("queue pressure")
    }

    @Test
    fun `zero firing rollout alerts is not a deploy-health finding`() {
        assertThat(detect.detect(DetectorId.D4_DEPLOY_HEALTH, mapOf("rollout_alerts_firing" to 0.0))).isEmpty()
    }

    @Test
    fun `every detector is inert on an empty signal map`() {
        DetectorId.entries.forEach { id ->
            assertThat(detect.detect(id, emptyMap())).describedAs("detector %s", id).isEmpty()
        }
    }

    @Test
    fun `each finding gets its own id`() {
        val a = detect.detect(DetectorId.D4_DEPLOY_HEALTH, mapOf("rollout_alerts_firing" to 1.0)).single()
        val b = detect.detect(DetectorId.D4_DEPLOY_HEALTH, mapOf("rollout_alerts_firing" to 1.0)).single()
        assertThat(a.id).isNotEqualTo(b.id)
    }

    @Test
    fun `a detector failure is recorded on the span and rethrown unchanged`() {
        every { config.ciFailureRateThreshold() } throws IllegalStateException("config unavailable")

        assertThatThrownBy { detect.detect(DetectorId.D1_CI_PIPELINE_HEALTH, mapOf("ci_failure_rate" to 0.5)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("config unavailable")

        // The span must be closed AND marked ERROR — a detector that throws while its span still
        // reads OK is invisible in the trace, which is where a silently failing detector hides.
        exporter.contract().requiresSpan("devops-agent.detector.evaluate")
        assertThatThrownBy { exporter.contract().hasNoErrorSpan() }
            .isInstanceOf(AssertionError::class.java)
    }
}
