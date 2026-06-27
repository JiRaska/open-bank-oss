// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sepainstant.application.port.out

import java.time.Instant
import java.util.UUID

/** Lifecycle of a transactional-outbox row as it is drained to Kafka. */
enum class SctInstOutboxStatus { PENDING, SENT, FAILED }

/**
 * A domain event to be written into the outbox in the same transaction as the aggregate change.
 * [eventId] and [createdAt] are assigned at construction so callers only supply the payload identity.
 */
data class SctInstOutboxMessage(
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
    val eventId: UUID = UUID.randomUUID(),
    val createdAt: Instant = Instant.EPOCH
)

/** A persisted outbox row as read back by the dispatcher. */
data class SctInstOutboxEntry(
    val eventId: UUID,
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
    val status: SctInstOutboxStatus,
    val attemptCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val sentAt: Instant?,
    val lastError: String?
)

/** Outbound port for draining the transactional outbox (read pending, mark sent/failed). */
interface SctInstOutboxRepository {

    suspend fun listProcessable(limit: Int): List<SctInstOutboxEntry>

    /**
     * Count of processable (PENDING + FAILED) rows — the outbox **backlog** (ADR-0077 / ADR-0079).
     * Backs the `openbank.outbox.backlog` gauge. Same status filter as [listProcessable]: SENT rows
     * are excluded.
     */
    suspend fun countProcessable(): Long

    suspend fun markSent(eventId: UUID, sentAt: Instant = Instant.EPOCH)

    suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant = Instant.EPOCH)
}

/** Outbound port the dispatcher uses to push a serialized outbox payload onto the transport. */
interface SctInstOutboxEventPublisher {

    suspend fun publish(payload: String)
}
