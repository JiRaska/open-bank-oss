// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.libs.persistence.outbox

import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import org.hibernate.reactive.mutiny.Mutiny
import org.jboss.logging.Logger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * The kernel-owned outbox repository (ADR-0327 D1): the whole [OutboxRepositoryV2] port —
 * indexed claim-by-aggregate-head (D3), backoff on failure (D4), batched `markSent` (D6), O(1)
 * count, oldest-row age (D10) and batched retention (D8) — parameterised by the table
 * ([OutboxTableShape]) and the entity class. A service repository becomes
 *
 * ```
 * @ApplicationScoped
 * class LedgerOutboxRepositoryImpl(clock: Clock) :
 *     AbstractPanacheOutboxRepository<LedgerOutboxEntity>(OutboxTableShape("ledger_outbox"), LedgerOutboxEntity::class.java, clock),
 *     LedgerOutboxRepository,
 *     PanacheRepository<LedgerOutboxEntity> {
 *     fun persistInTransaction(message: OutboxMessage) = persist(message.toEntity())
 * }
 * ```
 *
 * plus its own `persistInTransaction`. Every column this class reads or writes it reaches through
 * native SQL ([OutboxSql]), never through a mapped property — that is what lets it serve an entity
 * on either [PanacheOutboxEntity] (v1, own `claimedAt`) or [PanacheOutboxEntityV2], provided the
 * TABLE carries `claimed_at`, `next_attempt_at` and the two partial indexes from
 * `db/outbox-v2-template.sql`. `RETURNING *` maps only the columns the entity declares; the rest
 * are ignored by Hibernate.
 *
 * Reactive surface, event-loop only (ADR-0050 N1): every method runs its statement inside
 * `Panache.withTransaction` on the calling Vert.x context, so the `@Scheduled suspend` dispatcher
 * that drives it never bridges a session onto a worker thread.
 *
 * Not a `PanacheRepository` itself: Quarkus resolves a Panache repository's entity type from the
 * concrete class's type argument at build time, and this class needs no active-record method —
 * the concrete subclass adds `PanacheRepository<E>` for its own `persist`/`find` if it wants them.
 */
abstract class AbstractPanacheOutboxRepository<E : PanacheOutboxEntity> : OutboxRepositoryV2 {

    protected lateinit var shape: OutboxTableShape
        private set
    protected lateinit var entityClass: Class<E>
        private set
    protected lateinit var clock: Clock
        private set
    private var random: Random = Random.Default

    constructor(shape: OutboxTableShape, entityClass: Class<E>, clock: Clock = Clock.systemUTC()) {
        this.shape = shape
        this.entityClass = entityClass
        this.clock = clock
    }

    /** Test seam: a deterministic jitter source for the D4 schedule. */
    protected constructor(
        shape: OutboxTableShape,
        entityClass: Class<E>,
        clock: Clock,
        random: Random,
    ) : this(shape, entityClass, clock) {
        this.random = random
    }

    // Required by Quarkus CDI for proxy subclass generation of the concrete @ApplicationScoped
    // bean — never called at runtime (mirrors AbstractOutboxDispatcher's second constructor).
    protected constructor()

    /** `DEFAULT_MAX_ATTEMPTS` unless a service has a measured reason to differ (ADR-0050 N5). */
    protected open val maxAttempts: Int get() = OutboxFailurePolicy.DEFAULT_MAX_ATTEMPTS

    /** Short service name for the DEAD warning line — defaults to the table minus `_outbox`. */
    protected open val serviceLabel: String get() = shape.table.removeSuffix("_outbox")

    override suspend fun listProcessable(limit: Int): List<OutboxEntry> {
        val now = Instant.now(clock)
        return inTransaction { s ->
            s.createNativeQuery(OutboxSql.listProcessable(shape), entityClass)
                .bindEligibility(now, now.minus(DEFAULT_STALE_AFTER))
                .setParameter("limit", limit.coerceAtLeast(1))
                .resultList
        }.map { rows -> rows.map { it.toEntry() } }.awaitSuspending()
    }

    override suspend fun claimProcessable(limit: Int, staleAfter: Duration): List<OutboxEntry> {
        val now = Instant.now(clock)
        return inTransaction { s ->
            s.createNativeQuery(OutboxSql.claim(shape), entityClass)
                .bindEligibility(now, now.minus(staleAfter))
                .setParameter("limit", limit.coerceAtLeast(1))
                .resultList
        }.map { rows -> rows.map { it.toEntry() } }.awaitSuspending()
    }

    override suspend fun countProcessable(): Long = inTransaction { s ->
        s.createNativeQuery(OutboxSql.countProcessable(shape), java.lang.Long::class.java)
            .setParameter("pending", OutboxStatus.PENDING.name)
            .setParameter("failed", OutboxStatus.FAILED.name)
            .singleResult
    }.map { it.toLong() }.awaitSuspending()

    override suspend fun oldestProcessableAge(now: Instant): Duration? = inTransaction { s ->
        s.createNativeQuery(OutboxSql.oldestProcessable(shape), Instant::class.java)
            .let { q ->
                q.setParameter("pending", OutboxStatus.PENDING.name)
                    .setParameter("failed", OutboxStatus.FAILED.name)
                    .setParameter("dispatching", OutboxStatus.DISPATCHING.name)
                    .setParameter("stale", now.minus(DEFAULT_STALE_AFTER))
                    .setParameter("now", now)
            }
            .singleResultOrNull
    }.map { oldest -> oldest?.let { Duration.between(it, now).coerceAtLeast(Duration.ZERO) } }.awaitSuspending()

    override suspend fun markSent(eventId: UUID, sentAt: Instant) = markSentBatch(listOf(eventId), sentAt)

    override suspend fun markSentBatch(eventIds: Collection<UUID>, sentAt: Instant) {
        if (eventIds.isEmpty()) return
        inTransaction<Int> { s ->
            s.createNativeQuery<Int>(OutboxSql.markSentBatch(shape))
                .setParameter("sent", OutboxStatus.SENT.name)
                .setParameter("now", sentAt)
                .setParameter("ids", eventIds.toList())
                .executeUpdate()
        }.awaitSuspending()
    }

    /**
     * D4: lock the row, read its attempt count, decide FAILED-with-schedule or DEAD in Kotlin
     * ([OutboxFailurePolicy] + [OutboxBackoff], one source of truth for both), write it back.
     * Returns the status actually persisted, as the port requires (#5128 finding 3).
     */
    override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant): OutboxStatus =
        inTransaction<OutboxStatus> { s ->
            s.createNativeQuery(OutboxSql.lockForFailure(shape), java.lang.Integer::class.java)
                .setParameter("eventId", eventId)
                .singleResultOrNull
                .chain { current: java.lang.Integer? ->
                    if (current == null) {
                        // Unreachable in practice — the dispatcher only marks a row it just claimed —
                        // but degrade rather than throw out of a batch that is otherwise mid-flight.
                        Uni.createFrom().item(OutboxStatus.FAILED)
                    } else {
                        val attempts = current.toInt() + 1
                        val next = OutboxFailurePolicy.statusAfterFailure(attempts, maxAttempts)
                        val schedule = if (next ==
                            OutboxStatus.DEAD
                        ) {
                            null
                        } else {
                            OutboxBackoff.nextAttemptAt(attempts, failedAt, random)
                        }
                        s.createNativeQuery<Int>(OutboxSql.markFailed(shape))
                            .setParameter("status", next.name)
                            .setParameter("attempts", attempts)
                            .setParameter("error", error.take(OutboxFailurePolicy.MAX_ERROR_LEN))
                            .setParameter("now", failedAt)
                            .setParameter("nextAttemptAt", schedule)
                            .setParameter("eventId", eventId)
                            .executeUpdate()
                            .invoke { _ ->
                                if (next == OutboxStatus.DEAD) {
                                    log.warnf(
                                        "%s.outbox.dead event_id=%s attempts=%d last_error=%s",
                                        serviceLabel,
                                        eventId,
                                        attempts,
                                        error.take(LOG_ERROR_LEN),
                                    )
                                }
                            }
                            .replaceWith(next)
                    }
                }
        }.awaitSuspending()

    override suspend fun purgeSent(olderThan: Duration, batch: Int, now: Instant): Int =
        PanacheOutboxRetention.purgeSent(shape, olderThan, batch, now)

    /** Same label rule as a v1 repository's [PanacheOutboxRetention]: the table names the outbox. */
    override val retentionLabel: String get() = shape.table.removeSuffix("_outbox").replace('_', '-')

    override suspend fun purgeDead(olderThan: Duration, batch: Int, now: Instant): Int = inTransaction<Int> { s ->
        s.createNativeQuery<Int>(OutboxSql.purgeDead(shape))
            .setParameter("dead", OutboxStatus.DEAD.name)
            .setParameter("cut", now.minus(olderThan))
            .setParameter("limit", batch.coerceAtLeast(1))
            .executeUpdate()
    }.awaitSuspending()

    companion object {
        private val log: Logger = Logger.getLogger(AbstractPanacheOutboxRepository::class.java)

        /** The port's `claimProcessable` default, reused by the peek/age reads for "is this DISPATCHING row stale". */
        val DEFAULT_STALE_AFTER: Duration = Duration.ofMinutes(2)

        private const val LOG_ERROR_LEN = 200
    }
}

/** One `Panache.withTransaction` on the calling Vert.x context around [block]. */
private fun <T> inTransaction(block: (Mutiny.Session) -> Uni<T>): Uni<T> =
    Panache.withTransaction { Panache.getSession().chain { session -> block(session) } }

/** Binds the parameters of [OutboxSql.eligible]: the three in-flight statuses, `:stale` and `:now`. */
private fun <E> Mutiny.SelectionQuery<E>.bindEligibility(now: Instant, stale: Instant): Mutiny.SelectionQuery<E> =
    setParameter("pending", OutboxStatus.PENDING.name)
        .setParameter("failed", OutboxStatus.FAILED.name)
        .setParameter("dispatching", OutboxStatus.DISPATCHING.name)
        .setParameter("stale", stale)
        .setParameter("now", now)
