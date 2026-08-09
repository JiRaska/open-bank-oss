// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.audit.domain.model

import java.time.Instant
import java.util.UUID

data class AuditEntry(
    val id: UUID,
    val eventType: String,
    val aggregateType: String,
    val aggregateId: String,
    val actorId: String?,
    val actorType: String?,
    val payload: String,
    val sourceService: String,
    val correlationId: String?,
    val occurredAt: Instant,
    val recordedAt: Instant,
    /**
     * Whether [occurredAt] is the producer's own event time or a stand-in for it.
     *
     * "When it happened" and "when we recorded it" are different facts, and [recordedAt] already
     * holds the second one. When a producer's payload carries no `occurredAt`, the consumer has
     * nothing better than ingest time to put here — and a row that says so is worth far more than
     * one that quietly claims the queue's clock was the event's (#3883).
     */
    val occurredAtSource: OccurredAtSource,
    /**
     * Whether [sourceService] is the producer's own claim or the broker topic standing in for it.
     *
     * The same distinction [occurredAtSource] draws for time, drawn for attribution, and for the
     * same reason: a value the consumer supplied must never be indistinguishable from one the
     * producer asserted (#3994).
     */
    val sourceServiceSource: AttributionSource = AttributionSource.ABSENT,
    /** Ingress channel the event arrived through — ui|mcp|api (ADR-0226); null = unknown/legacy. */
    val channel: String? = null,
    /** Ordered on-behalf-of delegation chain from the RFC 8693 `act` claim (ADR-0224); empty = direct. */
    val actChain: List<String> = emptyList(),
    /** Browser or agent session the action belongs to; groups one sitting's events. */
    val sessionId: String? = null,
    /**
     * The party this action was taken ON BEHALF OF — the account owner who issued the delegation
     * grant (ADR-0232 D5). Null for a direct action, which is the overwhelming majority.
     *
     * [actorId] stays the DELEGATE: who did it does not change because they were allowed to.
     * This is the second half of the pair, and it is what makes the grantor transparency query
     * ("what did they do with my account") answerable at all.
     *
     * Like [channel]/[actChain], a query index derived from the chain-hashed [payload], not an
     * independent claim — see V12__delegated_action_index.sql.
     */
    val onBehalfOf: String? = null,
    /** The delegation grant that permitted the action; null for a direct action (ADR-0232 D5). */
    val delegationId: String? = null,
)

/**
 * Provenance of [AuditEntry.occurredAt] (#3883).
 *
 * The fleet's canonical event-time key is `occurredAt` — declared on
 * `com.openbank.libs.domain.event.DomainEvent` and emitted by 14 of the 21 topics this service
 * consumes. The remaining producers emit no event time at all (or, in document-service's case,
 * name it `at`), and for those the consumer used to substitute `Instant.now(clock)` with nothing
 * anywhere recording that it had done so. This enum is that record.
 *
 * Deliberately NOT a second parsing fallback: accepting `at`/`timestamp` too would restore the
 * silence this exists to end. A producer that emits the wrong key now shows up as [INGEST] rows
 * and on `openbank.audit.event.time.missing`, and gets fixed at the producer.
 */
/**
 * Provenance of an attribution field — who says so (#3994).
 *
 * `source_service` was `node["sourceService"] ?: "unknown"`, and 76% of the live audit trail is
 * that `"unknown"`: `customer-edge` is the only producer in the fleet that populates the field.
 * The consumer can now recover the producer from the Kafka topic for every subscribed topic
 * (`TopicAttribution`), which retires almost all of that — but a recovered value is a different
 * kind of claim from a declared one, and an evidentiary store must not blur the two.
 *
 * That is the whole point of this enum. Substituting a plausible value silently is exactly the
 * defect being fixed: it converts a visibly missing attribution into an invisible wrong one, and
 * the second is far harder to ever notice again. The row now carries who supplied the answer.
 */
enum class AttributionSource {
    /** The producer put the field in its own payload; the strongest claim available. */
    EVENT,

    /**
     * Derived from the Kafka topic the record arrived on. Sound — the topic is transport-level
     * addressing the producer cannot forge by omission — but it identifies the producing SERVICE,
     * not a producer's own assertion about itself.
     */
    TOPIC,

    /** Neither available: the field holds the `"unknown"` sentinel and attributes nothing. */
    ABSENT,
}

enum class OccurredAtSource {
    /** The producer sent a parseable `occurredAt`; [AuditEntry.occurredAt] is the real event time. */
    EVENT,

    /**
     * No parseable `occurredAt` in the payload, so [AuditEntry.occurredAt] is ingest time —
     * an upper bound on when the operation happened, not the operation's own time.
     */
    INGEST,
}
