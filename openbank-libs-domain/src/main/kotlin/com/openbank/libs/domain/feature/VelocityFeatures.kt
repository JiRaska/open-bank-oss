// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.feature

import java.time.Duration
import java.time.Instant

/** Event type emitted by transaction-service (`TransactionInitiatedEvent.eventType`). */
const val TRANSACTION_INITIATED: String = "TransactionInitiated"

/**
 * Count of [TRANSACTION_INITIATED] events for an entity within the current tumbling [window],
 * reconstructed as-of an instant. Freshness is **window-boundary based**: a stored value whose
 * bucket differs from the read instant's bucket is stale (the current bucket has recorded nothing
 * yet), which is the correct staleness signal for a tumbling counter — not a flat TTL.
 */
private class VelocityTxnCountFeature(
    private val window: VelocityWindow,
    override val name: String,
    override val ttl: Duration,
) : FeatureDefinition {
    override val type: FeatureType = FeatureType.LONG
    override val eventTypes: Set<String> = setOf(TRANSACTION_INITIATED)

    override fun compute(asOf: Instant, events: List<FeatureEvent>): Double {
        val bucketStart = window.bucketStart(asOf)
        return events.count { event ->
            event.eventType in eventTypes &&
                event.occurredAt.isBefore(asOf) &&
                // strict < asOf — anti-leakage (ADR-0140)
                !event.occurredAt.isBefore(bucketStart) // within the current tumbling bucket
        }.toDouble()
    }

    override fun isStale(asOf: Instant, now: Instant): Boolean = window.bucketStart(asOf) != window.bucketStart(now)

    /** The bucket an event at [eventTime] increments — used by the online updater. */
    fun bucketStart(eventTime: Instant): Instant = window.bucketStart(eventTime)
}

/** H1 transaction-count velocity feature (current clock hour). */
val VELOCITY_TXN_COUNT_H1: FeatureDefinition =
    VelocityTxnCountFeature(VelocityWindow.H1, name = "velocity_txn_count_h1", ttl = Duration.ofHours(1))

/** H24 transaction-count velocity feature (current clock day). */
val VELOCITY_TXN_COUNT_H24: FeatureDefinition =
    VelocityTxnCountFeature(VelocityWindow.H24, name = "velocity_txn_count_h24", ttl = Duration.ofDays(1))

/**
 * The features declared for ADR-0139/0140 **phase 1**: H1 and H24 only. `D7` is deliberately
 * deferred ("folded in as the catalogue expands", ADR-0140) and is not declared here.
 */
val PHASE1_FEATURES: List<FeatureDefinition> = listOf(VELOCITY_TXN_COUNT_H1, VELOCITY_TXN_COUNT_H24)

/**
 * The tumbling-bucket start for [eventTime] under [feature], when [feature] is a windowed velocity
 * feature; `null` for non-windowed features. Lets the online updater compute the bucket without
 * exposing the private feature class.
 */
fun windowBucketStart(feature: FeatureDefinition, eventTime: Instant): Instant? =
    (feature as? VelocityTxnCountFeature)?.bucketStart(eventTime)
