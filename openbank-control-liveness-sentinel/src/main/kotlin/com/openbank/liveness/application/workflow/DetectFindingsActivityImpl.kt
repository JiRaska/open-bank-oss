// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.application.workflow

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.liveness.domain.model.ControlMechanism
import com.openbank.liveness.domain.model.FindingSeverity
import com.openbank.liveness.domain.model.FindingStatus
import com.openbank.liveness.domain.model.LivenessFinding
import com.openbank.liveness.infrastructure.config.LivenessSentinelConfig
import jakarta.enterprise.context.ApplicationScoped
import java.math.BigDecimal
import java.time.Instant

@ApplicationScoped
class DetectFindingsActivityImpl(private val config: LivenessSentinelConfig) : DetectFindingsActivity {

    override fun detect(mechanism: ControlMechanism, signals: Map<String, Double>): List<LivenessFinding> =
        when (mechanism) {
            ControlMechanism.M3_WORKFLOW_WATCHDOG -> detectStaleHeartbeats(signals)
            ControlMechanism.M1_EVENT_CONSUMER_LIVENESS -> detectProducerOnlyTopics(signals)
            ControlMechanism.M2_LINEAGE_VS_CODE -> detectUnverifiedLineage(signals)
            ControlMechanism.M4_RECONCILIATION_DRIFT_SLA -> detectSustainedDrift(signals)
        }

    // Each gauge label is "<job>|<expectedIntervalSeconds>" -> ageSeconds. The label carries the
    // job's own declared interval so the multiplier check needs no second lookup.
    private fun detectStaleHeartbeats(signals: Map<String, Double>): List<LivenessFinding> =
        signals.mapNotNull { (label, ageSeconds) ->
            val (job, intervalRaw) = label.split("|", limit = 2).let { it.getOrElse(0) { label } to it.getOrNull(1) }
            val expectedIntervalSeconds = intervalRaw?.toDoubleOrNull() ?: return@mapNotNull null
            val warnThreshold = expectedIntervalSeconds * config.staleHeartbeatMultiplier()
            if (ageSeconds < warnThreshold) return@mapNotNull null
            val criticalThreshold = expectedIntervalSeconds * config.staleHeartbeatMultiplier() * 2
            LivenessFinding(
                id = Ids.newId().toString(),
                mechanism = ControlMechanism.M3_WORKFLOW_WATCHDOG,
                severity = if (ageSeconds >= criticalThreshold) FindingSeverity.CRITICAL else FindingSeverity.WARNING,
                detectedAt = Instant.now(),
                title = "Stale heartbeat for '$job': last success ${"%.0f".format(ageSeconds)}s ago " +
                    "(expected every ${"%.0f".format(expectedIntervalSeconds)}s)",
                affectedControl = job,
                rawMetricValue = BigDecimal.valueOf(ageSeconds),
                threshold = BigDecimal.valueOf(warnThreshold),
                status = FindingStatus.OPEN,
            )
        }

    private fun detectProducerOnlyTopics(signals: Map<String, Double>): List<LivenessFinding> =
        signals.filterValues { it >= 1.0 }.map { (topic, _) ->
            LivenessFinding(
                id = Ids.newId().toString(),
                mechanism = ControlMechanism.M1_EVENT_CONSUMER_LIVENESS,
                severity = FindingSeverity.WARNING,
                detectedAt = Instant.now(),
                title = "Topic '$topic' has a producer and zero consumers, and is not in the rules.yaml allowlist",
                affectedControl = topic,
                rawMetricValue = BigDecimal.ONE,
                threshold = BigDecimal.ZERO,
                status = FindingStatus.OPEN,
            )
        }

    private fun detectUnverifiedLineage(signals: Map<String, Double>): List<LivenessFinding> =
        signals.filterValues { it >= 1.0 }.map { (edge, _) ->
            LivenessFinding(
                id = Ids.newId().toString(),
                mechanism = ControlMechanism.M2_LINEAGE_VS_CODE,
                severity = FindingSeverity.WARNING,
                detectedAt = Instant.now(),
                title = "governance.yaml lineage edge '$edge' has no matching code (unverified)",
                affectedControl = edge,
                rawMetricValue = BigDecimal.ONE,
                threshold = BigDecimal.ZERO,
                status = FindingStatus.OPEN,
            )
        }

    private fun detectSustainedDrift(signals: Map<String, Double>): List<LivenessFinding> =
        signals.mapNotNull { (control, consecutiveRuns) ->
            val threshold = config.consecutiveDriftThreshold().toDouble()
            if (consecutiveRuns < threshold) return@mapNotNull null
            LivenessFinding(
                id = Ids.newId().toString(),
                mechanism = ControlMechanism.M4_RECONCILIATION_DRIFT_SLA,
                severity = FindingSeverity.CRITICAL,
                detectedAt = Instant.now(),
                title = "Control '$control' has drifted for ${consecutiveRuns.toInt()} consecutive runs " +
                    "(threshold ${threshold.toInt()})",
                affectedControl = control,
                rawMetricValue = BigDecimal.valueOf(consecutiveRuns),
                threshold = BigDecimal.valueOf(threshold),
                status = FindingStatus.OPEN,
            )
        }
}
