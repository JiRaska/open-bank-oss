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
        "openbank.cards.events" to "card-issuance-service",
        // Card MONEY PATH, distinct from the lifecycle topic above and from any domain-segment
        // derivation: `openbank.card.processing.*` would derive to "card-service", which does not
        // exist (ADR-0283 phase 1).
        "openbank.card.processing.events" to "card-processing-service",
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
        // Issue #5338: audit-service was not subscribed to this topic at all (absent from both
        // this table and application.yaml's topics list), so SCA device enrollment
        // (DEVICE_ENROLLED) was never audited — a real PSD2/SCA gap, since sca-service is
        // money-path. PR #5337 adds "sourceService" to the enroll payload so this row resolves
        // AttributionSource.EVENT rather than TOPIC once it merges; until then the topic-derived
        // value below is still correct (ScaService.kt's outbox payload sets no source field yet,
        // and "sca-service" is what it will say when it does).
        "openbank.sca.events" to "sca-service",
        // Issue #5728 (ADR-0249 D4): audit-service did not subscribe to this topic at all, so no
        // delegation grant lifecycle event was ever audited -- and delegation-service is
        // money-path (it mints payment rights). Subscribing it is what makes the new spend
        // reservation events (SpendReserved/SpendConfirmed/SpendReleased) reach the audit trail;
        // emitting them without this would have been an outbox row nobody reads. Those three
        // carry their own "sourceService", so they resolve AttributionSource.EVENT; the eight
        // older lifecycle events do not yet, and resolve TOPIC through this row.
        "openbank.delegation.events" to "delegation-service",
        "openbank.delegation.spend-reservation-state" to "delegation-service",
        // Issue #6035: four more money-path producers were absent from all three places at once
        // (this table, application.yaml's topics list, and the audit KafkaUser's Read ACLs) --
        // found by .github/scripts/check-audit-money-path-subscription.py, which derives the set
        // from `money_path_services` and each service's own outgoing channel declaration. Every
        // value below is read off the module that DECLARES the outgoing channel, not derived from
        // the topic's domain segment: a derivation writes a plausible, confident, FALSE service
        // name into a tamper-evident record, and `source_service` is chain-hashed into
        // `record_hash`, so it gets exactly one chance to be right.
        //
        // None of the four sets a "sourceService" body field today, so each resolves
        // AttributionSource.TOPIC through this row; a producer that later populates the field
        // keeps its own value and upgrades to AttributionSource.EVENT with no edit here.
        //
        // openbank-ledger-service/src/main/resources/application.yaml -> ledger-events-out.
        // The posting record itself -- the largest of the five gaps #6035 names.
        "openbank.ledger.journal.posted" to "ledger-service",
        // openbank-sdd-service/src/main/resources/application.yaml -> sdd-events-out.
        "openbank.sdd.event" to "sdd-service",
        // openbank-interest-service/src/main/resources/application.yaml -> interest-events-out.
        "openbank.interest.accrual.event" to "interest-service",
        // openbank-fraud-service/src/main/resources/application.yaml -> fraud-outbox-out.
        "openbank.fraud.hold.changed" to "fraud-service",
        // Issue #6035, second and final backfill: the last KNOWN_GAPS entries of
        // check-audit-money-path-subscription.py. Same rule as the four above -- each value is
        // read off the module that DECLARES the outgoing channel, never derived from the topic's
        // domain segment, because `source_service` is chain-hashed into `record_hash` and
        // `audit_entries` is append-only at the DB, so it gets exactly one chance to be right.
        // (The segment-derived guess would have been "billing" and "standing-orders";
        // neither is the module name.)
        //
        // Both publish through the shared outbox relay (`OutboxKafkaHeaders.headersFor`) and
        // neither sets a "sourceService" body field today, so each resolves AttributionSource.TOPIC
        // through this row; a producer that later populates the field keeps its own value and
        // upgrades to AttributionSource.EVENT with no edit here.
        //
        // (The third entry of the original backfill, openbank.psd2.events, left with its
        // producer: the psd2-events-out emitter and the topic itself were deleted in #8510,
        // so there is nothing left to attribute.)
        //
        // openbank-billing-service/src/main/resources/application.yaml -> billing-events-out.
        "openbank.billing.fee.event" to "billing-service",
        // openbank-standing-order-service/src/main/resources/application.yaml ->
        // standing-order-events-out.
        "openbank.standing-orders.order.event" to "standing-order-service",
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
