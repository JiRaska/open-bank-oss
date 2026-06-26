// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.security.application.port.out

import java.time.Instant
import java.util.UUID

/** Lifecycle of a transactional-outbox row as it is drained to Kafka. */
enum class SecurityOutboxStatus { PENDING, SENT, FAILED }

/**
 * A domain event to be written into the outbox in the same transaction as the aggregate change.
 * [createdAt] is assigned at construction so callers only supply the payload identity.
 */
data class SecurityOutboxMessage(
    val eventId: UUID,
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
    val createdAt: Instant = Instant.EPOCH
)

/** A persisted outbox row as read back by the dispatcher. */
data class SecurityOutboxEntry(
    val eventId: UUID,
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
    val status: SecurityOutboxStatus,
    val attemptCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val sentAt: Instant?,
    val lastError: String?
)

/** Outbound port for publishing a serialized outbox payload to the transport (Kafka). */
interface SecurityOutboxEventPublisher {
    suspend fun publish(payload: String)
}

/** Outbound port for draining the transactional outbox (read pending, mark sent/failed). */
interface SecurityOutboxRepository {

    suspend fun listProcessable(limit: Int): List<SecurityOutboxEntry>

    /**
     * Count of processable (PENDING + FAILED) rows — the outbox **backlog** (ADR-0077 / ADR-0079).
     * Backs the `openbank.outbox.backlog` gauge. Same status filter as [listProcessable]: SENT rows
     * are excluded.
     */
    suspend fun countProcessable(): Long

    suspend fun markSent(eventId: UUID, sentAt: Instant = Instant.EPOCH)

    suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant = Instant.EPOCH)
}
