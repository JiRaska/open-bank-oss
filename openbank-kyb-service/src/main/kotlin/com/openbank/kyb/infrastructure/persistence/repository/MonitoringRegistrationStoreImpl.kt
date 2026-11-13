// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.persistence.repository

import com.openbank.kyb.application.port.out.MonitoringDesired
import com.openbank.kyb.application.port.out.MonitoringRegistration
import com.openbank.kyb.application.port.out.MonitoringRegistrationStore
import com.openbank.kyb.application.port.out.MonitoringStats
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import io.vertx.mutiny.sqlclient.Pool
import io.vertx.mutiny.sqlclient.Row
import io.vertx.mutiny.sqlclient.Tuple
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Postgres store of the desired monitoring registrations. See [MonitoringRegistrationStore] for
 * the contract. Plain SQL, no entity: every write here is one conditional statement, so there is
 * no loaded row whose stale copy could be written back.
 *
 *  - [syncInTransaction] runs on the caller's Hibernate session, so it commits with the case row.
 *    It is one statement whatever the case holds, and its insert is `ON CONFLICT DO NOTHING`:
 *    two writers of one case never fail on the row, they are told apart by the case's own
 *    version check.
 *  - the relay's writes are conditional on `generation`, so the answer to a request that a later
 *    case write superseded changes nothing.
 */
@ApplicationScoped
@Suppress("TooManyFunctions") // one statement per thing the relay, the consumer and the operator view ask
class MonitoringRegistrationStoreImpl(private val pool: Pool) : MonitoringRegistrationStore {

    override fun syncInTransaction(caseId: UUID, desiredRefs: Set<String>, now: Instant): Uni<Void> =
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Any>(SYNC_SQL)
                .setParameter(1, caseId)
                .setParameter(2, desiredRefs.joinToString(REF_SEPARATOR))
                .setParameter(TIME_PARAMETER, now.atOffset(ZoneOffset.UTC))
                .executeUpdate()
        }.replaceWithVoid()

    override suspend fun due(now: Instant, limit: Int): List<MonitoringRegistration> = pool.preparedQuery(
        "SELECT $ROW FROM kyb_monitored_subjects " +
            "WHERE sync_state = 'PENDING' AND next_attempt_at <= $1 ORDER BY next_attempt_at, id LIMIT $2",
    ).execute(Tuple.of(now.utc(), limit)).awaitSuspending().map(::registration)

    override suspend fun markSynced(registration: MonitoringRegistration, now: Instant): Boolean = pool.preparedQuery(
        "UPDATE kyb_monitored_subjects SET sync_state = 'SYNCED', synced_at = $1, pending_since = NULL, " +
            "last_error = NULL, updated_at = $1 WHERE id = $2 AND generation = $3",
    ).execute(Tuple.of(now.utc(), registration.id, registration.generation)).awaitSuspending().rowCount() > 0

    override suspend fun markFailed(
        registration: MonitoringRegistration,
        now: Instant,
        nextAttemptAt: Instant,
        reason: String,
    ) {
        pool.preparedQuery(
            "UPDATE kyb_monitored_subjects SET attempt_count = attempt_count + 1, next_attempt_at = $1, " +
                "last_error = $2, updated_at = $3 WHERE id = $4 AND generation = $5 AND sync_state = 'PENDING'",
        ).execute(
            Tuple.of(nextAttemptAt.utc(), reason.take(MAX_REASON), now.utc(), registration.id, registration.generation),
        ).awaitSuspending()
    }

    override suspend fun markRefused(registration: MonitoringRegistration, now: Instant, reason: String) {
        pool.preparedQuery(
            "UPDATE kyb_monitored_subjects SET sync_state = 'REFUSED', attempt_count = attempt_count + 1, " +
                "pending_since = NULL, last_error = $1, updated_at = $2 " +
                "WHERE id = $3 AND generation = $4 AND sync_state = 'PENDING'",
        ).execute(Tuple.of(reason.take(MAX_REASON), now.utc(), registration.id, registration.generation))
            .awaitSuspending()
    }

    override suspend fun findBySubjectRef(subjectRef: String): List<MonitoringRegistration> =
        pool.preparedQuery("SELECT $ROW FROM kyb_monitored_subjects WHERE subject_ref = $1")
            .execute(Tuple.of(subjectRef)).awaitSuspending().map(::registration)

    override suspend fun wantedElsewhere(subjectRef: String, exceptCaseId: UUID): Boolean = pool.preparedQuery(
        "SELECT EXISTS (SELECT 1 FROM kyb_monitored_subjects " +
            "WHERE subject_ref = $1 AND case_id <> $2 AND desired = 'REGISTERED')",
    ).execute(Tuple.of(subjectRef, exceptCaseId)).awaitSuspending().iterator().next().getBoolean(0)

    override suspend fun reassertElsewhere(subjectRef: String, exceptCaseId: UUID, now: Instant) {
        pool.preparedQuery(
            "UPDATE kyb_monitored_subjects SET $REQUEUE, generation = generation + 1 " +
                "WHERE subject_ref = $2 AND case_id <> $3 AND desired = 'REGISTERED'",
        ).execute(Tuple.of(now.utc(), subjectRef, exceptCaseId)).awaitSuspending()
    }

    override suspend fun requeueSettledBefore(before: Instant, now: Instant, limit: Int): Int = pool.preparedQuery(
        // The outer condition repeats the inner one: a row that a case write turned to
        // WITHDRAWN between the two is re-checked by the UPDATE and left alone.
        "UPDATE kyb_monitored_subjects SET $REQUEUE WHERE $SETTLED AND updated_at < $2 AND id IN " +
            "(SELECT id FROM kyb_monitored_subjects WHERE $SETTLED AND updated_at < $2 " +
            "ORDER BY updated_at, id LIMIT $3)",
    ).execute(Tuple.of(now.utc(), before.utc(), limit)).awaitSuspending().rowCount()

    override suspend fun casesToBackfill(limit: Int): List<UUID> =
        pool.preparedQuery(BACKFILL_SQL).execute(Tuple.of(limit)).awaitSuspending().map { it.getUUID(0) }

    override suspend fun statesOf(caseId: UUID): Map<String, String> = pool.preparedQuery(
        "SELECT subject_ref, sync_state FROM kyb_monitored_subjects WHERE case_id = $1 AND desired = 'REGISTERED'",
    ).execute(Tuple.of(caseId)).awaitSuspending().associate { it.getString(0) to it.getString(1) }

    override suspend fun stats(now: Instant): MonitoringStats {
        val row = pool.preparedQuery(STATS_SQL).execute(Tuple.of(now.utc())).awaitSuspending().iterator().next()
        var column = 0
        return MonitoringStats(
            registered = row.getLong(column++),
            pending = row.getLong(column++),
            oldestPendingAgeSeconds = row.getLong(column++),
            refused = row.getLong(column++),
            openReviews = row.getLong(column++),
            oldestOpenReviewAgeSeconds = row.getLong(column++),
            exitsRequired = row.getLong(column++),
            unscreenedApprovedCases = row.getLong(column),
        )
    }

    private fun registration(row: Row) = MonitoringRegistration(
        id = row.getLong("id"),
        caseId = row.getUUID("case_id"),
        subjectRef = row.getString("subject_ref"),
        desired = MonitoringDesired.valueOf(row.getString("desired")),
        generation = row.getLong("generation"),
        attemptCount = row.getInteger("attempt_count"),
    )

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private companion object {
        const val MAX_REASON = 64
        const val TIME_PARAMETER = 3

        /** A reference is a UUID or `kyb-case:<uuid>:<hex>`; neither can contain this. */
        const val REF_SEPARATOR = ","

        const val ROW = "id, case_id, subject_ref, desired, generation, attempt_count"

        /** Due now, from attempt zero; `$1` is the time. */
        const val REQUEUE =
            "sync_state = 'PENDING', attempt_count = 0, next_attempt_at = $1, pending_since = $1, updated_at = $1"

        const val SETTLED = "desired = 'REGISTERED' AND sync_state IN ('SYNCED', 'REFUSED')"

        /**
         * ?1 case id, ?2 the desired references joined by [REF_SEPARATOR], ?3 the time. The insert
         * adds what is missing and yields to a row that exists; the update turns every row whose
         * desired state disagrees with ?2. A row the insert just added is not visible to the
         * update of the same statement, and needs no turning.
         */
        const val SYNC_SQL =
            "WITH wanted AS (SELECT r AS ref FROM unnest(string_to_array(cast(?2 as text), ',')) AS r " +
                "WHERE r <> ''), " +
                "added AS (INSERT INTO kyb_monitored_subjects " +
                "(id, case_id, subject_ref, desired, sync_state, next_attempt_at, pending_since, " +
                "created_at, updated_at) " +
                "SELECT nextval('kyb_monitored_subjects_seq'), ?1, ref, 'REGISTERED', 'PENDING', ?3, ?3, ?3, ?3 " +
                "FROM wanted ON CONFLICT (case_id, subject_ref) DO NOTHING) " +
                "UPDATE kyb_monitored_subjects m SET " +
                "desired = CASE WHEN m.desired = 'REGISTERED' THEN 'WITHDRAWN' ELSE 'REGISTERED' END, " +
                "sync_state = 'PENDING', generation = m.generation + 1, attempt_count = 0, " +
                "next_attempt_at = ?3, pending_since = ?3, last_error = NULL, updated_at = ?3 " +
                "WHERE m.case_id = ?1 " +
                "AND (m.desired = 'REGISTERED') <> (m.subject_ref IN (SELECT ref FROM wanted))"

        /**
         * A case counts as approved exactly as `BusinessOnboardingCase.monitored` defines it.
         * `"key":"` is in the stored document of every screened subject, so the LIKE keeps a case
         * whose screening holds nobody out of the list instead of returning it on every run.
         */
        const val BACKFILL_SQL =
            "SELECT c.case_id FROM kyb_cases c WHERE c.status IN ('SIGNED', 'ACTIVE') " +
                "AND c.screening_json LIKE '%\"key\":\"%' " +
                "AND NOT EXISTS (SELECT 1 FROM kyb_monitored_subjects m WHERE m.case_id = c.case_id) " +
                "ORDER BY c.id LIMIT $1"

        private const val OPEN_REVIEW =
            "c.monitoring_review_opened_at IS NOT NULL AND c.monitoring_exit_required_at IS NULL"

        const val STATS_SQL =
            "SELECT " +
                "(SELECT count(*) FROM kyb_monitored_subjects " +
                "  WHERE desired = 'REGISTERED' AND sync_state = 'SYNCED'), " +
                "(SELECT count(*) FROM kyb_monitored_subjects WHERE sync_state = 'PENDING'), " +
                "(SELECT COALESCE(EXTRACT(EPOCH FROM ($1::timestamptz - min(pending_since))), 0)::bigint " +
                "  FROM kyb_monitored_subjects WHERE sync_state = 'PENDING'), " +
                "(SELECT count(*) FROM kyb_monitored_subjects " +
                "  WHERE desired = 'REGISTERED' AND sync_state = 'REFUSED'), " +
                "(SELECT count(*) FROM kyb_cases c WHERE $OPEN_REVIEW), " +
                "(SELECT COALESCE(EXTRACT(EPOCH FROM " +
                "  ($1::timestamptz - min(c.monitoring_review_opened_at))), 0)::bigint " +
                "  FROM kyb_cases c WHERE $OPEN_REVIEW), " +
                "(SELECT count(*) FROM kyb_cases c WHERE c.monitoring_exit_required_at IS NOT NULL), " +
                "(SELECT count(*) FROM kyb_cases c WHERE c.status IN ('SIGNED', 'ACTIVE') AND c.screening_json IS NULL)"
    }
}
