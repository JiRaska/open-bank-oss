// SPDX-License-Identifier: Apache-2.0

package com.openbank.swift.infrastructure.persistence.repository

import com.openbank.swift.application.port.out.RedriveBackoff
import com.openbank.swift.application.port.out.RescheduleOutcome
import com.openbank.swift.application.port.out.SwiftSettlementRedrivePort
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

/**
 * Native SQL over the V12 re-drive columns. They are deliberately NOT mapped on `SwiftMessageEntity`: a
 * generic merge of the aggregate would otherwise write a stale attempt count or clear a live claim.
 *
 * [claim] is ONE conditional UPDATE. PostgreSQL serialises concurrent UPDATEs of the same row and re-evaluates
 * the WHERE clause against the committed winner (READ COMMITTED), so the loser sees a live lease and updates
 * zero rows. No lock is held across the transaction-service call that follows.
 */
@ApplicationScoped
class SwiftSettlementRedriveRepository : SwiftSettlementRedrivePort {

    override suspend fun dueIds(now: Instant, dueBefore: Instant, limit: Int): List<UUID> = Panache.withSession {
        Panache.getSession().chain { session ->
            session.createNativeQuery<Any>(DUE_SQL)
                .setParameter("now", now)
                .setParameter("dueBefore", dueBefore)
                .setParameter("limit", limit.coerceAtLeast(1))
                .resultList
        }
    }.awaitSuspending().map { it as? UUID ?: UUID.fromString(it.toString()) }

    override suspend fun claim(id: UUID, token: UUID, now: Instant, dueBefore: Instant, leaseUntil: Instant): Boolean =
        Panache.withTransaction {
            Panache.getSession().chain { session ->
                session.createNativeMutationQuery(CLAIM_SQL)
                    .setParameter("id", id)
                    .setParameter("token", token)
                    .setParameter("now", now)
                    .setParameter("dueBefore", dueBefore)
                    .setParameter("leaseUntil", leaseUntil)
                    .executeUpdate()
            }
        }.awaitSuspending() == 1

    override suspend fun release(id: UUID, token: UUID) {
        Panache.withTransaction {
            Panache.getSession().chain { session ->
                session.createNativeMutationQuery(RELEASE_SQL)
                    .setParameter("id", id)
                    .setParameter("token", token)
                    .executeUpdate()
            }
        }.awaitSuspending()
    }

    override suspend fun reschedule(id: UUID, token: UUID, now: Instant, backoff: RedriveBackoff): RescheduleOutcome? =
        Panache.withTransaction {
            Panache.getSession().chain { session ->
                session.createNativeQuery<Any>(HELD_ATTEMPTS_SQL)
                    .setParameter("id", id)
                    .setParameter("token", token)
                    .resultList
                    .chain { rows ->
                        val held = rows.firstOrNull()
                            ?: return@chain Uni.createFrom().item(null as RescheduleOutcome?)
                        val attempts = (held as Number).toInt() + 1
                        val exhausted = attempts >= backoff.maxAttempts
                        val nextAt = if (exhausted) null else now.plus(backoff.delayAfter(attempts))
                        session.createNativeMutationQuery(RESCHEDULE_SQL)
                            .setParameter("id", id)
                            .setParameter("token", token)
                            .setParameter("attempts", attempts)
                            .setParameter("nextAt", nextAt)
                            .setParameter("exhaustedAt", if (exhausted) now else null)
                            .executeUpdate()
                            .map { updated ->
                                if (updated == 1) RescheduleOutcome(attempts, nextAt, exhausted) else null
                            }
                    }
            }
        }.awaitSuspending()

    private companion object {
        /** The booking clock is open on a SENT MT103 that is neither exhausted, backing off nor held. */
        const val ELIGIBLE = "message_type = 'MT103' AND status = 'SENT' " +
            "AND booking_first_due_at IS NOT NULL AND booking_first_due_at <= :dueBefore " +
            "AND booking_proven_at IS NULL AND booking_redrive_exhausted_at IS NULL " +
            "AND (booking_redrive_next_at IS NULL OR booking_redrive_next_at <= :now) " +
            "AND (booking_redrive_claimed_until IS NULL OR booking_redrive_claimed_until < :now)"

        const val DUE_SQL = "SELECT id FROM swift_messages WHERE $ELIGIBLE ORDER BY booking_first_due_at LIMIT :limit"

        const val CLAIM_SQL = "UPDATE swift_messages SET booking_redrive_claim = :token, " +
            "booking_redrive_claimed_until = :leaseUntil WHERE id = :id AND $ELIGIBLE"

        const val RELEASE_SQL = "UPDATE swift_messages SET booking_redrive_claim = NULL, " +
            "booking_redrive_claimed_until = NULL WHERE id = :id AND booking_redrive_claim = :token"

        const val HELD_ATTEMPTS_SQL = "SELECT booking_redrive_attempts FROM swift_messages " +
            "WHERE id = :id AND booking_redrive_claim = :token FOR UPDATE"

        const val RESCHEDULE_SQL = "UPDATE swift_messages SET booking_redrive_attempts = :attempts, " +
            "booking_redrive_next_at = :nextAt, booking_redrive_exhausted_at = :exhaustedAt, " +
            "booking_redrive_claim = NULL, booking_redrive_claimed_until = NULL " +
            "WHERE id = :id AND booking_redrive_claim = :token"
    }
}
