// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.feature

import java.time.Duration
import java.time.Instant

/** Event type emitted by transaction-service when a payment has settled. */
const val TRANSACTION_COMPLETED: String = "TransactionCompleted"

/**
 * Event type emitted for an account balance change. Screaming case because that is what the
 * producer puts on the wire — measured on the warehouse, all 205 of these events carry
 * `bookedAmount` and `availableAmount`, and `aggregate_version` = 0 on every one of them.
 */
const val BALANCE_UPDATED: String = "BALANCE_UPDATED"

/**
 * The settled money-flow features ADR-0282 phase 1 asks for (issue #8792), declared once and
 * computed by one pure function so the online and offline materialisations cannot skew
 * (ADR-0140, ADR-0201 D3).
 *
 * POST-SETTLEMENT, AND THAT COSTS A PAIRING. #8792 requires the fact to be post-settlement rather
 * than at instruction time, and no single event carries both halves: `TransactionCompleted` is the
 * settlement and carries no amount, `TransactionInitiated` carries the amount and precedes the
 * money moving. So every feature here pairs the two by [FeatureEvent.correlationId] and takes the
 * TIME from the settlement and the AMOUNT from the instruction. An instruction that never settled
 * contributes nothing, which is the whole point — counting it would report money that never moved.
 *
 * WHAT IS DELIBERATELY NOT HERE. Category, MCC and merchant are absent from every transaction
 * payload today, so no spend-category feature can be declared honestly, and none is. Channel
 * recency is the same: measured 2026-09-11, NOT ONE event in the warehouse carries a payload key
 * containing "channel", so the feature #8792 asks for has no source to read.
 *
 * Buffer months used to be listed here as deferred for the same reason. It is not: it needs a
 * balance LEVEL rather than a flow, and [BALANCE_UPDATED] supplies one — so it is declared below as
 * its own definition rather than bolted onto a flow feature.
 */
private const val D90: Long = 90

/**
 * Days per month for turning a windowed flow into a monthly rate. A nominal 30, not 30.44: the
 * window is itself a round 90 days, so a calendar-accurate divisor would imply a precision the
 * input does not have.
 */
private const val DAYS_PER_MONTH: Double = 30.0

private fun windowStart(asOf: Instant, days: Long): Instant = asOf.minus(Duration.ofDays(days))

/**
 * Settlements in `(asOf - days, asOf)` paired with the instruction that carries their amount.
 *
 * The strict `<` bound on [asOf] is the anti-leakage invariant (ADR-0140). The instruction is NOT
 * bounded by the window — a payment instructed in March and settled in June is a June settlement,
 * and dropping its amount because the instruction fell outside the window would report a settled
 * payment with no value.
 */
private fun settledAmounts(
    asOf: Instant,
    events: List<FeatureEvent>,
    days: Long,
    direction: FlowDirection,
): List<Pair<Instant, Long>> {
    val amountByCorrelation =
        events
            .filter { it.correlationId != null && it.amountMinor != null && it.direction == direction }
            .associate { it.correlationId!! to it.amountMinor!! }
    val from = windowStart(asOf, days)
    return events
        .asSequence()
        .filter { it.eventType == TRANSACTION_COMPLETED }
        .filter { it.occurredAt.isBefore(asOf) && !it.occurredAt.isBefore(from) }
        .mapNotNull { settlement ->
            val amount = settlement.correlationId?.let { amountByCorrelation[it] } ?: return@mapNotNull null
            settlement.occurredAt to amount
        }
        .sortedBy { it.first }
        .toList()
}

private class SettledFlowFeature(
    override val name: String,
    private val direction: FlowDirection,
    private val days: Long,
) : FeatureDefinition {
    override val type: FeatureType = FeatureType.LONG
    override val eventTypes: Set<String> = setOf(TRANSACTION_COMPLETED, TRANSACTION_INITIATED)
    override val ttl: Duration = Duration.ofDays(1)

    override fun compute(asOf: Instant, events: List<FeatureEvent>): Double =
        settledAmounts(asOf, events, days, direction).sumOf { it.second }.toDouble()
}

/** Money that settled INTO the entity's account over the last 90 days, in minor units. */
val SETTLED_INFLOW_MINOR_D90: FeatureDefinition =
    SettledFlowFeature("settled_inflow_minor_d90", FlowDirection.IN, D90)

/** Money that settled OUT of the entity's account over the last 90 days, in minor units. */
val SETTLED_OUTFLOW_MINOR_D90: FeatureDefinition =
    SettledFlowFeature("settled_outflow_minor_d90", FlowDirection.OUT, D90)

/**
 * The share of settled inflow that was not spent, over 90 days: `(in - out) / in`.
 *
 * UNDEFINED IS NOT ZERO, AND THIS IS THE WHOLE CARE IN THIS FEATURE. An entity with no settled
 * inflow has no savings rate — the ratio's divisor is zero — and returning `0.0` would report
 * "saves nothing", the least flattering answer for the customer we know least about, in a feature
 * whose consumer is a rewards programme. V10's `volatility_ratio` guards the identical divisor with
 * NULL for the identical reason. `FeatureDefinition.compute` is non-null by contract, so the
 * undefined case is [Double.NaN]: every comparison against NaN is false, so a threshold test on it
 * fails CLOSED — no reward is granted on an unknown — instead of silently passing a zero through.
 *
 * The value is NOT clamped. A negative rate means the entity spent more than it received in the
 * window, which is a real and important state; clamping it to zero would erase exactly the customers
 * a financial-health programme most needs to see.
 */
private class SavingsRateFeature(override val name: String, private val days: Long) : FeatureDefinition {
    override val type: FeatureType = FeatureType.DOUBLE
    override val eventTypes: Set<String> = setOf(TRANSACTION_COMPLETED, TRANSACTION_INITIATED)
    override val ttl: Duration = Duration.ofDays(1)

    override fun compute(asOf: Instant, events: List<FeatureEvent>): Double {
        val inflow = settledAmounts(asOf, events, days, FlowDirection.IN).sumOf { it.second }
        if (inflow == 0L) return Double.NaN
        val outflow = settledAmounts(asOf, events, days, FlowDirection.OUT).sumOf { it.second }
        return (inflow - outflow).toDouble() / inflow.toDouble()
    }
}

/** Savings rate over the last 90 days. [Double.NaN] when there was no settled inflow. */
val SAVINGS_RATE_D90: FeatureDefinition = SavingsRateFeature("savings_rate_d90", D90)

/**
 * Median whole-day gap between consecutive settled OUTBOUND payments over 90 days.
 *
 * Median rather than mean: one salary-day cluster or a single large gap after a dormant period
 * moves a mean enough to invert the answer, and cadence is exactly the quantity a rewards rule
 * would threshold on.
 *
 * Fewer than two settlements gives no gap at all, and [Double.NaN] says so — the same fail-closed
 * choice as [SAVINGS_RATE_D90], and for the same reason: `0.0` would read as "spends every day",
 * the most active possible answer for an entity that has barely transacted.
 */
private class SpendCadenceFeature(override val name: String, private val days: Long) : FeatureDefinition {
    override val type: FeatureType = FeatureType.DOUBLE
    override val eventTypes: Set<String> = setOf(TRANSACTION_COMPLETED, TRANSACTION_INITIATED)
    override val ttl: Duration = Duration.ofDays(1)

    override fun compute(asOf: Instant, events: List<FeatureEvent>): Double {
        val times = settledAmounts(asOf, events, days, FlowDirection.OUT).map { it.first }
        if (times.size < 2) return Double.NaN
        val gaps = times.zipWithNext { a, b -> Duration.between(a, b).toDays() }.sorted()
        val mid = gaps.size / 2
        return if (gaps.size % 2 == 1) {
            gaps[mid].toDouble()
        } else {
            (gaps[mid - 1] + gaps[mid]).toDouble() / 2.0
        }
    }
}

/** Median days between settled outbound payments over the last 90 days. NaN below two settlements. */
val SPEND_CADENCE_DAYS_D90: FeatureDefinition = SpendCadenceFeature("spend_cadence_days_d90", D90)

/**
 * Settled payments in the window whose amount could not be resolved.
 *
 * THE COVERAGE SIGNAL, AND IT IS NOT DECORATION. Every feature above drops a settlement it cannot
 * price, and a drop is invisible: the flow simply reads lower and nothing anywhere disagrees. That
 * is the shape of the push adapter whose skipped result carried `success = true` — a silent no-op
 * with no signal of its own. This feature gives the drop a number, so "this entity spends little"
 * and "we could not price this entity's spending" stop being the same reading.
 *
 * Measured on the warehouse 2026-09-05 for the analogous warehouse join: 47 of 47 settlements paired
 * to an amount, so the expected value today is zero and a non-zero reading is a real regression in
 * the event contract rather than normal noise.
 */
private class AmountlessSettlementFeature(override val name: String, private val days: Long) : FeatureDefinition {
    override val type: FeatureType = FeatureType.LONG
    override val eventTypes: Set<String> = setOf(TRANSACTION_COMPLETED, TRANSACTION_INITIATED)
    override val ttl: Duration = Duration.ofDays(1)

    override fun compute(asOf: Instant, events: List<FeatureEvent>): Double {
        val priced =
            events
                .filter { it.correlationId != null && it.amountMinor != null }
                .mapTo(mutableSetOf()) { it.correlationId!! }
        val from = windowStart(asOf, days)
        return events
            .filter { it.eventType == TRANSACTION_COMPLETED }
            .filter { it.occurredAt.isBefore(asOf) && !it.occurredAt.isBefore(from) }
            .count { it.correlationId == null || it.correlationId !in priced }
            .toDouble()
    }
}

/** Settled payments in the last 90 days that no instruction priced. Expected to be zero. */
val MONEY_EVENTS_WITHOUT_AMOUNT_D90: FeatureDefinition =
    AmountlessSettlementFeature("money_events_without_amount_d90", D90)

/**
 * How many months of the entity's recent outflow its latest known balance would cover.
 *
 * `balance / (outflow_over_window / months_in_window)`. The only feature here that mixes a LEVEL
 * with a FLOW, which is why it names three event types.
 *
 * THE BALANCE IS NOT WINDOWED, AND THAT IS THE LOAD-BEARING CHOICE. It is the latest
 * [BALANCE_UPDATED] strictly before [asOf], at any age. Restricting it to the window would return
 * [Double.NaN] for every account whose balance has not moved in 90 days — a dormant savings
 * account — which is exactly the healthiest buffer a financial-health programme wants to see. A
 * bank statement answers "last known balance" for the same reason. Staleness is the feature
 * store's problem, not this function's: `ttl` bounds how long a computed value is served.
 *
 * [FeatureEvent.amountMinor] must carry the balance LEVEL. Which level is the ingest layer's
 * decision and it matters: `bookedAmount` is the accounting balance, `availableAmount` subtracts
 * holds. For "how long could this person keep spending" the available balance is the truthful one,
 * since a hold is money already committed — so an ingest that maps `bookedAmount` will make this
 * feature read HIGH for an account with large holds. Stated here rather than assumed, because
 * nothing in the type says which arrived.
 *
 * UNDEFINED CASES, both [Double.NaN] for the reason [SAVINGS_RATE_D90] gives: no balance event at
 * all, and zero outflow. Zero outflow is NOT an infinite buffer — it is an unknown one, and
 * `Double.POSITIVE_INFINITY` would pass every `>` threshold a rewards rule could write, granting on
 * an entity that has demonstrably not spent. NaN fails closed instead.
 *
 * NOT CLAMPED. A negative balance yields a negative result, which says the entity is already in
 * deficit — a real state, and clamping it to zero would erase the customers who most need seeing.
 */
private class BufferMonthsFeature(override val name: String, private val days: Long) : FeatureDefinition {
    override val type: FeatureType = FeatureType.DOUBLE
    override val eventTypes: Set<String> = setOf(BALANCE_UPDATED, TRANSACTION_COMPLETED, TRANSACTION_INITIATED)
    override val ttl: Duration = Duration.ofDays(1)

    override fun compute(asOf: Instant, events: List<FeatureEvent>): Double {
        // Strict `isBefore(asOf)` is the anti-leakage invariant (ADR-0140), the same bound
        // `settledAmounts` applies — a balance stamped exactly at asOf is not yet knowable.
        val balance = events
            .filter { it.eventType == BALANCE_UPDATED && it.amountMinor != null }
            .filter { it.occurredAt.isBefore(asOf) }
            .maxByOrNull { it.occurredAt }
            ?.amountMinor
            ?: return Double.NaN

        val outflow = settledAmounts(asOf, events, days, FlowDirection.OUT).sumOf { it.second }
        if (outflow <= 0L) return Double.NaN

        val monthsInWindow = days.toDouble() / DAYS_PER_MONTH
        return balance.toDouble() / (outflow.toDouble() / monthsInWindow)
    }
}

/** Months of recent outflow the latest known balance covers. NaN with no balance or no outflow. */
val BUFFER_MONTHS_D90: FeatureDefinition = BufferMonthsFeature("buffer_months_d90", D90)

/**
 * The settled money-flow features, declared together so a consumer registers the coverage signal
 * alongside the values it qualifies rather than picking the flattering half.
 */
val MONEY_FLOW_FEATURES: List<FeatureDefinition> =
    listOf(
        SETTLED_INFLOW_MINOR_D90,
        SETTLED_OUTFLOW_MINOR_D90,
        SAVINGS_RATE_D90,
        SPEND_CADENCE_DAYS_D90,
        BUFFER_MONTHS_D90,
        MONEY_EVENTS_WITHOUT_AMOUNT_D90,
    )
