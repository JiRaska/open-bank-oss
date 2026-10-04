// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.cardprocessing.infrastructure.persistence.repository

import com.openbank.cardprocessing.application.port.out.IdempotencyClaim
import com.openbank.cardprocessing.application.port.out.LifecycleIdempotencyPort
import com.openbank.cardprocessing.application.port.out.LifecycleOperation
import com.openbank.cardprocessing.application.port.out.Reservation
import com.openbank.cardprocessing.infrastructure.persistence.entity.CardLifecycleIdempotencyEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Database-backed idempotency reservations for the token and dispute paths.
 *
 * Postgres rather than the fleet's Redis [IdempotencyStore][com.openbank.libs.idempotency.IdempotencyStore]
 * for two reasons that are specific to calls which reach a card network:
 *
 * 1. **Completion is atomic with the result.** The reservation is flipped to COMPLETED in the same
 *    transaction that inserts the token or case row and its outbox event, so "completed" can never
 *    point at a row that rolled back. A Redis record is a second store and cannot join that commit.
 * 2. **A PENDING reservation never self-heals.** The Redis store's in-flight marker expires after a
 *    TTL by design; here, expiring it would let a retry reach the network a second time after a crash
 *    that happened AFTER the network acted — a second wallet credential, a second chargeback.
 *
 * The reservation is an `INSERT ... ON CONFLICT DO NOTHING` under the primary key, committed on its
 * own before the network is called: exactly one concurrent request inserts the row.
 */
@ApplicationScoped
class LifecycleIdempotencyRepositoryImpl(private val clock: Clock) :
    LifecycleIdempotencyPort,
    PanacheRepository<CardLifecycleIdempotencyEntity> {

    override suspend fun reserve(operation: LifecycleOperation, key: String, fingerprint: String): Reservation {
        val reservationKey = keyOf(operation, key)
        val now = OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC)
        val inserted = Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Int>(
                    "INSERT INTO card_lifecycle_idempotency " +
                        "(reservation_key, operation, fingerprint, state, result_id, created_at, updated_at) " +
                        "VALUES (:key, :operation, :fingerprint, '$PENDING', NULL, :now, :now) " +
                        "ON CONFLICT (reservation_key) DO NOTHING",
                )
                    .setParameter("key", reservationKey)
                    .setParameter("operation", operation.name)
                    .setParameter("fingerprint", fingerprint)
                    .setParameter("now", now)
                    .executeUpdate()
            }
        }.awaitSuspending()
        if (inserted == 1) return Reservation.Claimed

        val existing = Panache.withSession { find("reservationKey", reservationKey).firstResult() }.awaitSuspending()
            // Released between our INSERT and this read: the winner was refused. Treat as in progress
            // rather than looping — the client retries and finds the key free.
            ?: return Reservation.InProgress
        return when {
            existing.fingerprint != fingerprint -> Reservation.Mismatch
            existing.state == COMPLETED -> Reservation.Completed(requireNotNull(existing.resultId))
            else -> Reservation.InProgress
        }
    }

    override suspend fun release(claim: IdempotencyClaim) {
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Int>(
                    "DELETE FROM card_lifecycle_idempotency WHERE reservation_key = :key AND state = '$PENDING'",
                ).setParameter("key", keyOf(claim.operation, claim.key)).executeUpdate()
            }
        }.awaitSuspending()
    }

    override suspend fun complete(claim: IdempotencyClaim, resultId: UUID) {
        Panache.withTransaction { completeInTransaction(claim, resultId) }.awaitSuspending()
    }

    /**
     * Completes [claim] inside the CALLER's transaction — chained by the token and dispute repositories
     * after the result row and the outbox row, so all three commit together.
     *
     * Exactly one row must flip from PENDING; anything else means the reservation was not held, and
     * the whole write fails rather than committing a result no reservation accounts for.
     */
    fun completeInTransaction(claim: IdempotencyClaim, resultId: UUID): Uni<Void> = Panache.getSession()
        .flatMap { session ->
            session.createNativeQuery<Int>(
                "UPDATE card_lifecycle_idempotency SET state = '$COMPLETED', result_id = :result, " +
                    "updated_at = :now WHERE reservation_key = :key AND state = '$PENDING'",
            )
                .setParameter("result", resultId)
                .setParameter("now", OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC))
                .setParameter("key", keyOf(claim.operation, claim.key))
                .executeUpdate()
        }
        .invoke { updated ->
            check(updated == 1) { "idempotency reservation ${claim.operation} was not held PENDING at completion" }
        }
        .replaceWithVoid()

    private fun keyOf(operation: LifecycleOperation, key: String) = "${operation.name}:$key"

    private companion object {
        const val PENDING = "PENDING"
        const val COMPLETED = "COMPLETED"
    }
}
