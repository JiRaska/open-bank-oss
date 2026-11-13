// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.persistence.repository

import com.openbank.kyb.application.port.out.MonitoringDesired
import com.openbank.kyb.application.port.out.MonitoringRegistration
import com.openbank.kyb.application.port.out.MonitoringRegistrationStore
import com.openbank.kyb.application.port.out.MonitoringStats
import com.openbank.kyb.infrastructure.persistence.entity.MonitoredSubjectEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Postgres store of the desired monitoring registrations. See [MonitoringRegistrationStore] for
 * the contract; what is specific here is how each guarantee is held:
 *
 *  - [syncInTransaction] uses the caller's session, so its rows commit with the case row;
 *  - [markSynced] and [markFailed] are conditional on `generation`, so the answer to a request
 *    that a later case write superseded changes nothing;
 *  - the read-only aggregates go through the plain reactive pool: they are counts and ages over
 *    two tables and have no business loading entities.
 */
@ApplicationScoped
@Suppress("TooManyFunctions") // one query per thing the relay and the consumer ask, plus the row mappers
class MonitoringRegistrationStoreImpl(private val pool: Pool) :
    MonitoringRegistrationStore,
    PanacheRepository<MonitoredSubjectEntity> {

    override fun syncInTransaction(caseId: UUID, desiredRefs: Set<String>, now: Instant): Uni<Void> =
        list("caseId", caseId).flatMap { rows ->
            rows.forEach { row ->
                val wanted = row.subjectRef in desiredRefs
                if (wanted != (row.desired == REGISTERED)) row.turn(if (wanted) REGISTERED else WITHDRAWN, now)
            }
            val known = rows.map { it.subjectRef }.toSet()
            val fresh = (desiredRefs - known).map { newRow(caseId, it, now) }
            if (fresh.isEmpty()) Uni.createFrom().voidItem() else persist(fresh)
        }

    private fun MonitoredSubjectEntity.turn(to: String, now: Instant) {
        desired = to
        syncState = PENDING
        generation += 1
        attemptCount = 0
        nextAttemptAt = now
        pendingSince = now
        lastError = null
        updatedAt = now
    }

    private fun newRow(case: UUID, ref: String, now: Instant) = MonitoredSubjectEntity().apply {
        caseId = case
        subjectRef = ref
        desired = REGISTERED
        syncState = PENDING
        nextAttemptAt = now
        pendingSince = now
        createdAt = now
        updatedAt = now
    }

    override suspend fun due(now: Instant, limit: Int): List<MonitoringRegistration> = Panache.withSession {
        find("syncState = ?1 and nextAttemptAt <= ?2 order by nextAttemptAt, id", PENDING, now).page(0, limit).list()
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun markSynced(registration: MonitoringRegistration, now: Instant): Boolean =
        Panache.withTransaction {
            update(
                "syncState = ?1, syncedAt = ?2, pendingSince = null, lastError = null, updatedAt = ?2 " +
                    "where id = ?3 and generation = ?4",
                SYNCED,
                now,
                registration.id,
                registration.generation,
            )
        }.awaitSuspending() > 0

    override suspend fun markFailed(
        registration: MonitoringRegistration,
        now: Instant,
        nextAttemptAt: Instant,
        reason: String,
    ) {
        Panache.withTransaction {
            update(
                "attemptCount = attemptCount + 1, nextAttemptAt = ?1, lastError = ?2, updatedAt = ?3 " +
                    "where id = ?4 and generation = ?5 and syncState = ?6",
                nextAttemptAt,
                reason.take(MAX_REASON),
                now,
                registration.id,
                registration.generation,
                PENDING,
            )
        }.awaitSuspending()
    }

    override suspend fun findBySubjectRef(subjectRef: String): List<MonitoringRegistration> = Panache.withSession {
        list("subjectRef", subjectRef)
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun wantedElsewhere(subjectRef: String, exceptCaseId: UUID): Boolean = Panache.withSession {
        count("subjectRef = ?1 and caseId <> ?2 and desired = ?3", subjectRef, exceptCaseId, REGISTERED)
    }.awaitSuspending() > 0

    override suspend fun requeueConfirmedBefore(confirmedBefore: Instant, now: Instant, limit: Int): Int =
        Panache.withTransaction {
            find(
                "desired = ?1 and syncState = ?2 and syncedAt < ?3 order by syncedAt, id",
                REGISTERED,
                SYNCED,
                confirmedBefore,
            ).page(0, limit).list().map { rows ->
                rows.forEach { row ->
                    row.syncState = PENDING
                    row.attemptCount = 0
                    row.nextAttemptAt = now
                    row.pendingSince = now
                    row.updatedAt = now
                }
                rows.size
            }
        }.awaitSuspending()

    override suspend fun casesToBackfill(limit: Int): List<UUID> =
        pool.preparedQuery(BACKFILL_SQL).execute(Tuple.of(limit)).awaitSuspending().map { it.getUUID(0) }

    override suspend fun stats(now: Instant): MonitoringStats {
        val row = pool.preparedQuery(STATS_SQL).execute(Tuple.of(now.atOffset(ZoneOffset.UTC)))
            .awaitSuspending().iterator().next()
        return MonitoringStats(
            registered = row.getLong(REGISTERED_COL),
            pending = row.getLong(PENDING_COL),
            oldestPendingAgeSeconds = row.getLong(PENDING_AGE_COL),
            openReviews = row.getLong(REVIEWS_COL),
            oldestOpenReviewAgeSeconds = row.getLong(REVIEW_AGE_COL),
            unscreenedApprovedCases = row.getLong(UNSCREENED_COL),
        )
    }

    private fun MonitoredSubjectEntity.toDomain() = MonitoringRegistration(
        id = requireNotNull(id),
        caseId = caseId,
        subjectRef = subjectRef,
        desired = MonitoringDesired.valueOf(desired),
        generation = generation,
        attemptCount = attemptCount,
    )

    private companion object {
        val REGISTERED = MonitoringDesired.REGISTERED.name
        val WITHDRAWN = MonitoringDesired.WITHDRAWN.name
        const val PENDING = "PENDING"
        const val SYNCED = "SYNCED"
        const val MAX_REASON = 64

        const val REGISTERED_COL = 0
        const val PENDING_COL = 1
        const val PENDING_AGE_COL = 2
        const val REVIEWS_COL = 3
        const val REVIEW_AGE_COL = 4
        const val UNSCREENED_COL = 5

        /** A case counts as approved exactly as `BusinessOnboardingCase.monitored` defines it. */
        private const val APPROVED =
            "(c.status IN ('SIGNED', 'ACTIVE') OR " +
                "(c.status = 'MANUAL_REVIEW' AND c.monitoring_review_opened_at IS NOT NULL))"

        /**
         * `"key":"` is in the stored document of every screened subject, so the LIKE keeps a case
         * whose screening holds nobody out of the list instead of returning it on every run.
         */
        const val BACKFILL_SQL =
            "SELECT c.case_id FROM kyb_cases c WHERE $APPROVED " +
                "AND c.screening_json LIKE '%\"key\":\"%' " +
                "AND NOT EXISTS (SELECT 1 FROM kyb_monitored_subjects m WHERE m.case_id = c.case_id) " +
                "ORDER BY c.id LIMIT $1"

        const val STATS_SQL =
            "SELECT " +
                "(SELECT count(*) FROM kyb_monitored_subjects " +
                "  WHERE desired = 'REGISTERED' AND sync_state = 'SYNCED'), " +
                "(SELECT count(*) FROM kyb_monitored_subjects WHERE sync_state = 'PENDING'), " +
                "(SELECT COALESCE(EXTRACT(EPOCH FROM ($1::timestamptz - min(pending_since))), 0)::bigint " +
                "  FROM kyb_monitored_subjects WHERE sync_state = 'PENDING'), " +
                "(SELECT count(*) FROM kyb_cases c " +
                "  WHERE c.status = 'MANUAL_REVIEW' AND c.monitoring_review_opened_at IS NOT NULL), " +
                "(SELECT COALESCE(EXTRACT(EPOCH FROM " +
                "  ($1::timestamptz - min(c.monitoring_review_opened_at))), 0)::bigint " +
                "  FROM kyb_cases c WHERE c.status = 'MANUAL_REVIEW' AND c.monitoring_review_opened_at IS NOT NULL), " +
                "(SELECT count(*) FROM kyb_cases c WHERE c.status IN ('SIGNED', 'ACTIVE') AND c.screening_json IS NULL)"
    }
}
