// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.devops.application.workflow

import com.openbank.devops.domain.model.DetectorId
import com.openbank.devops.domain.model.DevOpsFinding
import com.openbank.devops.domain.model.DoraMetric
import com.openbank.devops.domain.model.FindingSeverity
import com.openbank.devops.domain.model.FindingStatus
import com.openbank.devops.domain.model.RemediationKind
import com.openbank.devops.infrastructure.config.DevOpsConfig
import com.openbank.libs.domain.identifiers.Ids
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Instant

/**
 * Threshold detectors over the collected signals (ADR-0119). Pure functions — no I/O — so the
 * Temporal activity is deterministic and cheap to unit-test. Each detector emits zero or one
 * finding tagged with the DORA metric it puts at risk and the kind of durable remediation that
 * would fix it (the agent's whole purpose: a permanent fix, not a band-aid).
 */
@ApplicationScoped
class DetectFindingsActivityImpl(private val config: DevOpsConfig, private val tracer: Tracer) :
    DetectFindingsActivity {

    /**
     * The detector boundary is where collected observability signals become an actionable finding.
     * Its span contains only the bounded detector enum and result count — never raw signals, titles,
     * resource names, or remediation payloads.
     */
    override fun detect(detectorId: DetectorId, signals: Map<String, Double>): List<DevOpsFinding> {
        val span = tracer.spanBuilder("devops-agent.detector.evaluate")
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan()
        return try {
            val findings = when (detectorId) {
                DetectorId.D1_CI_PIPELINE_HEALTH -> detectCiPipeline(signals)
                DetectorId.D2_DORA_REGRESSION -> detectDoraRegression(signals)
                DetectorId.D3_RUNNER_CAPACITY -> detectRunnerCapacity(signals)
                DetectorId.D4_DEPLOY_HEALTH -> detectDeployHealth(signals)
                DetectorId.D5_SSDLC_HYGIENE -> detectSsdlcHygiene(signals)
                DetectorId.D6_INCIDENT_RECURRENCE -> detectIncidentRecurrence(signals)
            }
            span.setAttribute("openbank.devops.detector", detectorId.name)
            span.setAttribute("openbank.devops.findings.count", findings.size.toLong())
            findings
        } catch (failure: Exception) {
            span.recordException(failure)
            span.setStatus(StatusCode.ERROR)
            throw failure
        } finally {
            span.end()
        }
    }

    @Suppress("MagicNumber")
    private fun detectCiPipeline(signals: Map<String, Double>): List<DevOpsFinding> {
        // ci_failure_rate is absent when the GitHub token isn't seeded -> detector inert, never noisy.
        val failureRate = signals["ci_failure_rate"] ?: return emptyList()
        val threshold = config.ciFailureRateThreshold()
        if (failureRate < threshold) return emptyList()
        return listOf(
            finding(
                detector = DetectorId.D1_CI_PIPELINE_HEALTH,
                severity = if (failureRate > threshold * 2) FindingSeverity.CRITICAL else FindingSeverity.WARNING,
                title = "CI pipeline failure rate %.0f%% over recent runs (threshold %.0f%%)".format(
                    failureRate * 100,
                    threshold * 100,
                ),
                raw = BigDecimal.valueOf(failureRate),
                threshold = BigDecimal.valueOf(threshold),
                resource = "github-actions/workflows",
                dora = DoraMetric.CHANGE_FAILURE_RATE,
                remediation = RemediationKind.PULL_REQUEST,
            ),
        )
    }

    private fun detectSsdlcHygiene(signals: Map<String, Double>): List<DevOpsFinding> {
        // open `fleet-health` issues = accumulated CI/SSDLC drift; absent signal -> inert.
        val open = signals["open_fleet_health_issues"] ?: return emptyList()
        val threshold = config.ssdlcDriftThreshold().toDouble()
        if (open < threshold) return emptyList()
        return listOf(
            finding(
                detector = DetectorId.D5_SSDLC_HYGIENE,
                severity = FindingSeverity.WARNING,
                title = "%.0f open fleet-health issues (threshold %.0f) — accumulating SSDLC drift".format(
                    open,
                    threshold,
                ),
                raw = BigDecimal.valueOf(open),
                threshold = BigDecimal.valueOf(threshold),
                resource = "github/fleet-health-issues",
                dora = DoraMetric.LEAD_TIME_FOR_CHANGES,
                remediation = RemediationKind.TICKET,
            ),
        )
    }

    @Suppress("MagicNumber")
    private fun detectDoraRegression(signals: Map<String, Double>): List<DevOpsFinding> {
        val cfr = signals["change_failure_rate_proxy"] ?: return emptyList()
        val threshold = config.changeFailureRateThreshold()
        if (cfr < threshold) return emptyList()
        return listOf(
            finding(
                detector = DetectorId.D2_DORA_REGRESSION,
                severity = if (cfr > threshold * 2) FindingSeverity.CRITICAL else FindingSeverity.WARNING,
                title = "Change Failure Rate proxy at %.1f%% (5xx ratio, threshold %.1f%%)".format(
                    cfr * 100,
                    threshold * 100,
                ),
                raw = BigDecimal.valueOf(cfr),
                threshold = BigDecimal.valueOf(threshold),
                resource = "fleet/http-5xx",
                dora = DoraMetric.CHANGE_FAILURE_RATE,
                remediation = RemediationKind.TICKET,
            ),
        )
    }

    @Suppress("MagicNumber")
    private fun detectRunnerCapacity(signals: Map<String, Double>): List<DevOpsFinding> {
        val assigned = signals["arc_assigned_runners"] ?: return emptyList()
        val running = signals["arc_running_runners"] ?: 0.0
        // CRITICAL: jobs assigned but zero online runners — the pool is stranded (the openbank-batch
        // incident). WARNING: queue pressure ratio over threshold (saturated but not dead).
        val ratio = assigned / (running + 1.0)
        val pressureThreshold = config.runnerQueuePressureThreshold()
        val stranded = assigned >= 1.0 && running < 1.0
        if (!stranded && ratio < pressureThreshold) return emptyList()
        return listOf(
            finding(
                detector = DetectorId.D3_RUNNER_CAPACITY,
                severity = if (stranded) FindingSeverity.CRITICAL else FindingSeverity.WARNING,
                title = if (stranded) {
                    "Runner pool stranded: %.0f jobs assigned, 0 online runners".format(assigned)
                } else {
                    "Runner queue pressure %.0f%% (%.0f assigned / %.0f running)".format(ratio * 100, assigned, running)
                },
                raw = BigDecimal.valueOf(if (stranded) assigned else ratio),
                threshold = BigDecimal.valueOf(pressureThreshold),
                resource = "arc-runners",
                dora = DoraMetric.LEAD_TIME_FOR_CHANGES,
                remediation = RemediationKind.PULL_REQUEST,
            ),
        )
    }

    private fun detectDeployHealth(signals: Map<String, Double>): List<DevOpsFinding> {
        val rolloutAlerts = signals["rollout_alerts_firing"] ?: return emptyList()
        if (rolloutAlerts < 1.0) return emptyList()
        return listOf(
            finding(
                detector = DetectorId.D4_DEPLOY_HEALTH,
                severity = FindingSeverity.CRITICAL,
                title = "%.0f Argo Rollouts canary alert(s) firing — deploy health degraded".format(rolloutAlerts),
                raw = BigDecimal.valueOf(rolloutAlerts),
                threshold = BigDecimal.ZERO,
                resource = "argo-rollouts",
                dora = DoraMetric.CHANGE_FAILURE_RATE,
                remediation = RemediationKind.TICKET,
            ),
        )
    }

    private fun detectIncidentRecurrence(signals: Map<String, Double>): List<DevOpsFinding> {
        val recurrence = signals["max_critical_alert_recurrence"] ?: return emptyList()
        val threshold = config.incidentRecurrenceThreshold().toDouble()
        if (recurrence < threshold) return emptyList()
        return listOf(
            finding(
                detector = DetectorId.D6_INCIDENT_RECURRENCE,
                severity = FindingSeverity.WARNING,
                title = "Recurring critical incident: same alert fired %.0f× (threshold %.0f) — needs a permanent guard"
                    .format(recurrence, threshold),
                raw = BigDecimal.valueOf(recurrence),
                threshold = BigDecimal.valueOf(threshold),
                resource = "alertmanager/recurring",
                dora = DoraMetric.TIME_TO_RESTORE,
                remediation = RemediationKind.RUNBOOK_UPDATE,
            ),
        )
    }

    @Suppress("LongParameterList")
    private fun finding(
        detector: DetectorId,
        severity: FindingSeverity,
        title: String,
        raw: BigDecimal,
        threshold: BigDecimal,
        resource: String,
        dora: DoraMetric?,
        remediation: RemediationKind,
    ) = DevOpsFinding(
        id = Ids.newId().toString(),
        detector = detector,
        severity = severity,
        detectedAt = Instant.now(),
        title = title,
        rawMetricValue = raw,
        threshold = threshold,
        affectedResource = resource,
        doraMetricImpacted = dora,
        remediationKind = remediation,
        status = FindingStatus.OPEN,
    )
}
