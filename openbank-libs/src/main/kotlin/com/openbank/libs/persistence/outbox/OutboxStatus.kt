// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

package com.openbank.libs.persistence.outbox

import java.time.Instant
import java.util.UUID

/**
 * Lifecycle of an outbox row (ADR-0050).
 *
 * - [PENDING] — written in the same transaction as the state change, not yet relayed.
 * - [SENT] — successfully relayed to the broker.
 * - [FAILED] — a relay attempt failed; the row stays eligible for re-dispatch.
 * - [DEAD] — terminal. The row exhausted [OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS]
 *   publish attempts and is parked so a poison row can neither be retried forever nor
 *   starve the batch (ADR-0050 N5). Excluded from `listProcessable`.
 */
enum class OutboxStatus { PENDING, SENT, FAILED, DEAD }

data class OutboxMessage(
    val eventId: UUID = UUID.randomUUID(),
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
    val createdAt: Instant = Instant.EPOCH,
)

data class OutboxEntry(
    val eventId: UUID,
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
    val status: OutboxStatus,
    val attemptCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val sentAt: Instant?,
    val lastError: String?,
)
