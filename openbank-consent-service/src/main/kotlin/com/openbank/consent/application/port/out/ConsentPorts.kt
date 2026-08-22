// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.consent.application.port.out

import com.openbank.consent.domain.model.Consent
import com.openbank.libs.domain.event.DomainEvent
import io.smallrye.mutiny.Uni
import java.time.OffsetDateTime
import java.util.UUID

/** Outbound persistence port for the consent aggregate. */
interface ConsentRepository {

    suspend fun save(consent: Consent): Consent

    /**
     * Persist the consent aggregate state change AND [event] to the transactional outbox in a
     * SINGLE database transaction (transactional outbox, ADR-0126 §D3). The event is durable iff
     * the status change commits, and
     * [com.openbank.consent.infrastructure.outbox.ConsentOutboxDispatcher] relays it to Kafka
     * at-least-once — closing the direct-Kafka dual-write that silently dropped the event on a
     * crash between the DB commit and the send.
     */
    suspend fun save(consent: Consent, event: DomainEvent): Consent = saveSuperseding(consent, event, emptyList())

    /**
     * Persist [consent] with [event], AND every aggregate in [superseded] with its own event, in a
     * SINGLE transaction (issue #6487).
     *
     * Atomicity is the whole point: an activation that commits without its supersedes leaves two
     * ACTIVE consents for the same grantee and scopes, each independently sufficient to grant
     * access — which is the defect this exists to prevent. Two sequential [save] calls cannot give
     * that guarantee.
     */
    suspend fun saveSuperseding(
        consent: Consent,
        event: DomainEvent,
        superseded: List<Pair<Consent, DomainEvent>>,
    ): Consent

    suspend fun findById(id: UUID): Consent?

    suspend fun findByPartyId(partyId: UUID): List<Consent>

    suspend fun findByGranteeId(granteeId: String): List<Consent>

    suspend fun findActiveByGranteeAndParty(granteeId: String, partyId: UUID): List<Consent>

    /**
     * Reactive sweep: find all ACTIVE consents with validTo < threshold.
     * Returns a Uni so callers can compose it in a reactive pipeline without blocking. The
     * scheduled sweeper awaits that pipeline from a `suspend fun`, which is what puts it on a
     * Vert.x context — subscribing from a plain `@Scheduled` method does NOT (#2913).
     */
    fun findExpiredActive(threshold: OffsetDateTime): Uni<List<Consent>>

    /**
     * Atomically transition a single consent from ACTIVE → EXPIRED if and only if the row is still
     * ACTIVE (optimistic guard) AND enqueue [event] to the transactional outbox in the SAME
     * transaction (ADR-0126 §D4). Returns a Uni<Boolean>: true if the row transitioned (and the
     * event was enqueued), false if it was already in a terminal state (no event). Mirrors the
     * command-path [save] atomicity so the sweep cannot mark a consent EXPIRED without durably
     * enqueueing the ConsentExpired event.
     */
    fun markExpired(id: UUID, expiredAt: OffsetDateTime, event: DomainEvent): Uni<Boolean>
}

/** Read-model snapshot of an SCA challenge as fetched from the sca-service. */
data class ScaChallengeSnapshot(val id: UUID, val partyId: UUID, val purpose: String, val status: String)

/** Outbound port to the sca-service for resolving the state of an SCA challenge. */
interface ScaChallengeClient {

    suspend fun getChallenge(challengeId: UUID): ScaChallengeSnapshot
}
