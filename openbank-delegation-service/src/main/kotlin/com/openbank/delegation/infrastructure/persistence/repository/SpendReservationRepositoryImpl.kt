// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.DelegationOutboxRepository
import com.openbank.delegation.application.port.out.ReserveOutcome
import com.openbank.delegation.application.port.out.SpendReservationRepository
import com.openbank.delegation.domain.event.DelegationFirstUsed
import com.openbank.delegation.domain.event.DelegationSpendReservationStateChanged
import com.openbank.delegation.domain.model.CountedSpend
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.SpendDecision
import com.openbank.delegation.domain.model.SpendReservation
import com.openbank.delegation.domain.model.SpendReservationOperationType
import com.openbank.delegation.domain.model.SpendReservationState
import com.openbank.delegation.domain.model.SpendWindow
import com.openbank.delegation.infrastructure.persistence.entity.DelegationGrantEntity
import com.openbank.delegation.infrastructure.persistence.entity.SpendReservationEntity
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hibernate.reactive.mutiny.Mutiny
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * ADR-0249 D3 — where the counter's atomicity actually lives.
 *
 * **The concurrency mechanism, and why this one.** Every reserve begins by taking a row-level write
 * lock on the grant it reserves against (`SELECT id FROM delegation_grants WHERE id = $1 FOR
 * UPDATE`), inside the same transaction that then counts the window and inserts the reservation.
 * Postgres therefore serialises all reserves against ONE grant: the second transaction blocks until
 * the first commits and then counts a total that already includes it, so two reserves that would
 * jointly breach a ceiling can never both succeed.
 *
 * It is a database guarantee on purpose. A `synchronized`/`Mutex` in the JVM would look like it
 * worked in a single-replica test and protect nothing in production, where this service runs
 * several pods — the two racing reserves are usually not even in the same process.
 *
 * Rejected alternatives: `SERIALIZABLE` isolation (correct, but it turns the race into a
 * serialisation failure the caller must retry, and a retry loop on a money path is a thing to get
 * wrong rather than a thing to rely on); a unique or exclusion constraint over the reservations
 * themselves (a ceiling is a SUM over a window — no constraint can express it); an advisory lock
 * (same serialisation, but keyed on a number with no referential integrity, so a lock could be
 * taken on a grant that does not exist).
 *
 * Idempotency is the second, independent guarantee: `uq_delegation_spend_idempotency` makes a
 * repeated key a database fact rather than a read-then-write, so even a retry that somehow escapes
 * the lock cannot double-count — it violates the constraint instead.
 */
@ApplicationScoped
class SpendReservationRepositoryImpl(
    private val outboxRepository: DelegationOutboxRepository,
    private val objectMapper: ObjectMapper,
    @ConfigProperty(
        name = "openbank.delegation.spend-reservation-state-events-enabled",
        defaultValue = "false",
    )
    private val stateEventsEnabled: Boolean,
) : SpendReservationRepository,
    PanacheRepository<SpendReservationEntity> {

    override suspend fun reserve(
        candidate: SpendReservation,
        window: SpendWindow,
        decide: (DelegationGrant, CountedSpend) -> SpendDecision,
    ): ReserveOutcome = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            lockGrant(session, candidate.grantId).flatMap { lockedGrant ->
                checkNotNull(lockedGrant) { "delegation ${candidate.grantId} disappeared before reserve" }
                findByIdempotencyKey(candidate.grantId, candidate.idempotencyKey).flatMap { existing ->
                    if (existing != null) {
                        val replay = existing.toDomain()
                        val outcome: ReserveOutcome = if (sameSpend(replay, candidate)) {
                            ReserveOutcome.Replayed(replay)
                        } else {
                            ReserveOutcome.IdempotencyConflict
                        }
                        Uni.createFrom().item(outcome)
                    } else if (
                        candidate.operationType == SpendReservationOperationType.DOMESTIC_PAYMENT &&
                        !stateEventsEnabled
                    ) {
                        Uni.createFrom().item(ReserveOutcome.StateStreamUnavailable)
                    } else {
                        countAndInsert(session, lockedGrant.toDomain(), candidate, window, decide)
                    }
                }
            }
        }
    }.awaitSuspending()

    override suspend fun findById(grantId: UUID, reservationId: UUID): SpendReservation? = Panache.withSession {
        find("id = ?1 and grantId = ?2", reservationId, grantId).firstResult<SpendReservationEntity>()
    }.awaitSuspending()?.toDomain()

    /**
     * Compare-and-set on the current state, so a confirm and a release racing on one reservation
     * cannot both land: exactly one UPDATE matches `state = 'RESERVED'`, the other counts zero rows
     * and the caller is told what the row actually became.
     *
     * The returned aggregate is DERIVED from the pre-update row rather than re-read: a bulk JPQL
     * update bypasses the persistence context, so a `find` in the same session may legitimately
     * answer with the stale snapshot. The two fields the update changes are the two this knows.
     */
    override suspend fun settle(
        grantId: UUID,
        reservationId: UUID,
        target: SpendReservationState,
        settledAt: OffsetDateTime,
    ): SpendReservation? = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            lockGrant(session, grantId).flatMap { lockedGrant ->
                if (lockedGrant == null) {
                    Uni.createFrom().nullItem()
                } else {
                    find("id = ?1 and grantId = ?2", reservationId, grantId)
                        .firstResult<SpendReservationEntity>()
                        .flatMap { before ->
                            if (before == null) {
                                Uni.createFrom().nullItem()
                            } else {
                                update(
                                    "state = ?1, settledAt = ?2 where id = ?3 and grantId = ?4 and state = ?5",
                                    target,
                                    settledAt,
                                    reservationId,
                                    grantId,
                                    SpendReservationState.RESERVED,
                                ).flatMap { count ->
                                    if (count > 0L) {
                                        val settled = before.toDomain().copy(state = target, settledAt = settledAt)
                                        appendStateEvent(settled, lockedGrant.toDomain()).replaceWith(settled)
                                    } else {
                                        Uni.createFrom().nullItem()
                                    }
                                }
                            }
                        }
                }
            }
        }
    }.awaitSuspending()

    private fun lockGrant(session: Mutiny.Session, grantId: UUID): Uni<DelegationGrantEntity?> = session
        .createQuery("FROM DelegationGrantEntity WHERE id = :grantId", DelegationGrantEntity::class.java)
        .setParameter("grantId", grantId)
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .singleResultOrNull

    private fun findByIdempotencyKey(grantId: UUID, key: String): Uni<SpendReservationEntity?> =
        find("grantId = ?1 and idempotencyKey = ?2", grantId, key).firstResult()

    private fun countAndInsert(
        session: Mutiny.Session,
        lockedGrant: DelegationGrant,
        candidate: SpendReservation,
        window: SpendWindow,
        decide: (DelegationGrant, CountedSpend) -> SpendDecision,
    ): Uni<ReserveOutcome> = countedSpend(session, candidate, window).flatMap { counted ->
        when (val decision = decide(lockedGrant, counted)) {
            is SpendDecision.Refused -> Uni.createFrom().item(ReserveOutcome.Refused(decision) as ReserveOutcome)

            SpendDecision.Allowed -> appendFirstUseEvent(session, candidate, lockedGrant)
                .flatMap { session.persist(SpendReservationEntity.fromDomain(candidate)) }
                .flatMap { appendStateEvent(candidate, lockedGrant) }
                .replaceWith(ReserveOutcome.Created(candidate) as ReserveOutcome)
        }
    }

    /**
     * ADR-0249 D4 — announce the grantee's FIRST use of this authority to the grantor (#5728).
     *
     * The count runs inside the transaction that already holds `FOR UPDATE` on the grant, so it is
     * serialised against every other reserve on the same grant: the second of two racing reserves
     * blocks, then counts the first one's row and emits nothing. It is deliberately counted BEFORE
     * the candidate is persisted — after the insert the answer would always be at least one.
     *
     * Every operation type counts, and the domestic state-stream flag is not consulted: this is a
     * customer-facing disclosure about the authority, not part of the compacted reservation stream.
     * A refusal and an idempotent replay both reach this method not at all — the first returns
     * before `countAndInsert`, the second never leaves the idempotency branch — which is what makes
     * "exactly one per grant" a property of the transaction rather than of a downstream filter.
     */
    private fun appendFirstUseEvent(
        session: Mutiny.Session,
        candidate: SpendReservation,
        grant: DelegationGrant,
    ): Uni<Void> = session
        .createQuery(FIRST_USE_COUNT_HQL, Long::class.javaObjectType)
        .setParameter("grantId", candidate.grantId)
        .singleResult
        .flatMap { priorReservations ->
            if (priorReservations.toLong() > 0L) {
                Uni.createFrom().voidItem()
            } else {
                val event = DelegationFirstUsed.from(candidate, grant)
                outboxRepository.persistInTransaction(
                    OutboxMessage(
                        eventId = event.eventId,
                        aggregateId = event.aggregateId,
                        eventType = event.eventType,
                        payload = objectMapper.writeValueAsString(event),
                        createdAt = event.occurredAt,
                    ),
                )
            }
        }

    private fun appendStateEvent(reservation: SpendReservation, grant: DelegationGrant): Uni<Void> {
        if (reservation.operationType != SpendReservationOperationType.DOMESTIC_PAYMENT) {
            return Uni.createFrom().voidItem()
        }
        val event = DelegationSpendReservationStateChanged.from(reservation, grant)
        return outboxRepository.persistInTransaction(
            OutboxMessage(
                eventId = event.eventId,
                aggregateId = event.aggregateId,
                eventType = event.eventType,
                payload = objectMapper.writeValueAsString(event),
                createdAt = event.occurredAt,
            ),
        )
    }

    /**
     * RESERVED and CONFIRMED count, RELEASED does not, and only rows in the SAME currency as the
     * amount being reserved — a ceiling is denominated, and summing across currencies would invent
     * a total nobody agreed to. `created_at` is the window anchor: a reservation belongs to the day
     * it took the headroom, not to the day it was later settled.
     */
    private fun countedSpend(
        session: Mutiny.Session,
        candidate: SpendReservation,
        window: SpendWindow,
    ): Uni<CountedSpend> {
        val currency = candidate.amount.currency.code
        return sumSince(session, candidate.grantId, currency, window.dayStart).flatMap { daily ->
            sumSince(session, candidate.grantId, currency, window.monthStart).map { monthly ->
                CountedSpend(
                    withinDay = SpendReservationEntity.toMoney(daily, currency),
                    withinMonth = SpendReservationEntity.toMoney(monthly, currency),
                )
            }
        }
    }

    private fun sumSince(
        session: Mutiny.Session,
        grantId: UUID,
        currency: String,
        from: OffsetDateTime,
    ): Uni<BigDecimal> = session
        .createNativeQuery(SUM_SQL, BigDecimal::class.java)
        .setParameter("grantId", grantId)
        .setParameter("currency", currency)
        .setParameter("from", from)
        .resultList
        .map { rows -> rows.firstOrNull() ?: BigDecimal.ZERO }

    private companion object {
        /** Any reservation ever taken on this grant — no window, no currency, no state filter. */
        const val FIRST_USE_COUNT_HQL =
            "SELECT count(r) FROM SpendReservationEntity r WHERE r.grantId = :grantId"

        const val SUM_SQL = """
            select coalesce(sum(amount), 0)
              from delegation_spend_reservations
             where grant_id = :grantId
               and currency = :currency
               and state in ('RESERVED', 'CONFIRMED')
               and created_at >= :from
        """
    }
}

private fun sameSpend(existing: SpendReservation, candidate: SpendReservation): Boolean =
    existing.amount.currency == candidate.amount.currency &&
        existing.amount.amount.compareTo(candidate.amount.amount) == 0 &&
        existing.operationType == candidate.operationType
