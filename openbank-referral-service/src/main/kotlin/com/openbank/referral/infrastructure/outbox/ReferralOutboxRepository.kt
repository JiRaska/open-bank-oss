// SPDX-License-Identifier: Apache-2.0
package com.openbank.referral.infrastructure.outbox

import com.openbank.libs.persistence.outbox.OutboxEntry
import com.openbank.libs.persistence.outbox.OutboxFailurePolicy
import com.openbank.libs.persistence.outbox.OutboxRepository
import com.openbank.libs.persistence.outbox.OutboxStatus
import com.openbank.referral.infrastructure.persistence.ReferralOutboxEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class ReferralOutboxRepository(private val clock: Clock) :
    OutboxRepository,
    PanacheRepository<ReferralOutboxEntity> {
    override suspend fun listProcessable(limit: Int): List<OutboxEntry> = Panache.withSession {
        find("status in (?1, ?2) order by createdAt", OutboxStatus.PENDING.name, OutboxStatus.FAILED.name)
            .range(0, limit.coerceAtLeast(1) - 1).list()
    }.map { rows -> rows.map { it.toEntry() } }.awaitSuspending()

    override suspend fun countProcessable(): Long = Panache.withSession {
        count("status in (?1, ?2)", OutboxStatus.PENDING.name, OutboxStatus.FAILED.name)
    }.awaitSuspending()

    override suspend fun claimProcessable(limit: Int, staleAfter: Duration): List<OutboxEntry> {
        val now = Instant.now(clock)
        return Panache.withTransaction {
            Panache.getSession().chain { session ->
                session.createNativeQuery(CLAIM_SQL, ReferralOutboxEntity::class.java)
                    .setParameter("pending", OutboxStatus.PENDING.name)
                    .setParameter("failed", OutboxStatus.FAILED.name)
                    .setParameter("dispatching", OutboxStatus.DISPATCHING.name)
                    .setParameter("stale", now.minus(staleAfter))
                    .setParameter("limit", limit.coerceAtLeast(1))
                    .setParameter("now", now)
                    .resultList
            }
        }.map { rows -> rows.map { it.toEntry() } }.awaitSuspending()
    }

    override suspend fun markSent(eventId: UUID, sentAt: Instant) {
        Panache.withTransaction {
            find("eventId", eventId).firstResult().invoke { row ->
                row?.apply {
                    status = OutboxStatus.SENT.name
                    attemptCount += 1
                    this.sentAt = sentAt
                    lastError = null
                    updatedAt = sentAt
                }
            }.replaceWith(Unit)
        }.awaitSuspending()
    }

    override suspend fun markFailed(eventId: UUID, error: String, failedAt: Instant): OutboxStatus =
        Panache.withTransaction {
            find("eventId", eventId).firstResult().map { row ->
                if (row == null) return@map OutboxStatus.FAILED
                row.attemptCount += 1
                row.lastError = error.take(OutboxFailurePolicy.MAX_ERROR_LEN)
                row.updatedAt = failedAt
                OutboxFailurePolicy.statusAfterFailure(row.attemptCount).also { row.status = it.name }
            }
        }.awaitSuspending()

    companion object {
        @Suppress("MaxLineLength")
        private const val CLAIM_SQL = """
            UPDATE referral_outbox SET status=:dispatching, claimed_at=:now, updated_at=:now
            WHERE id IN (SELECT id FROM referral_outbox
              WHERE status IN (:pending,:failed) OR (status=:dispatching AND claimed_at < :stale)
              ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED) RETURNING *
        """
    }
}
