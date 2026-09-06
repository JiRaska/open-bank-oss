// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application

import com.openbank.libs.analytics.TopicProducers

/**
 * Broker-side attribution for one consumed record (issue #2598).
 *
 * The sink used to read only the message *body*, and the body of an outbox-relayed event is
 * `OutboxEntry.payload` — the bare domain event. The outbox's own addressing does not travel in
 * that JSON: the event type rides in the `ce-type` Kafka header, the aggregate id is the record
 * KEY (`OutboxKafkaHeaders.partitionKey`), and the producing domain is the topic. So for every
 * producer that publishes a bare payload rather than a self-describing envelope, the sink
 * defaulted `aggregate_type`/`event_type` to `UNKNOWN` and `source_service` to `unknown` — with
 * the payload sitting right there, plainly identifiable. Nothing errored: the row landed, the
 * consumer stayed healthy, the pipeline was green, and three columns of attribution were gone.
 *
 * This carries the metadata the transport already had, so the fallbacks have something to fall
 * back TO.
 */
data class EventAddress(
    /** Kafka topic the record arrived on, e.g. `openbank.sca.events`. */
    val topic: String? = null,
    /** Record key — the outbox partition key, which IS the aggregate id. */
    val key: String? = null,
    /** `ce-type` header — the outbox event type. */
    val ceType: String? = null,
    /** Durable synthetic-origin header reconstructed from the outbox entry. */
    val synthetic: Boolean = false,
) {
    companion object {
        val NONE = EventAddress()
    }
}

/**
 * Derives the producing domain and service from a topic name.
 *
 * The fleet's topic convention is `openbank.<domain>[.<detail>].<events|event>` (ADR-0003 N3),
 * so the domain segment is a *derivable* fact rather than a hand-kept list. That matters: a
 * hand-kept map would silently readmit `UNKNOWN` for the next topic anyone subscribes, which is
 * exactly the failure shape this fixes. The map below only overrides where the segment is not
 * the domain word we file rows under.
 */
object TopicAttribution {

    /**
     * Domain segment → aggregate type, where the plain uppercase of the segment is not what the
     * silver views group by. Keep this SMALL: an entry here is an exception to the derivation,
     * not a registration, and an unmapped topic still attributes correctly.
     */
    private val AGGREGATE_OVERRIDES = mapOf(
        "documents" to "DOCUMENT",
        "onboarding" to "ONBOARDING",
        "feedback" to "FEEDBACK",
        // #8792. Singularised for the same reason as `documents` above, but the load-bearing part is
        // that these must equal what `inferAggregateType` derives from the BODY. The body path wins,
        // so a disagreement files one event family under `CARD` and its sibling under `CARDS` — the
        // ACCOUNT/Account split of #4553, which gave one aggregate two current-state rows.
        "cards" to "CARD",
        "standing-orders" to "STANDING_ORDER",
    )

    /** `openbank.sca.events` → `sca`; null if the topic does not follow the convention. */
    fun domainOf(topic: String?): String? {
        if (topic.isNullOrBlank()) return null
        val parts = topic.split('.')
        if (parts.size < 2 || parts[0] != "openbank") return null
        return parts[1].takeIf { it.isNotBlank() }
    }

    /** `openbank.sca.events` → `SCA`; null when the topic is unattributable. */
    fun aggregateType(topic: String?): String? {
        val domain = domainOf(topic) ?: return null
        return AGGREGATE_OVERRIDES[domain] ?: domain.uppercase().replace('-', '_')
    }

    /**
     * `openbank.sca.events` → `sca-service`; null when no module is known to produce [topic].
     *
     * DELEGATED, NOT DERIVED, AND THAT IS THE FIX. This used to build the name from the topic's
     * domain segment as `openbank-<segment>-service`, which is wrong twice over. It carries the
     * `openbank-` prefix the fleet convention forbids (`check-source-service-convention.py`: the
     * value is the module directory name WITHOUT it), and the segment is not the module name —
     * `openbank.transactions.…` derived `openbank-transactions-service`, which does not exist, and
     * `openbank.standing-orders.…` would derive `openbank-standing-orders-service`, likewise not a
     * module.
     *
     * Both spellings are in the warehouse today. `bronze_events` holds `openbank-balance-service`
     * (57 rows) and `balance-service` (45), `openbank-transactions-service` (48) and
     * `transaction-service` (30), the boundary falling on 2026-08-18/19 — the day producers began
     * stamping the field in the body, which stopped this fallback firing. Those rows are
     * unrewritable: bronze is append-only (ADR-0022).
     *
     * Returning null for an unknown topic is deliberate and is NOT a regression from the old
     * behaviour: the caller then records `unknown`, which is visibly missing attribution, where the
     * derivation produced a confident name for a module nobody has. A wrong answer is worse than an
     * absent one here, because only the absent one is countable.
     */
    fun sourceService(topic: String?): String? = TopicProducers.sourceService(topic)
}
