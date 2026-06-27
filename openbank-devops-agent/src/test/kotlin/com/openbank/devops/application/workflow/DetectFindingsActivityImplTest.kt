// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.devops.application.workflow

import com.openbank.devops.domain.model.DetectorId
import com.openbank.devops.domain.model.DoraMetric
import com.openbank.devops.domain.model.FindingSeverity
import com.openbank.devops.infrastructure.config.DevOpsConfig
import io.mockk.every
import io.mockk.mockk
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

    @BeforeEach
    fun setUp() {
        val config = mockk<DevOpsConfig>()
        every { config.ciFailureRateThreshold() } returns 0.20
        every { config.changeFailureRateThreshold() } returns 0.05
        every { config.runnerQueuePressureThreshold() } returns 0.80
        every { config.incidentRecurrenceThreshold() } returns 3
        detect = DetectFindingsActivityImpl(config)
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
    fun `CI pipeline detector is inert when the exporter series is absent`() {
        // ci_runs_last_day == 0 means the github-actions exporter is not deployed yet.
        val findings = detect.detect(
            DetectorId.D1_CI_PIPELINE_HEALTH,
            mapOf("ci_failures_last_day" to 0.0, "ci_runs_last_day" to 0.0),
        )
        assertThat(findings).isEmpty()
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
}
