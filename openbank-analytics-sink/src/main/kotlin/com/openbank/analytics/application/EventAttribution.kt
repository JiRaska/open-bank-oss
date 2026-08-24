// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.analytics.application

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
    )

    /** Domain segment → service name, where it is not `openbank-<segment>-service`. */
    private val SERVICE_OVERRIDES = mapOf(
        "documents" to "openbank-document-service",
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

    /** `openbank.sca.events` → `openbank-sca-service`; null when the topic is unattributable. */
    fun sourceService(topic: String?): String? {
        val domain = domainOf(topic) ?: return null
        return SERVICE_OVERRIDES[domain] ?: "openbank-$domain-service"
    }
}
