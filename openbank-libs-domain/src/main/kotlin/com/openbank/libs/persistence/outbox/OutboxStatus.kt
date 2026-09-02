// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import java.time.Instant
import java.util.UUID

/**
 * Lifecycle of an outbox row (ADR-0050).
 *
 * - [PENDING] — written in the same transaction as the state change, not yet relayed.
 * - [DISPATCHING] — claimed by one dispatcher instance ([OutboxRepository.claimProcessable],
 *   #1201) and in flight to the broker. Not a durable end state: a repository that implements
 *   claim semantics reclaims rows stuck here past its stale-claim window (the claiming pod
 *   crashed or was evicted between claim and `markSent`/`markFailed`) instead of leaving them
 *   stranded. Repositories still on the [OutboxRepository.claimProcessable] default never
 *   produce this status.
 * - [SENT] — successfully relayed to the broker.
 * - [FAILED] — a relay attempt failed; the row stays eligible for re-dispatch.
 * - [DEAD] — terminal. The row exhausted [OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS]
 *   publish attempts and is parked so a poison row can neither be retried forever nor
 *   starve the batch (ADR-0050 N5). Excluded from `listProcessable`.
 */
enum class OutboxStatus { PENDING, DISPATCHING, SENT, FAILED, DEAD }

data class OutboxMessage(
    val eventId: UUID = UUID.randomUUID(),
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
    /**
     * When the row was created. `Instant.now()` and NOT `Instant.EPOCH`: every service's
     * `toEntity()` assigns this over the column's `DEFAULT now()`, so an omitted value used to
     * stamp the row 1970 with nothing raising an error — 388 of ledger's 553 outbox rows (#3272).
     *
     * That is not cosmetic. The dispatcher claims work with
     * `ORDER BY created_at ASC ... FOR UPDATE SKIP LOCKED`, so an epoch row sorts ahead of every
     * correctly-stamped one permanently and starves real traffic behind a 1970 backlog, while the
     * `openbank.outbox.backlog` age signal reads a fresh row as 56 years old.
     *
     * A caller that needs a fixed clock (tests, replay) still passes it explicitly.
     */
    val createdAt: Instant = Instant.now(),
    /**
     * Durable origin marker for activity produced by a bank-owned synthetic customer.
     *
     * An outbox dispatcher runs after the business transaction, often on another worker, so it
     * must not infer this from request MDC or OpenTelemetry baggage. The persisted row is the
     * hand-off boundary; [OutboxKafkaHeaders] reconstructs the transport header from this value.
     */
    val synthetic: Boolean = false,
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
    val synthetic: Boolean = false,
)
