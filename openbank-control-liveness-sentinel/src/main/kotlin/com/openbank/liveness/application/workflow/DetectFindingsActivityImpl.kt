// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.application.workflow

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.liveness.application.port.out.GovernanceReadPort
import com.openbank.liveness.domain.model.ControlMechanism
import com.openbank.liveness.domain.model.FindingSeverity
import com.openbank.liveness.domain.model.FindingStatus
import com.openbank.liveness.domain.model.LivenessFinding
import com.openbank.liveness.infrastructure.config.LivenessSentinelConfig
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.math.BigDecimal
import java.time.Instant

@ApplicationScoped
open class DetectFindingsActivityImpl(
    private val config: LivenessSentinelConfig,
    private val governanceRead: GovernanceReadPort,
) : DetectFindingsActivity {

    override fun detect(mechanism: ControlMechanism, signals: Map<String, Double>): List<LivenessFinding> =
        when (mechanism) {
            ControlMechanism.M3_WORKFLOW_WATCHDOG -> detectStaleHeartbeats(signals)
            ControlMechanism.M1_EVENT_CONSUMER_LIVENESS -> detectProducerOnlyTopics(signals)
            ControlMechanism.M2_LINEAGE_VS_CODE -> detectUnverifiedLineage(signals)
            ControlMechanism.M4_RECONCILIATION_DRIFT_SLA -> detectSustainedDrift(signals)
        }

    // CollectSignalsActivityImpl joins the age gauge with its companion expected-interval and
    // has-ever-succeeded gauges and produces one composite key
    // "<job>|<expectedIntervalSeconds>|<everSucceeded>" per job -> ageSeconds, so this detector
    // never has to guess an interval or do a second Prometheus round-trip itself. The two trailing
    // fields are taken from the RIGHT so a job name is free to contain the separator.
    private fun detectStaleHeartbeats(signals: Map<String, Double>): List<LivenessFinding> =
        signals.mapNotNull { (label, ageSeconds) ->
            val fields = label.split('|')
            if (fields.size < COMPOSITE_KEY_FIELDS) return@mapNotNull null
            val job = fields.dropLast(2).joinToString("|")
            val expectedIntervalSeconds = fields[fields.size - 2].toDoubleOrNull() ?: return@mapNotNull null
            // Absent/1.0 means the job has produced at least one success in this pod's lifetime.
            val neverSucceeded = (fields.last().toDoubleOrNull() ?: 1.0) < 1.0
            // ADR-0163: criticalThreshold matches ADR-0160 mechanism 3's own Alertmanager paging
            // line (2x expected interval by default) so this agent's CRITICAL finding and the
            // underlying page can never silently disagree; warnThreshold fires earlier so a
            // heartbeat that is merely getting stale is visible before it becomes a page.
            val criticalThreshold = expectedIntervalSeconds * config.staleHeartbeatMultiplier()
            val warnThreshold = expectedIntervalSeconds * config.warnHeartbeatMultiplier()
            if (ageSeconds < warnThreshold) return@mapNotNull null
            LivenessFinding(
                id = Ids.newId().toString(),
                mechanism = ControlMechanism.M3_WORKFLOW_WATCHDOG,
                severity = if (ageSeconds >= criticalThreshold) FindingSeverity.CRITICAL else FindingSeverity.WARNING,
                detectedAt = Instant.now(),
                title = if (neverSucceeded) {
                    "Stale heartbeat for '$job': no success in the ${"%.0f".format(ageSeconds)}s since " +
                        "this pod registered it (expected every ${"%.0f".format(expectedIntervalSeconds)}s)"
                } else {
                    "Stale heartbeat for '$job': last success ${"%.0f".format(ageSeconds)}s ago " +
                        "(expected every ${"%.0f".format(expectedIntervalSeconds)}s)"
                },
                affectedControl = job,
                rawMetricValue = BigDecimal.valueOf(ageSeconds),
                threshold = BigDecimal.valueOf(warnThreshold),
                status = FindingStatus.OPEN,
            )
        }

    private fun detectProducerOnlyTopics(signals: Map<String, Double>): List<LivenessFinding> {
        val allowlist = runOnVertxContext { governanceRead.eventConsumerAllowlist() }
        return signals.filterValues { it >= 1.0 }
            .filterKeys { it !in allowlist }
            .map { (topic, _) ->
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
    }

    private fun detectUnverifiedLineage(signals: Map<String, Double>): List<LivenessFinding> {
        val allowlist = runOnVertxContext { governanceRead.lineageAllowlist() }
        return signals.filterValues { it >= 1.0 }
            .filterKeys { it !in allowlist }
            .map { (edge, _) ->
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
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
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

    private companion object {
        /** `<job>|<expectedIntervalSeconds>|<everSucceeded>` — see [detectStaleHeartbeats]. */
        const val COMPOSITE_KEY_FIELDS = 3
    }
}
