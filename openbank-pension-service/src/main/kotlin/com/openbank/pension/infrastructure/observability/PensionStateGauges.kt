// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pension.infrastructure.observability

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Singleton
import org.jboss.logging.Logger
import java.time.Duration

/** One counted row of a status snapshot: its labels and how many aggregates carry them. */
data class StateRow(val labels: Map<String, String>, val count: Long)

/** Everything the state gauges publish, read in one pass. */
data class PensionStateSnapshot(
    /** Contracts by status, product line and jurisdiction. */
    val contracts: List<StateRow>,
    /** Every other aggregate by `aggregate` and `status` (and `direction` for transfers). */
    val aggregates: List<StateRow>,
    /** Age in seconds of the oldest item of each operational queue; 0 for an EMPTY queue (true, not a sentinel). */
    val queueOldestAgeSeconds: Map<String, Double>,
    /** Items waiting in each operational queue. */
    val queueSize: Map<String, Long>,
)

/** Where a snapshot comes from; a port so the publisher is testable without a database. */
fun interface PensionStateSource {
    suspend fun snapshot(): PensionStateSnapshot
}

/**
 * Reads the snapshot with plain `count(*) … group by` over the reactive pool (works from a bare
 * scheduler context, no Hibernate session). Status columns are written from closed domain enums (most are CHECK-constrained),
 * so every label value is a closed vocabulary; no id is ever selected.
 */
@Singleton
class PgPensionStateSource(private val pool: Pool) : PensionStateSource {

    override suspend fun snapshot(): PensionStateSnapshot {
        val contracts = rows(
            "SELECT status, product_line, jurisdiction, count(*) AS n FROM pension_contracts GROUP BY 1, 2, 3",
            listOf("status", "product_line", "jurisdiction"),
        )
        val aggregates = AGGREGATE_TABLES.flatMap { (aggregate, table) ->
            rows("SELECT status, count(*) AS n FROM $table GROUP BY 1", listOf("status"))
                .map { it.copy(labels = it.labels + ("aggregate" to aggregate)) }
        } + rows(
            "SELECT direction, status, count(*) AS n FROM pension_transfer_requests GROUP BY 1, 2",
            listOf("direction", "status"),
        ).map { row ->
            val direction = row.labels.getValue("direction").lowercase()
            StateRow(mapOf("aggregate" to "transfer_$direction", "status" to row.labels.getValue("status")), row.count)
        }
        val sizes = mutableMapOf<String, Long>()
        val ages = mutableMapOf<String, Double>()
        QUEUES.forEach { (queue, sql) ->
            val row = pool.query(sql).execute().awaitSuspending().first()
            sizes[queue] = row.getLong("n")
            ages[queue] = row.getDouble("age") ?: 0.0
        }
        return PensionStateSnapshot(contracts, aggregates, ages, sizes)
    }

    private suspend fun rows(sql: String, columns: List<String>): List<StateRow> =
        pool.query(sql).execute().awaitSuspending().map { row ->
            StateRow(columns.associateWith { row.getString(it) ?: UNKNOWN }, row.getLong("n"))
        }

    private companion object {
        const val UNKNOWN = "UNKNOWN"

        /** (aggregate label, table): tables whose `status` column holds a domain enum name. */
        val AGGREGATE_TABLES = listOf(
            "onboarding_application" to "pension_onboarding_applications",
            "unmatched_payment" to "pension_unmatched_payments",
            "incentive_claim" to "pension_incentive_claims",
            "state_contribution_return" to "pension_state_contribution_returns",
            "termination_notice" to "pension_termination_notices",
            "payout_request" to "pension_payout_requests",
            "death_claim" to "pension_death_claims",
            "annuity_purchase" to "pension_annuity_purchases",
            // PENDING_ACTIVATION here IS the four-eyes queue: a maker's proposal awaiting its checker.
            "annuity_provider" to "pension_annuity_providers",
            "payment_instruction" to "pension_payment_instructions",
        )

        private const val AGE = "COALESCE(EXTRACT(EPOCH FROM (now() - min(created_at))), 0)::float8 AS age"

        /** Operational queues an operator works down: size and oldest-item age. */
        val QUEUES = listOf(
            "unmatched_payments" to "SELECT count(*) AS n, $AGE FROM pension_unmatched_payments WHERE status = 'OPEN'",
            "payment_instructions_pending" to
                "SELECT count(*) AS n, $AGE FROM pension_payment_instructions WHERE status = 'PENDING'",
            "incentive_claims_pending" to
                "SELECT count(*) AS n, $AGE FROM pension_incentive_claims WHERE status = 'PENDING'",
            "state_contribution_returns_due" to
                "SELECT count(*) AS n, $AGE FROM pension_state_contribution_returns WHERE status = 'DUE'",
        )
    }
}

/**
 * Publishes a [PensionStateSnapshot] as gauges:
 *
 * - `openbank_pension_contracts{status, product_line, jurisdiction}`
 * - `openbank_pension_aggregates{aggregate, status}`
 * - `openbank_pension_queue_size{queue}` and `openbank_pension_queue_oldest_age_seconds{queue}`
 *
 * COLD-POD SAFE BY ABSENCE (the WorkflowLivenessStale lesson): nothing is registered until the
 * first snapshot has been read, so a fresh pod publishes NO series rather than a zero that reads
 * as "empty queue" or an age that reads as "decades". An alert over these gauges therefore sees
 * either a measured value or nothing — and "nothing" is covered by the liveness of the refresh
 * job itself (`openbank_workflow_last_success_age_seconds{workflow="pension-state-gauges"}`).
 * Label sets that disappear (a status no aggregate holds any more) are removed on the next
 * refresh (`MultiGauge.register(rows, overwrite = true)`), never left frozen at their last value.
 */
class PensionStateGaugePublisher(private val registry: MeterRegistry) {
    private val contracts = MultiGauge.builder("openbank.pension.contracts")
        .description("Pension contracts by status, product line and jurisdiction")
        .register(registry)
    private val aggregates = MultiGauge.builder("openbank.pension.aggregates")
        .description("Pension aggregates by status (snapshot of the service's own tables)")
        .register(registry)
    private val queueSize = MultiGauge.builder("openbank.pension.queue.size")
        .description("Items waiting in a pension operational queue")
        .register(registry)
    private val queueAge = MultiGauge.builder("openbank.pension.queue.oldest_age_seconds")
        .description("Age of the oldest item in a pension operational queue; 0 when the queue is empty")
        .register(registry)

    fun publish(snapshot: PensionStateSnapshot) {
        contracts.register(snapshot.contracts.map { it.toRow() }, true)
        aggregates.register(snapshot.aggregates.map { it.toRow() }, true)
        queueSize.register(
            snapshot.queueSize.map { (queue, n) -> MultiGauge.Row.of(Tags.of("queue", queue), n) },
            true,
        )
        queueAge.register(
            snapshot.queueOldestAgeSeconds.map { (queue, age) -> MultiGauge.Row.of(Tags.of("queue", queue), age) },
            true,
        )
    }

    private fun StateRow.toRow(): MultiGauge.Row<Number> =
        MultiGauge.Row.of(Tags.of(labels.map { (k, v) -> io.micrometer.core.instrument.Tag.of(k, v) }), count)
}

/**
 * Refreshes the state gauges every minute. `suspend fun` (rules.yaml: scheduled_methods); liveness
 * registered on [StartupEvent] and recorded only after a snapshot was published, so a refresh that
 * keeps failing goes stale at 2x its interval (WorkflowLivenessStale) instead of serving old values.
 */
@ApplicationScoped
class PensionStateGaugeRefresher(
    private val source: PensionStateSource,
    private val domainMetrics: DomainMetrics,
    registry: MeterRegistry,
) {
    private val log = Logger.getLogger(PensionStateGaugeRefresher::class.java)
    private val publisher = PensionStateGaugePublisher(registry)
    private var liveness: WorkflowLivenessRecorder? = null

    fun register(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW, INTERVAL)
    }

    @Scheduled(
        identity = "pension-state-gauges",
        every = "\${openbank.pension.state-gauges.every:60s}",
        delayed = "\${openbank.pension.state-gauges.delay:15s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun refresh() {
        runCatching { publisher.publish(source.snapshot()) }
            .onSuccess { liveness?.recordSuccess() }
            .onFailure { log.warnf(it, "pension state gauges not refreshed; the previous values stay published") }
    }

    companion object {
        const val WORKFLOW = "pension-state-gauges"
        val INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
