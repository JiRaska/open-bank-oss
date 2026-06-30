// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import java.time.Instant
import java.util.UUID

interface OutboxRepository {
    /** PENDING + FAILED rows, oldest first. DEAD (N5) and SENT rows are excluded. */
    suspend fun listProcessable(limit: Int): List<OutboxEntry>

    /**
     * Count of processable (PENDING + FAILED) rows — the outbox **backlog**, the single most
     * important operational signal (ADR-0077 / ADR-0079). Backs the `openbank.outbox.backlog`
     * gauge via [com.openbank.libs.observability.DomainMetrics.registerOutboxBacklog]. DEAD (N5)
     * and SENT rows are excluded, matching [listProcessable]'s status filter.
     *
     * The default counts by materialising [listProcessable]; that is correct but O(backlog) in
     * rows loaded, so every concrete repository **should** override it with a `SELECT count(*)
     * WHERE status IN ('PENDING','FAILED')` for an O(1) read on a hot outbox.
     */
    suspend fun countProcessable(): Long = listProcessable(Int.MAX_VALUE).size.toLong()

    suspend fun markSent(eventId: UUID, sentAt: Instant = Instant.EPOCH)

    /**
     * Record a failed publish: increment the attempt counter, store the (truncated) error,
     * and apply [OutboxFailurePolicy] so an exhausted row transitions to terminal
     * [OutboxStatus.DEAD] instead of being retried forever (ADR-0050 N5).
     */
    suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant = Instant.EPOCH)
}

interface OutboxEventPublisher {
    /**
     * Relay one outbox row to the broker. The full [OutboxEntry] — not just its payload — is
     * passed so the transport can set the partition key (= aggregateId, ADR-0050 N2) and the
     * `ce-id` / `idempotency-key` / `ce-type` headers (ADR-0003 / ADR-0050 N3). See
     * [OutboxKafkaHeaders] for the canonical addressing.
     */
    suspend fun publish(entry: OutboxEntry)
}
