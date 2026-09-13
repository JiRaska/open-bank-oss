// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.feature

import java.time.Duration
import java.time.Instant

/** Which way money moved for the entity a [FeatureEvent] is keyed by. */
enum class FlowDirection { IN, OUT }

/**
 * A domain event a feature consumes, carrying its **business event time** (`occurredAt`) — the
 * as-of source ADR-0140 mandates (never wall-clock-at-write). Pure value object.
 *
 * THE THREE OPTIONAL FIELDS EXIST BECAUSE NO SINGLE EVENT CARRIES BOTH SETTLEMENT AND AMOUNT.
 * Measured on the warehouse 2026-09-05: `TransactionCompleted` says a payment settled and carries
 * only a reference number, while `TransactionInitiated` carries amount, currency and accounts but
 * is emitted before the money moves. A money feature therefore has to pair them, and a flat event
 * list with no correlation key makes that impossible — which is why the money features could not be
 * expressed against the original three-field shape, not merely inconvenient to write.
 *
 * Each field is null for every event that has no such fact, and a feature that needs one must say
 * what it does when it is absent rather than treating null as zero. Dropping an amount-less money
 * event silently under-counts spending, which is the flattering direction and therefore the
 * dangerous one for anything that rewards behaviour — [MONEY_EVENTS_WITHOUT_AMOUNT_D90] exists so
 * that gap is a number rather than an absence.
 *
 * @param amountMinor money in MINOR units (haléře, cents). Never a floating-point amount: a rate
 *   computed from Doubles is fine, an amount stored as one is a defect.
 * @param correlationId the id that pairs events about the same business fact — the transaction's
 *   own aggregate id, which both the initiated and the completed event carry.
 * @param direction which way the money moved for [entityId]; the same transaction is OUT for the
 *   source account and IN for the target, so an internal transfer between two of the customer's own
 *   accounts is correctly both.
 */
data class FeatureEvent(
    val entityId: String,
    val eventType: String,
    val occurredAt: Instant,
    val amountMinor: Long? = null,
    val correlationId: String? = null,
    val direction: FlowDirection? = null,
)

/** The scalar type a feature materialises to. */
enum class FeatureType { LONG, DOUBLE }

/**
 * A value read from the online store, carrying its provenance and a freshness verdict (ADR-0140).
 * Callers MUST treat [Stale] exactly like [Missing] — a stale feature is never a confident value.
 */
sealed interface FeatureValue {
    /** A value within its freshness budget. */
    data class Fresh(val value: Double, val asOf: Instant, val sourceOffset: Long) : FeatureValue

    /** Present but no longer valid for [asOf] at read time — treat as [Missing]. */
    data class Stale(val asOf: Instant, val sourceOffset: Long) : FeatureValue

    /** No value for this key. */
    data object Missing : FeatureValue
}

/**
 * One feature, **declared once** (ADR-0140 "one definition, two materialisations"). [compute]
 * reconstructs the value as-of an instant from events strictly before it — the *same* pure function
 * used to update the online store and to reconstruct offline, so the two materialisations cannot
 * skew. No framework imports (ADR-0002) — fully unit-testable in isolation.
 */
interface FeatureDefinition {
    /** Stable feature name, e.g. `velocity_txn_count_h1`. Also the online-store key prefix. */
    val name: String

    /** Scalar type. */
    val type: FeatureType

    /** Freshness budget. A value as-of older than this (per [isStale]) is treated as missing. */
    val ttl: Duration

    /** Event types this feature consumes, e.g. `{"TransactionInitiated"}`. */
    val eventTypes: Set<String>

    /**
     * Pure as-of reconstruction. **Only events with `occurredAt` strictly before [asOf] are
     * visible** — the strict `<` bound is the anti-leakage invariant (ADR-0140).
     */
    fun compute(asOf: Instant, events: List<FeatureEvent>): Double

    /**
     * The freshness assertion (ADR-0140). Default: a value is stale once it is older than [ttl].
     * Windowed features override this with window-boundary semantics.
     */
    fun isStale(asOf: Instant, now: Instant): Boolean = Duration.between(asOf, now) > ttl
}
