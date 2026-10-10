// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.pensionfund.infrastructure.observability

import com.openbank.libs.observability.DomainMetrics
import com.openbank.libs.observability.WorkflowLivenessRecorder
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tag
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

/** One counted row of a status snapshot: its labels and how many items carry them. */
data class StateRow(val labels: Map<String, String>, val count: Long)

/**
 * One ACTIVE fund, read in the same pass as everything else. A figure that has not been
 * established yet is NULL, never a seed: a fund without a published NAV has no net assets, no NAV
 * per unit and no "last published" age.
 */
data class FundState(
    val isin: String,
    val currency: String,
    /** Units the register holds today across all contracts. */
    val unitsOutstanding: Double,
    /** Net assets of the latest PUBLISHED NAV (AUM as last established), in [currency]. */
    val netAssets: Double?,
    val navPerUnit: Double?,
    /** Seconds since the latest published valuation day ENDED (00:00 UTC of the next day); 0 before it ends. */
    val lastPublishedAgeSeconds: Double?,
    /** Age of the oldest PENDING order of this fund; 0 when none is pending (a measurement). */
    val pendingOrdersOldestAgeSeconds: Double,
)

/** Everything the state gauges publish, read in one pass. */
data class PensionFundStateSnapshot(
    val funds: List<FundState>,
    /** PENDING orders by `fund` (ISIN) and `type`. */
    val pendingOrders: List<StateRow>,
    /** Every aggregate by `aggregate` and `status`. */
    val aggregates: List<StateRow>,
    /** Age in seconds of the oldest item of each queue; 0 for an EMPTY queue (true, not a sentinel). */
    val queueOldestAgeSeconds: Map<String, Double>,
    val queueSize: Map<String, Long>,
)

/** Where a snapshot comes from; a port so the publisher is testable without a database. */
fun interface PensionFundStateSource {
    suspend fun snapshot(): PensionFundStateSnapshot
}

/**
 * Plain SQL over the reactive pool (works from a bare scheduler context, no Hibernate session).
 * Status and type columns are written from closed domain enums and the only identifier selected
 * is the fund's ISIN — never a contract, order or NAV id.
 */
@Singleton
class PgPensionFundStateSource(private val pool: Pool) : PensionFundStateSource {

    override suspend fun snapshot(): PensionFundStateSnapshot {
        val funds = pool.query(FUNDS).execute().awaitSuspending().map { row ->
            FundState(
                isin = row.getString("isin").trim(),
                currency = row.getString("currency"),
                unitsOutstanding = row.getDouble("units"),
                netAssets = row.getDouble("net_assets"),
                navPerUnit = row.getDouble("nav_per_unit"),
                lastPublishedAgeSeconds = row.getDouble("nav_age"),
                pendingOrdersOldestAgeSeconds = row.getDouble("pending_age") ?: 0.0,
            )
        }
        val pending = rows(PENDING_BY_TYPE, listOf("fund", "type"))
        val aggregates = AGGREGATE_TABLES.flatMap { (aggregate, table) ->
            rows("SELECT status, count(*) AS n FROM $table GROUP BY 1", listOf("status"))
                .map { it.copy(labels = it.labels + ("aggregate" to aggregate)) }
        }
        val sizes = mutableMapOf<String, Long>()
        val ages = mutableMapOf<String, Double>()
        QUEUES.forEach { (queue, sql) ->
            val row = pool.query(sql).execute().awaitSuspending().first()
            sizes[queue] = row.getLong("n")
            ages[queue] = row.getDouble("age") ?: 0.0
        }
        return PensionFundStateSnapshot(funds, pending, aggregates, ages, sizes)
    }

    private suspend fun rows(sql: String, columns: List<String>): List<StateRow> =
        pool.query(sql).execute().awaitSuspending().map { row ->
            StateRow(columns.associateWith { (row.getString(it) ?: UNKNOWN).trim() }, row.getLong("n"))
        }

    private companion object {
        const val UNKNOWN = "UNKNOWN"

        val FUNDS = """
            SELECT f.isin, f.currency,
                   (SELECT COALESCE(sum(h.units), 0) FROM unit_holdings h WHERE h.fund_id = f.id)::float8 AS units,
                   n.net_assets::float8 AS net_assets,
                   n.nav_per_unit::float8 AS nav_per_unit,
                   -- 0 while the published valuation day has not ended yet (a same-day NAV), never negative;
                   -- NULL with no published NAV. GREATEST ignores NULLs (GREATEST(NULL, 0) = 0), so the
                   -- CASE is what keeps a never-published fund absent instead of reading "just published".
                   CASE WHEN n.valuation_date IS NULL THEN NULL ELSE GREATEST(
                       EXTRACT(EPOCH FROM (now() - ((n.valuation_date + 1)::timestamp AT TIME ZONE 'UTC'))), 0
                   ) END::float8 AS nav_age,
                   (SELECT EXTRACT(EPOCH FROM (now() - min(o.placed_at)))
                      FROM unit_orders o WHERE o.fund_id = f.id AND o.status = 'PENDING')::float8 AS pending_age
              FROM funds f
              LEFT JOIN LATERAL (
                   SELECT valuation_date, net_assets, nav_per_unit FROM fund_navs
                    WHERE fund_id = f.id AND status = 'PUBLISHED'
                    ORDER BY valuation_date DESC LIMIT 1
              ) n ON true
             WHERE f.status = 'ACTIVE'
        """.trimIndent()

        val PENDING_BY_TYPE = """
            SELECT f.isin AS fund, o.order_type AS type, count(*) AS n
              FROM unit_orders o JOIN funds f ON f.id = o.fund_id
             WHERE o.status = 'PENDING' GROUP BY 1, 2
        """.trimIndent()

        /** (aggregate label, table): tables whose `status` column holds a domain enum name. */
        val AGGREGATE_TABLES = listOf(
            "fund" to "funds",
            "nav" to "fund_navs",
            "unit_order" to "unit_orders",
            "strategy" to "fund_strategies",
            "strategy_change" to "strategy_changes",
        )

        private fun age(column: String) = "COALESCE(EXTRACT(EPOCH FROM (now() - min($column))), 0)::float8 AS age"

        /** Queues someone works down: size and oldest-item age. The two approval queues are four-eyes. */
        val QUEUES = listOf(
            "orders_pending" to "SELECT count(*) AS n, ${age("placed_at")} FROM unit_orders WHERE status = 'PENDING'",
            "navs_awaiting_approval" to
                "SELECT count(*) AS n, ${age("calculated_at")} FROM fund_navs WHERE status = 'CALCULATED'",
            "strategy_changes_awaiting_approval" to
                "SELECT count(*) AS n, ${age("submitted_at")} FROM strategy_changes WHERE status = 'PENDING_APPROVAL'",
        )
    }
}

/**
 * Publishes a [PensionFundStateSnapshot] as gauges:
 *
 * - `openbank_pension_fund_units_outstanding{fund}`, `openbank_pension_fund_net_assets{fund, currency}`,
 *   `openbank_pension_fund_nav_per_unit{fund, currency}`
 * - `openbank_pension_fund_nav_last_published_age_seconds{fund}`
 * - `openbank_pension_fund_orders_pending{fund, type}`, `openbank_pension_fund_orders_pending_oldest_age_seconds{fund}`
 * - `openbank_pension_fund_aggregates{aggregate, status}`
 * - `openbank_pension_fund_queue_size{queue}`, `openbank_pension_fund_queue_oldest_age_seconds{queue}`
 *
 * COLD-POD SAFE BY ABSENCE (the WorkflowLivenessStale lesson): nothing is registered until the
 * first snapshot was read, so a fresh pod publishes NO series rather than a 0 that reads as "no
 * order pending" or an age that reads as "decades". A fund with no published NAV yet has no
 * net-assets / NAV / last-published series either. "The refresh never ran" is the liveness of the
 * job itself (`openbank_workflow_last_success_age_seconds{workflow="pension-fund-state-gauges"}`).
 * Label sets that disappear (a closed fund) are removed on the next refresh, never frozen.
 */
class PensionFundStateGaugePublisher(private val registry: MeterRegistry) {
    private fun gauge(name: String, description: String) =
        MultiGauge.builder(name).description(description).register(registry)

    private val units = gauge("openbank.pension_fund.units_outstanding", "Units the register holds per fund")
    private val netAssets =
        gauge("openbank.pension_fund.net_assets", "Net assets (AUM) at the fund's latest PUBLISHED NAV")
    private val navPerUnit = gauge("openbank.pension_fund.nav.per_unit", "NAV per unit of the latest PUBLISHED NAV")
    private val navAge = gauge(
        "openbank.pension_fund.nav.last_published_age_seconds",
        "Seconds since the latest published valuation day ended (00:00 UTC next day)",
    )
    private val pendingOrders = gauge("openbank.pension_fund.orders.pending", "PENDING unit orders by fund and type")
    private val pendingAge = gauge(
        "openbank.pension_fund.orders.pending_oldest_age_seconds",
        "Age of the oldest PENDING order per fund; 0 when none is pending",
    )
    private val aggregates = gauge("openbank.pension_fund.aggregates", "Pension-fund aggregates by status")
    private val queueSize = gauge("openbank.pension_fund.queue.size", "Items waiting in a pension-fund queue")
    private val queueAge = gauge(
        "openbank.pension_fund.queue.oldest_age_seconds",
        "Age of the oldest item in a pension-fund queue; 0 when the queue is empty",
    )

    fun publish(snapshot: PensionFundStateSnapshot) {
        val funds = snapshot.funds
        units.register(funds.map { row(Tags.of("fund", it.isin), it.unitsOutstanding) }, true)
        netAssets.register(
            funds.mapNotNull { f -> f.netAssets?.let { row(Tags.of("fund", f.isin, "currency", f.currency), it) } },
            true,
        )
        navPerUnit.register(
            funds.mapNotNull { f -> f.navPerUnit?.let { row(Tags.of("fund", f.isin, "currency", f.currency), it) } },
            true,
        )
        navAge.register(
            funds.mapNotNull { f -> f.lastPublishedAgeSeconds?.let { row(Tags.of("fund", f.isin), it) } },
            true,
        )
        pendingAge.register(funds.map { row(Tags.of("fund", it.isin), it.pendingOrdersOldestAgeSeconds) }, true)
        pendingOrders.register(snapshot.pendingOrders.map { it.toRow() }, true)
        aggregates.register(snapshot.aggregates.map { it.toRow() }, true)
        queueSize.register(snapshot.queueSize.map { (q, n) -> row(Tags.of("queue", q), n) }, true)
        queueAge.register(snapshot.queueOldestAgeSeconds.map { (q, age) -> row(Tags.of("queue", q), age) }, true)
    }

    private fun row(tags: Tags, value: Number): MultiGauge.Row<Number> = MultiGauge.Row.of(tags, value)

    private fun StateRow.toRow(): MultiGauge.Row<Number> =
        MultiGauge.Row.of(Tags.of(labels.map { (k, v) -> Tag.of(k, v) }), count)
}

/**
 * Refreshes the state gauges every minute. `suspend fun` (rules.yaml: scheduled_methods); liveness
 * registered on [StartupEvent] and recorded only after a snapshot was published, so a refresh that
 * keeps failing goes stale at 2x its interval (WorkflowLivenessStale) instead of serving old values.
 */
@ApplicationScoped
class PensionFundStateGaugeRefresher(
    private val source: PensionFundStateSource,
    private val domainMetrics: DomainMetrics,
    registry: MeterRegistry,
) {
    private val log = Logger.getLogger(PensionFundStateGaugeRefresher::class.java)
    private val publisher = PensionFundStateGaugePublisher(registry)
    private var liveness: WorkflowLivenessRecorder? = null

    fun register(@Observes @Suppress("UNUSED_PARAMETER") event: StartupEvent) {
        liveness = domainMetrics.registerWorkflowLiveness(WORKFLOW, INTERVAL)
    }

    @Scheduled(
        identity = "pension-fund-state-gauges",
        every = "\${openbank.pension-fund.state-gauges.every:60s}",
        delayed = "\${openbank.pension-fund.state-gauges.delay:15s}",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    suspend fun refresh() {
        runCatching { publisher.publish(source.snapshot()) }
            .onSuccess { liveness?.recordSuccess() }
            .onFailure { log.warnf(it, "pension-fund state gauges not refreshed; the previous values stay published") }
    }

    companion object {
        const val WORKFLOW = "pension-fund-state-gauges"
        val INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
