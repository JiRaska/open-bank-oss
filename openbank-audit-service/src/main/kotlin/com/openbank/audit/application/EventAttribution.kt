// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.application

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
 * purpose, but it is WRONG for nine of the twenty-one topics audited here — `openbank.cards.events`
 * is produced by card-issuance-service, `openbank.payments.swift.event` by swift-service,
 * `openbank.customer.audit` by customer-edge, `openbank.security.*` by security-scanner, and
 * `accounts`/`transactions`/`documents` are plural where the module is singular. A derivation that
 * is wrong nine times out of twenty-one does not under-attribute: it writes a plausible,
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

    private val TOPIC_TO_SERVICE = mapOf(
        "openbank.accounts.account.created" to "account-service",
        "openbank.transactions.transaction.initiated" to "transaction-service",
        "openbank.balance.events" to "balance-service",
        "openbank.party.events" to "party-service",
        "openbank.kyc.events" to "kyc-service",
        "openbank.consent.events" to "consent-service",
        "openbank.customer.audit" to "customer-edge",
        "openbank.clearing.batch.event" to "clearing-service",
        "openbank.security.ict.incident" to "security-scanner",
        "openbank.security.scan.event" to "security-scanner",
        "openbank.cards.events" to "card-issuance-service",
        "openbank.dispute.events" to "dispute-service",
        "openbank.domestic.payment.events" to "domestic-payment",
        "openbank.sepa.payment.events" to "sepa-payment",
        "openbank.statement.event" to "statement-service",
        "openbank.sanctions.screening.event" to "sanctions-service",
        "openbank.sepa.instant.events" to "sepa-instant",
        "openbank.fx.conversion.completed" to "fx-service",
        "openbank.documents.document.event" to "document-service",
        "openbank.payments.swift.event" to "swift-service",
        "openbank.lending.events" to "lending-service",
    )

    /** Topics with a verified producer entry. Visible for the coverage test. */
    val mappedTopics: Set<String> get() = TOPIC_TO_SERVICE.keys

    /**
     * The service that produces [topic], or null when the topic is not one of ours.
     *
     * Null rather than a guess: an unrecognised topic keeps `"unknown"`, which is honest, instead
     * of acquiring a service name derived from a naming convention it may not follow.
     */
    fun sourceService(topic: String?): String? {
        if (topic.isNullOrBlank()) return null
        return TOPIC_TO_SERVICE[topic]
    }
}
