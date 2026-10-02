// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.application.workflow

import com.openbank.libs.observability.WorkflowLivenessMetrics
import com.openbank.liveness.application.port.out.PrometheusQueryPort
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pure-JVM coverage of the signal collector. The activity bridges to a Vert.x context in
 * production; here [runOnVertxContext] is overridden with a plain `runBlocking`, which is the only
 * seam needed -- everything the activity actually decides (which series it queries, and how the
 * three watchdog gauges are joined) is above that bridge.
 */
class CollectSignalsActivityImplTest {

    /** Records every PromQL string the activity asks for, and answers from a fixed table. */
    private class RecordingPrometheus(private val vectors: Map<String, Map<String, Double>>) : PrometheusQueryPort {
        val queries = mutableListOf<String>()
        override suspend fun queryInstant(promql: String): Double? = null
        override suspend fun queryVector(promql: String): Map<String, Double> {
            queries += promql
            return vectors[promql] ?: emptyMap()
        }

        override suspend fun queryRange(
            promql: String,
            start: Instant,
            end: Instant,
            step: String,
        ): List<Pair<Instant, Double>> = emptyList()
    }

    private class TestableCollect(prometheus: PrometheusQueryPort) : CollectSignalsActivityImpl(prometheus) {
        override fun <T> runOnVertxContext(block: suspend () -> T): T = runBlocking { block() }
    }

    private fun collector(vectors: Map<String, Map<String, Double>>): Pair<TestableCollect, RecordingPrometheus> {
        val prom = RecordingPrometheus(vectors)
        return TestableCollect(prom) to prom
    }

    private fun watchdogVectors(
        ages: Map<String, Double>,
        intervals: Map<String, Double>,
        succeeded: Map<String, Double> = emptyMap(),
    ) = mapOf(
        WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SERIES to ages,
        WorkflowLivenessMetrics.EXPECTED_INTERVAL_SERIES to intervals,
        WorkflowLivenessMetrics.SUCCESS_RECORDED_SERIES to succeeded,
    )

    @Test
    fun `watchdog heartbeats join age, expected interval and the success flag into one composite key`() {
        val (activity, _) = collector(
            watchdogVectors(
                ages = mapOf("balance-reconciliation" to 240.0),
                intervals = mapOf("balance-reconciliation" to 100.0),
                succeeded = mapOf("balance-reconciliation" to 1.0),
            ),
        )

        val out = activity.collectWatchdogHeartbeats()

        assertThat(out).hasSize(1)
        val (key, age) = out.entries.single()
        assertThat(age).isEqualTo(240.0)
        // The detector splits the two trailing fields off the RIGHT, so job|interval|flag order
        // is load-bearing, not cosmetic.
        assertThat(key.split('|').dropLast(2).joinToString("|")).isEqualTo("balance-reconciliation")
        assertThat(key.split('|')[key.split('|').size - 2].toDouble()).isEqualTo(100.0)
        assertThat(key.split('|').last().toDouble()).isEqualTo(1.0)
    }

    @Test
    fun `a job with an age gauge but no expected-interval gauge is dropped, not guessed at`() {
        val (activity, _) = collector(
            watchdogVectors(
                ages = mapOf("has-interval" to 10.0, "no-interval" to 9999.0),
                intervals = mapOf("has-interval" to 100.0),
            ),
        )

        val out = activity.collectWatchdogHeartbeats()

        assertThat(out.keys.map { it.substringBefore('|') }).containsExactly("has-interval")
    }

    @Test
    fun `a missing success flag is read as HAS succeeded, so a rolling upgrade invents no findings`() {
        // An older pod emits two gauges, not three. Defaulting the other way would manufacture a
        // "never succeeded" claim about every job in the fleet for the length of a rollout.
        val (activity, _) = collector(
            watchdogVectors(
                ages = mapOf("legacy-job" to 10.0),
                intervals = mapOf("legacy-job" to 100.0),
                succeeded = emptyMap(),
            ),
        )

        assertThat(activity.collectWatchdogHeartbeats().keys.single()).isEqualTo("legacy-job|100.0|1.0")
    }

    @Test
    fun `a never-succeeded flag of zero survives into the composite key`() {
        val (activity, _) = collector(
            watchdogVectors(
                ages = mapOf("fresh-job" to 10.0),
                intervals = mapOf("fresh-job" to 100.0),
                succeeded = mapOf("fresh-job" to 0.0),
            ),
        )

        assertThat(activity.collectWatchdogHeartbeats().keys.single()).endsWith("|0.0")
    }

    @Test
    fun `an empty age vector yields no heartbeats even when intervals are present`() {
        val (activity, _) = collector(
            watchdogVectors(ages = emptyMap(), intervals = mapOf("balance-reconciliation" to 100.0)),
        )

        assertThat(activity.collectWatchdogHeartbeats()).isEmpty()
    }

    @Test
    fun `the watchdog series names are the ones the producer emits, not a local literal`() {
        // The literals here were once wrong (openbank_workflow_liveness_* vs openbank_workflow_*),
        // so every query returned an empty vector and mechanism 3 reported "healthy"
        // unconditionally. Pinning to the producer's own constants is the fix.
        val (activity, prom) = collector(emptyMap())

        activity.collectWatchdogHeartbeats()

        assertThat(prom.queries).containsExactly(
            WorkflowLivenessMetrics.LAST_SUCCESS_AGE_SERIES,
            WorkflowLivenessMetrics.EXPECTED_INTERVAL_SERIES,
            WorkflowLivenessMetrics.SUCCESS_RECORDED_SERIES,
        )
    }

    @Test
    fun `the event-consumer report queries the producer-only gauge and passes the vector through`() {
        val series = "openbank_event_consumer_liveness_producer_only"
        val (activity, prom) = collector(mapOf(series to mapOf("openbank.sca.events" to 1.0)))

        val out = activity.collectEventConsumerReport()

        assertThat(prom.queries).containsExactly(series)
        assertThat(out).containsExactly(java.util.Map.entry("openbank.sca.events", 1.0))
    }

    @Test
    fun `the lineage audit report queries the unverified-edge gauge`() {
        val series = "openbank_lineage_audit_unverified_edge"
        val (activity, prom) = collector(mapOf(series to mapOf("ledger->finrep" to 1.0)))

        val out = activity.collectLineageAuditReport()

        assertThat(prom.queries).containsExactly(series)
        assertThat(out).containsEntry("ledger->finrep", 1.0)
    }

    @Test
    fun `the drift report queries the consecutive-drift-runs gauge`() {
        val series = "openbank_reconciliation_consecutive_drift_runs"
        val (activity, prom) = collector(mapOf(series to mapOf("balance-reconciliation" to 4.0)))

        val out = activity.collectReconciliationDriftWindows()

        assertThat(prom.queries).containsExactly(series)
        assertThat(out).containsEntry("balance-reconciliation", 4.0)
    }
}
