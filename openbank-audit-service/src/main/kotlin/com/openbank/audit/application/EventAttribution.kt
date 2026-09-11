// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

import com.openbank.libs.analytics.TopicProducers

/**
 * Broker-side addressing for one consumed audit record (#3994).
 *
 * [AuditConsumer.consume] used to take `payload: String`, so it saw the message BODY and nothing
 * else. Two facts the transport was already carrying were therefore dropped at ingest:
 *
 *  - the **event type**, published as the `ce-type` Kafka header by every outbox-relayed producer
 *    (`OutboxKafkaHeaders.HEADER_EVENT_TYPE`). The body of an outbox event is the bare domain
 *    event, which often has no `eventType` key at all — so the discriminator was on the wire and
 *    unreadable, and the row stored `"UNKNOWN"`.
 *  - the **topic**, which names the producing domain by the fleet's own convention (ADR-0003 N3).
 *
 * Same defect, same cause and the same fix as the analytics sink's #2598 — that consumer had the
 * identical `consume(payload: String)` signature and the identical pair of `UNKNOWN`/`unknown`
 * defaults. This is the audit-side counterpart.
 */
data class EventAddress(
    /** Kafka topic the record arrived on, e.g. `openbank.party.events`. */
    val topic: String? = null,
    /** `ce-type` header — the outbox event type (`OutboxKafkaHeaders.HEADER_EVENT_TYPE`). */
    val ceType: String? = null,
) {
    companion object {
        /** No broker metadata available — a hand-constructed or replayed record. */
        val NONE = EventAddress()
    }
}

/**
 * Topic -> producing service, for the topics this service subscribes to.
 *
 * **Why a table and not a derivation.** The sibling sink derives the service from the topic's
 * domain segment (`openbank.<domain>.…` -> `openbank-<domain>-service`) and that is right for its
 * purpose, but it is WRONG for eight of the twenty topics audited here — `openbank.cards.events`
 * is produced by card-issuance-service, `openbank.payments.swift.event` by swift-service,
 * `openbank.customer.audit` by customer-edge, `openbank.security.ict.incident` by security-scanner,
 * and `accounts`/`transactions`/`documents` are plural where the module is singular. A derivation
 * that is wrong eight times out of twenty does not under-attribute: it writes a plausible,
 * confident, FALSE service name into a tamper-evident evidentiary record, which is strictly worse
 * than the `"unknown"` it replaces. So every value here is verified against the module that
 * actually declares the outgoing channel, and [AttributionSource] keeps a derived value
 * distinguishable from a producer-declared one at every point downstream.
 *
 * **Why the scope is still not hand-kept.** A hand-kept table reads as complete when it is short —
 * the failure this repo has been bitten by repeatedly. `AuditTopicAttributionTest` parses the
 * subscribed topic list out of `application.yaml` and fails on any topic missing an entry here, and
 * on any entry here that is no longer subscribed. The VALUES are facts a human verified; the
 * COVERAGE is derived from the config, so a newly subscribed topic cannot quietly default to
 * `"unknown"` again.
 *
 * Values use the fleet's audit convention — the module directory without the `openbank-` prefix,
 * which is what `customer-edge` (the one producer that populates the field today) already writes.
 */
object TopicAttribution {
    // The topic -> producing-module table moved to openbank-libs-domain
    // (com.openbank.libs.analytics.TopicProducers) so analytics-sink reads the SAME rows rather
    // than deriving a name from the topic's domain segment — a derivation that produced
    // `openbank-transactions-service`, a module that does not exist. The values are unchanged:
    // `source_service` is chain-hashed into `record_hash` and audit_entries is append-only, so a
    // moved value would be a new spelling, not a refactor. The reasoning for each row lives with
    // the table.

    /** Topics with a verified producer entry. Visible for the coverage test. */
    val mappedTopics: Set<String> get() = TopicProducers.mappedTopics

    /**
     * The service that produces [topic], or null when the topic is not one of ours.
     *
     * Null rather than a guess: an unrecognised topic keeps `"unknown"`, which is honest, instead
     * of acquiring a service name derived from a naming convention it may not follow.
     */
    fun sourceService(topic: String?): String? = TopicProducers.sourceService(topic)
}
