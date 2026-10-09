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
     *
     * @return the status this row was actually persisted with ([OutboxStatus.FAILED] or
     * [OutboxStatus.DEAD]) — the caller (see `OutboxDispatch.dispatchOnce`) uses this to attribute
     * `DomainMetrics.outboxDead` correctly instead of independently recomputing
     * [OutboxFailurePolicy.statusAfterFailure] and hoping it agrees with what this call actually
     * wrote (#5128 finding 3). Every implementation must return the status it persisted, not a
     * value predicted before the write.
     */
    suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant = Instant.now()): OutboxStatus
}

/**
 * The kernel-owned outbox repository contract (ADR-0327 D1/D3/D6/D8/D10). Implemented by
 * `openbank-libs-runtime`'s `AbstractPanacheOutboxRepository`; a hand-rolled repository may also
 * implement it, but then it takes on every guarantee below.
 *
 * **The batch contract that makes concurrent sends legal (D6):** every list [claimProcessable]
 * returns holds **at most one row per `aggregateId`**, and that row is the aggregate's head — no
 * older unsent row of the same aggregate exists, and none of it is freshly DISPATCHING on any
 * replica (D3's anti-join claim). [OutboxDispatch] relies on exactly that when it publishes a
 * claimed batch with bounded concurrency: rows of *different* aggregates are independent (Kafka
 * keys by aggregate, ADR-0050 N2), so nothing in a batch can be reordered against anything else
 * in the same batch. A repository that cannot honour the one-row-per-aggregate rule must NOT
 * implement this interface — the v1 [OutboxRepository] path stays strictly sequential.
 *
 * Guarantee, stated precisely: at-least-once delivery; for one aggregate, events reach the
 * broker in `(created_at, id)` order, under any number of replicas; across aggregates no order
 * is promised.
 */
interface OutboxRepositoryV2 :
    OutboxRepository,
    SentOutboxRetention {
    /**
     * One `UPDATE … WHERE event_id IN (…)` for every row in [eventIds] (D6): status SENT,
     * `sent_at = updated_at = sentAt`, `attempt_count + 1`, `last_error` cleared. Transactions per
     * batch: one, not one per row. An empty [eventIds] is a no-op.
     */
    suspend fun markSentBatch(eventIds: Collection<UUID>, sentAt: Instant = Instant.now())

    /**
     * Age of the oldest row that is eligible for dispatch **right now** (PENDING, FAILED past its
     * `next_attempt_at`, or stale DISPATCHING), measured from its `created_at` to [now]; `null`
     * when nothing is eligible. Backs `openbank_outbox_oldest_age_seconds` (D10) — the gauge that
     * makes a backlog of 99 three-day-old rows visible where `openbank_outbox_backlog > 100` is
     * not. A cold pod with an empty table reads 0, never a 1970-derived age (the ADR-0237 lesson).
     */
    suspend fun oldestProcessableAge(now: Instant = Instant.now()): Duration?

    /** Delete up to [batch] DEAD rows whose `updated_at` is older than [olderThan] (D8, 30 d). */
    suspend fun purgeDead(olderThan: Duration, batch: Int, now: Instant = Instant.now()): Int
}

/**
 * Retention of SENT outbox rows (ADR-0327 D8, extended to v1 outboxes by ADR-0329).
 *
 * A SENT row has done its job — the broker holds the event and audit-service keeps the durable
 * record — but its `payload` still carries whatever the event carried, often personal data
 * (IBANs, names, device decisions). Without a purge it outlives every retention period the
 * service's own tables declare. The shared `OutboxSentRetentionJob` (openbank-libs-runtime)
 * discovers every CDI bean of this type and purges it nightly, so an outbox opts IN by
 * implementing this interface — and `check-outbox-sent-retention.py` fails the build for an
 * outbox-bearing module that does not.
 *
 * Implemented by every [OutboxRepositoryV2] (the kernel base); a v1 repository implements it
 * by delegation: `SentOutboxRetention by PanacheOutboxRetention(OutboxTableShape("x_outbox"))`.
 */
interface SentOutboxRetention {
    /**
     * True only when SENT rows still back a live read or replay invariant. The shared job skips
     * this target entirely; the enforced outbox-retention gate requires a reasoned exemption.
     * Remove the exemption after the evidence moves to a durable store.
     */
    val sentRetentionExempt: Boolean
        get() = false

    /**
     * Delete up to [batch] SENT rows whose `sent_at` is older than [olderThan] (D8); returns the
     * number deleted so the caller can loop until short. Never touches PENDING/FAILED/DISPATCHING
     * rows, and never DEAD rows — those are the producer-side DLQ (D4) until an operator requeues
     * them, and they have their own longer window.
     */
    suspend fun purgeSent(olderThan: Duration, batch: Int, now: Instant = Instant.now()): Int

    /**
     * `service` label for the retention metrics. The default derives it from the class name —
     * `ScaOutboxRepositoryImpl` becomes `sca` — after stripping an Arc-generated suffix
     * (`_Subclass`, `_ClientProxy`; the #5143 lesson). Override where that disagrees with the
     * service's other outbox metrics.
     */
    val retentionLabel: String
        get() = OutboxRetention.deriveLabel(this::class.java.simpleName)
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
