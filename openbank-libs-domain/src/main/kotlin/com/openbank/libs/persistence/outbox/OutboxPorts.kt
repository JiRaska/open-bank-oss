// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import java.time.Duration
import java.time.Instant
import java.util.UUID

interface OutboxRepository {
    /** PENDING + FAILED rows, oldest first. DEAD (N5) and SENT rows are excluded. */
    suspend fun listProcessable(limit: Int): List<OutboxEntry>

    /**
     * Atomically claim up to [limit] processable rows for **this** dispatcher instance,
     * transitioning them to [OutboxStatus.DISPATCHING] so a concurrently running instance
     * cannot select the same rows (#1201). This matters whenever more than one pod can run the
     * dispatch loop at once — including under a steady-state `replicas: 1` deployment, since an
     * Argo Rollouts canary window runs the old and new pod simultaneously for the duration of the
     * rollout, and **both** run every `@Scheduled` bean regardless of traffic-weight split.
     *
     * Also reclaims rows still [OutboxStatus.DISPATCHING] after [staleAfter] — the claiming pod
     * crashed or was evicted between claiming the row and calling `markSent`/`markFailed` — so a
     * claim can never strand a row forever.
     *
     * The default delegates to [listProcessable]: an **unclaimed peek**, safe only when the
     * caller can guarantee a single dispatcher instance is ever running (no concurrent-claim
     * protection). Override with an atomic `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP
     * LOCKED)` claim wherever that guarantee doesn't hold — see
     * `LedgerOutboxRepositoryImpl.claimProcessable` for the reference implementation. Rolling
     * this out to the rest of the outbox-bearing fleet is tracked as follow-up scope on #1201.
     */
    suspend fun claimProcessable(limit: Int, staleAfter: Duration = Duration.ofMinutes(2)): List<OutboxEntry> =
        listProcessable(limit)

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

    /**
     * `Instant.now()` and NOT `Instant.EPOCH` — the shared [OutboxDispatch] calls this with no
     * timestamp, so the default is what every dispatched row in the fleet actually got. Repository
     * implementations assign it to BOTH `sent_at` and `updated_at`, so an epoch default stamped
     * both 1970 (#3272, same family as the `createdAt` default).
     */
    suspend fun markSent(eventId: UUID, sentAt: Instant = Instant.now())

    /**
     * Record a failed publish: increment the attempt counter, store the (truncated) error,
     * and apply [OutboxFailurePolicy] so an exhausted row transitions to terminal
     * [OutboxStatus.DEAD] instead of being retried forever (ADR-0050 N5).
     *
     * `failedAt` defaults to `Instant.now()` for the same reason as [markSent]: [OutboxDispatch]
     * passes none, and it lands in `updated_at` — which the dead-letter janitor prunes on
     * (`status = DEAD and updatedAt < threshold`). At 1970 every DEAD row is instantly older than
     * any retention window (#3272).
     */
    suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant = Instant.now())
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
