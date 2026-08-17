// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
package com.openbank.agent.infrastructure.observability

import com.openbank.agent.application.ProposalService
import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.Startup
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Publishes the agent **pending-proposal** backlog as a Micrometer gauge (C8 prod-readiness
 * sweep / ADR-0077). PROPOSED rows awaiting human approval are an operational SLO signal: a
 * growing backlog means the four-eyes gate is stalling and operators should be alerted.
 *
 *  - `openbank_agent_proposals_pending{service="agent"}` — PROPOSED proposals not yet decided.
 *
 * The gauge is populated by a scheduled JDBC tick (ProposalService uses plain Agroal JDBC,
 * not reactive Panache). Micrometer reads the cached [AtomicLong] lock-free on the scrape thread.
 *
 * Service-local [MeterRegistry] (null-safe via [Instance], exactly like libs DomainMetrics):
 * this metric is agent-specific; adding it to the shared DomainMetrics facade would force a
 * fleet-wide rebuild for a one-service concern (service-local metrics pattern, ADR-0085 §2).
 */
@Startup
@ApplicationScoped
class AgentMetricsAdapter(private val proposalService: ProposalService, private val registry: MeterRegistry?) {
    // CDI constructor: MeterRegistry is optional (absent when Prometheus extension is not on
    // the classpath, e.g. in slim test slices). Without an explicit @Inject ctor, ArC sees two
    // constructors, registers no bean, and the @Startup hook silently never fires.
    @Inject
    constructor(proposalService: ProposalService, registryInstance: Instance<MeterRegistry>) : this(
        proposalService,
        if (registryInstance.isResolvable) registryInstance.get() else null,
    )

    private val pending = AtomicLong(0)

    @Inject
    lateinit var domainMetrics: DomainMetrics
    private var liveness: WorkflowLivenessRecorder? = null

    @PostConstruct
    fun register() {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW_NAME, EXPECTED_INTERVAL)
        val r = registry ?: return
        Gauge.builder("openbank.agent.proposals.pending", pending) { it.get().toDouble() }
            .tag("service", SERVICE)
            .description("Agent proposals in PROPOSED state awaiting human approval")
            .strongReference(true)
            .register(r)
    }

    @Scheduled(
        every = "30s",
        delayed = "15s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun refresh() {
        pending.set(proposalService.listPending().size.toLong())
        liveness?.recordSuccess()
    }

    companion object {
        private const val SERVICE = "agent"
        private const val WORKFLOW_NAME = "agent-proposal-backlog-refresh"
        private val EXPECTED_INTERVAL: Duration = Duration.ofSeconds(30)
    }
}
