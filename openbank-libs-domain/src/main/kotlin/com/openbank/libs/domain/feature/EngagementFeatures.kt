// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.domain.feature

import java.time.Duration
import java.time.Instant

/**
 * Campaign-relevant engagement features (ADR-0201 D3, ADR-0282 phase 1).
 *
 * ADR-0201 D3's central claim is that a second consumer must extend the EXISTING catalogue,
 * computed by the same pure `compute` function that serves both stores — one definition, therefore
 * no training/serving skew. These live beside [VELOCITY_TXN_COUNT_H1] for exactly that reason
 * rather than in campaign-service, where they would be a second catalogue by another name.
 *
 * WHAT THIS CONTRACT CAN AND CANNOT EXPRESS, stated because ADR-0282 phase 1 asks for more than it
 * allows. [FeatureEvent] carries `entityId`, `eventType` and `occurredAt` and nothing else — no
 * amount, no currency, no payload. So counting and recency are expressible and **savings rate and
 * emergency-buffer months are not**: both need money, and money is not on this contract. Widening
 * [FeatureEvent] is a change to the shared type that fraud already depends on, so it is a decision
 * to take deliberately rather than a line to slip into a feature file (#8792).
 *
 * A CUSTOMER-INITIATED ENGAGEMENT IS NOT AN IMPRESSION, and that distinction is the whole point of
 * [CUSTOMER_INITIATED]. An impression is something the BANK did to the customer: it records that a
 * banner was rendered, and it moves whether or not the customer noticed. Counting it as engagement
 * would make "this customer is engaged" mean "we showed them things recently", which is a statement
 * about the bank's own send volume — and a targeting model fed on it learns to reward whoever was
 * marketed to hardest. A dismissal is deliberately excluded for the same reason inverted: it is a
 * real customer action, but the action is refusal, and treating it as engagement would rank a
 * customer who keeps closing banners as one who wants more of them.
 */
private val CUSTOMER_INITIATED: Set<String> = setOf(
    "EngagementEvent.CLICK",
    "EngagementEvent.CONVERSION",
)

/**
 * Days since this party's most recent customer-initiated engagement.
 *
 * Returns [NEVER_ENGAGED_DAYS] when there is none, rather than 0 or a sentinel that reads as
 * "engaged today". A boot-time or never-seen value that reads as the healthiest possible value is
 * the failure this platform has already paid for once, with a liveness gauge seeded at the epoch
 * that reported decades and one seeded at zero that would have reported perfect health.
 */
private object EngagementRecencyDays : FeatureDefinition {
    override val name: String = "engagement_recency_days"
    override val type: FeatureType = FeatureType.DOUBLE
    override val ttl: Duration = Duration.ofDays(1)
    override val eventTypes: Set<String> = CUSTOMER_INITIATED

    override fun compute(asOf: Instant, events: List<FeatureEvent>): Double {
        val last = events
            .filter { it.eventType in eventTypes && it.occurredAt.isBefore(asOf) }
            .maxByOrNull { it.occurredAt }
            ?: return NEVER_ENGAGED_DAYS
        return Duration.between(last.occurredAt, asOf).toMillis().toDouble() / MILLIS_PER_DAY
    }
}

/** Customer-initiated engagements in the 30 days before `asOf`. */
private object EngagementCountD30 : FeatureDefinition {
    override val name: String = "engagement_count_d30"
    override val type: FeatureType = FeatureType.LONG
    override val ttl: Duration = Duration.ofDays(1)
    override val eventTypes: Set<String> = CUSTOMER_INITIATED

    override fun compute(asOf: Instant, events: List<FeatureEvent>): Double {
        val windowStart = asOf.minus(Duration.ofDays(WINDOW_DAYS))
        return events.count {
            it.eventType in eventTypes &&
                // strict < asOf — anti-leakage (ADR-0140), the same bound VelocityFeatures uses:
                // an event AT asOf is not knowable at asOf, and letting it in is how an offline
                // training set learns from the future while the online path cannot.
                it.occurredAt.isBefore(asOf) &&
                !it.occurredAt.isBefore(windowStart)
        }.toDouble()
    }
}

/**
 * A party who has never engaged, expressed in days. Deliberately large and deliberately NOT
 * `Double.MAX_VALUE`: it has to survive arithmetic in a consumer without becoming infinity, and it
 * has to be obviously a sentinel when a human reads it on a dashboard. Ten years.
 */
const val NEVER_ENGAGED_DAYS: Double = 3650.0

private const val MILLIS_PER_DAY: Double = 86_400_000.0
private const val WINDOW_DAYS: Long = 30

val ENGAGEMENT_RECENCY_DAYS: FeatureDefinition = EngagementRecencyDays
val ENGAGEMENT_COUNT_D30: FeatureDefinition = EngagementCountD30

/** The campaign-relevant additions, kept separate from fraud's [PHASE1_FEATURES]. */
val ENGAGEMENT_FEATURES: List<FeatureDefinition> = listOf(ENGAGEMENT_RECENCY_DAYS, ENGAGEMENT_COUNT_D30)
