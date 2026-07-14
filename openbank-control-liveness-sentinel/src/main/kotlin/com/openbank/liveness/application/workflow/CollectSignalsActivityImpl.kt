// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.

package com.openbank.liveness.application.workflow

import com.openbank.liveness.application.port.out.PrometheusQueryPort
import io.quarkus.vertx.VertxContextSupport
import io.smallrye.mutiny.coroutines.asUni
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jboss.logging.Logger

@ApplicationScoped
open class CollectSignalsActivityImpl(private val prometheusQuery: PrometheusQueryPort) : CollectSignalsActivity {

    private val log = Logger.getLogger(CollectSignalsActivityImpl::class.java)

    // ADR-0160 mechanism 3: one WorkflowLivenessWatchdog gauge per opted-in @Scheduled job,
    // labelled by job name, plus a companion gauge publishing that job's own declared expected
    // interval (also labelled by job name). Joined here into a single "<job>|<intervalSeconds>"
    // composite key -> ageSeconds map, so DetectFindingsActivityImpl never has to guess an
    // interval or make a second Prometheus round-trip of its own. A job with an age gauge but no
    // matching interval gauge is dropped rather than guessed at.
    override fun collectWatchdogHeartbeats(): Map<String, Double> = runOnVertxContext {
        log.debug("Collecting WorkflowLivenessWatchdog heartbeat gauges")
        val ages = prometheusQuery.queryVector("openbank_workflow_liveness_last_success_age_seconds")
        val intervals = prometheusQuery.queryVector("openbank_workflow_liveness_expected_interval_seconds")
        ages.mapNotNull { (job, age) ->
            val interval = intervals[job] ?: return@mapNotNull null
            "$job|$interval" to age
        }.toMap()
    }

    // ADR-0160 mechanism 1: check-event-consumer-liveness.sh publishes a gauge per producer-only
    // topic found in the fleet's mp.messaging declarations (1 = producer with zero consumers).
    override fun collectEventConsumerReport(): Map<String, Double> = runOnVertxContext {
        log.debug("Collecting event-consumer-liveness report")
        prometheusQuery.queryVector("openbank_event_consumer_liveness_producer_only")
    }

    // ADR-0160 mechanism 2: the lineage-vs-code audit publishes a gauge per governance.yaml
    // lineage edge it could not verify against code (1 = unverified edge).
    override fun collectLineageAuditReport(): Map<String, Double> = runOnVertxContext {
        log.debug("Collecting lineage-vs-code audit report")
        prometheusQuery.queryVector("openbank_lineage_audit_unverified_edge")
    }

    // ADR-0160 mechanism 4: consecutive drift-window counter per reconciliation control.
    override fun collectReconciliationDriftWindows(): Map<String, Double> = runOnVertxContext {
        log.debug("Collecting reconciliation drift-SLA windows")
        prometheusQuery.queryVector("openbank_reconciliation_consecutive_drift_runs")
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    protected open fun <T> runOnVertxContext(block: suspend () -> T): T = VertxContextSupport.subscribeAndAwait {
        CoroutineScope(Dispatchers.Unconfined).async { block() }.asUni()
    }
}
