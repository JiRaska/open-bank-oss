// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.analytics

/**
 * Which module produces a given Kafka topic — ONE definition, read by every consumer that has to
 * attribute an event whose body does not say.
 *
 * WHY THIS IS A TABLE AND NOT A DERIVATION. The obvious implementation reads the topic's domain
 * segment (`openbank.<domain>.…`) and builds a service name from it. Measured on the sandbox
 * warehouse 2026-09-06, that is wrong often enough to matter and wrong in a way nothing detects:
 * `openbank.transactions.transaction.initiated` yields `openbank-transactions-service`, a module
 * that does not exist (the real one is singular), and `openbank.standing-orders.order.event` yields
 * `standing-orders`, likewise not a module name. audit-service reached this conclusion first and
 * its own comments say so; this file is that table, moved to where both readers can share it.
 *
 * THE COST OF GETTING IT WRONG IS PERMANENT AND MEASURED. `source_service` is the column every
 * audit and analytics query groups by. bronze_events currently holds THREE spellings for two
 * producers, with a visible boundary: `openbank-balance-service` (57 rows, 2026-08-13 to 08-18)
 * then `balance-service` (45 rows, 08-19 onward), and `openbank-transactions-service` (48 rows)
 * then `transaction-service` (30 rows). The boundary is the day producers began stamping the field
 * in the body, which stopped the fallback firing. Every one of those rows is unrewritable: bronze is
 * append-only (ADR-0022) and `audit_entries` is append-only at the database with `source_service`
 * chain-hashed into `record_hash` (ADR-0031/0133). The fallback gets exactly one chance per event.
 *
 * THE RULE FOR ADDING A ROW. Read the value off the module that DECLARES the outgoing channel in
 * its `application.yaml`, never from the topic string. A topic whose producer stamps `sourceService`
 * in the body never reaches here — the body wins — so an entry is only load-bearing for producers
 * that do not, and adding one for a producer that does is harmless but pointless.
 *
 * The value is the module directory name WITHOUT the `openbank-` prefix, which is the fleet
 * convention `rules.yaml` and `check-source-service-convention.py` enforce on producers themselves.
 */
object TopicProducers {

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
        // #8792: the four topics analytics-sink subscribes to that audit-service does not, added
        // when this table became the shared definition. Same rule as every row above — read off the
        // module that DECLARES the outgoing channel, not from the topic segment, which would have
        // guessed "onboarding", "feedback" and "credit" for three channels that all belong to
        // customer-edge.
        //
        // openbank-customer-edge/src/main/resources/application.yaml -> onboarding-funnel-out,
        // feedback-events-out and credit-funnel-out. The edge writes all three because they are
        // journey telemetry from the public surface, not domain events of an owning service.
        "openbank.onboarding.funnel.events" to "customer-edge",
        "openbank.feedback.events" to "customer-edge",
        "openbank.credit.funnel.events" to "customer-edge",
        // openbank-engagement-service/src/main/resources/application.yaml -> engagement-events-out.
        "openbank.engagement.events" to "engagement-service",
    )

    /** Topics with a verified producer entry. Visible for coverage tests. */
    val mappedTopics: Set<String> get() = TOPIC_TO_SERVICE.keys

    /** The service that produces [topic], or null when the topic is not one of ours. */
    fun sourceService(topic: String?): String? {
        if (topic.isNullOrBlank()) return null
        return TOPIC_TO_SERVICE[topic]
    }
}
