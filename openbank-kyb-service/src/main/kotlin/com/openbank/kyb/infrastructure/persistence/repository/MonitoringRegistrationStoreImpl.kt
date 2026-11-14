// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.persistence.repository

import com.openbank.kyb.application.port.out.FindingDecisionConflictException
import com.openbank.kyb.application.port.out.MonitoringDesired
import com.openbank.kyb.application.port.out.MonitoringRegistration
import com.openbank.kyb.application.port.out.MonitoringRegistrationStore
import com.openbank.kyb.application.port.out.MonitoringStats
import com.openbank.kyb.domain.model.FindingDisposition
import com.openbank.kyb.domain.model.MonitoringFinding
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
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Postgres store of the monitoring registrations and findings. See [MonitoringRegistrationStore]
 * for the contract. Plain SQL, no entity: there is no loaded row whose stale copy could be
 * written back.
 *
 *  - The two `InTransaction` statements run on the caller's Hibernate session, so they commit
 *    with the case row. Each is ONE statement whatever the case holds.
 *  - Every other write names ONE row by id and repeats the condition it relies on, so it holds
 *    one row lock at a time (nothing here can deadlock with a case write) and a row that changed
 *    since it was read is left alone.
 *  - A delivery is fenced twice: by `generation` (the wish it was sent for) and by the claim
 *    (the relay that sent it).
 */
@ApplicationScoped
@Suppress("TooManyFunctions") // one statement per thing the relay, the consumer and the operator view ask
class MonitoringRegistrationStoreImpl(private val pool: Pool) : MonitoringRegistrationStore {

    override fun syncInTransaction(caseId: UUID, desiredKeys: Set<String>, now: Instant): Uni<Void> =
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Any>(SYNC_SQL)
                .setParameter(1, caseId)
                .setParameter(2, desiredKeys.joinToString(KEY_SEPARATOR))
                .setParameter(TIME_PARAMETER, now.atOffset(ZoneOffset.UTC))
                .executeUpdate()
        }.replaceWithVoid()

    override fun saveFindingsInTransaction(caseId: UUID, findings: List<MonitoringFinding>): Uni<Void> =
        Panache.getSession().flatMap { session ->
            session.createNativeQuery<Any>(FINDINGS_SQL)
                .setParameter(1, caseId)
                .setParameter(2, KybJson.mapper.writeValueAsString(findings.map(::findingDocument)))
                .executeUpdate()
        }.map { written -> allWritten(caseId, expected = findings.size, written = written) }.replaceWithVoid()

    /**
     * Every finding the case holds must be a row that says the same. The statement writes each
     * one or leaves it out, so a count short of [expected] means a stored row disagrees with the
     * case being written — and then nothing of this case write may commit: the case's own
     * monitoring columns would describe findings that are not there.
     */
    private fun allWritten(caseId: UUID, expected: Int, written: Int) {
        if (written != expected) throw FindingDecisionConflictException(caseId)
    }

    /** Text only, so the statement casts each value itself and no mapper setting can change the wire form. */
    private fun findingDocument(f: MonitoringFinding): Map<String, String?> = mapOf(
        "findingId" to f.findingId.toString(),
        "subjectKey" to f.subjectKey,
        "matchKind" to f.matchKind,
        "listType" to f.listType,
        "listEntryRef" to f.listEntryRef,
        "listEntryVersion" to f.listEntryVersion,
        "matchScore" to f.matchScore.toString(),
        "matchStatus" to f.matchStatus,
        "detection" to f.detection,
        "receivedAt" to f.receivedAt.toString(),
        "disposition" to f.disposition.name,
        "decidedBy" to f.decidedBy,
        "decisionReason" to f.decisionReason,
        "decidedAt" to f.decidedAt?.toString(),
    )

    override suspend fun findingsOf(caseId: UUID): List<MonitoringFinding> = pool.preparedQuery(
        "SELECT finding_id, subject_key, match_kind, list_type, list_entry_ref, list_entry_version, " +
            "match_score, match_status, detection, received_at, disposition, decided_by, decision_reason, " +
            "decided_at FROM kyb_monitoring_findings WHERE case_id = $1 ORDER BY received_at, finding_id",
    ).execute(Tuple.of(caseId)).awaitSuspending().map {
        MonitoringFinding(
            findingId = it.getUUID("finding_id"),
            subjectKey = it.getString("subject_key"),
            matchKind = it.getString("match_kind"),
            listType = it.getString("list_type"),
            listEntryRef = it.getString("list_entry_ref"),
            listEntryVersion = it.getString("list_entry_version"),
            matchScore = it.getDouble("match_score"),
            matchStatus = it.getString("match_status"),
            detection = it.getString("detection"),
            receivedAt = it.getOffsetDateTime("received_at").toInstant(),
            disposition = FindingDisposition.valueOf(it.getString("disposition")),
            decidedBy = it.getString("decided_by"),
            decisionReason = it.getString("decision_reason"),
            decidedAt = it.getOffsetDateTime("decided_at")?.toInstant(),
        )
    }

    override suspend fun claimNext(
        now: Instant,
        leaseUntil: Instant,
        withdrawalsOnly: Boolean,
    ): MonitoringRegistration? {
        val claimable = if (withdrawalsOnly) "$CLAIMABLE AND $HELD_BY_REGISTRY_WITHDRAWAL" else CLAIMABLE
        // The outer condition repeats the inner one: of two relays that picked the same row,
        // the second waits for the first's update, sees the lease and takes nothing.
        return pool.preparedQuery(
            "UPDATE kyb_monitored_subjects SET claimed_until = $2 WHERE id = " +
                "(SELECT id FROM kyb_monitored_subjects WHERE $claimable ORDER BY next_attempt_at, id LIMIT 1) " +
                "AND $claimable RETURNING $ROW",
        ).execute(Tuple.of(now.utc(), leaseUntil.utc())).awaitSuspending().firstOrNull()?.let(::registration)
    }

    override suspend fun markSynced(registration: MonitoringRegistration, now: Instant): Boolean {
        val recorded = pool.preparedQuery(
            "UPDATE kyb_monitored_subjects SET sync_state = 'SYNCED', synced_at = $4, pending_since = NULL, " +
                "claimed_until = NULL, last_error = NULL, updated_at = $4 WHERE $HELD",
        ).execute(held(registration, now.utc())).awaitSuspending().rowCount() > 0
        // The wish changed while the call was in flight: nothing is recorded, and the row is
        // handed back so the new wish is sent next.
        if (!recorded) release(registration)
        return recorded
    }

    override suspend fun markFailed(
        registration: MonitoringRegistration,
        now: Instant,
        nextAttemptAt: Instant,
        reason: String,
    ) {
        val recorded = pool.preparedQuery(
            "UPDATE kyb_monitored_subjects SET attempt_count = attempt_count + 1, next_attempt_at = $5, " +
                "last_error = $6, claimed_until = NULL, updated_at = $4 WHERE $HELD",
        ).execute(held(registration, now.utc(), nextAttemptAt.utc(), reason.take(MAX_REASON)))
            .awaitSuspending().rowCount() > 0
        if (!recorded) release(registration)
    }

    override suspend fun markRefused(registration: MonitoringRegistration, now: Instant, reason: String) {
        val recorded = pool.preparedQuery(
            "UPDATE kyb_monitored_subjects SET sync_state = 'REFUSED', attempt_count = attempt_count + 1, " +
                "pending_since = NULL, last_error = $5, claimed_until = NULL, updated_at = $4 WHERE $HELD",
        ).execute(held(registration, now.utc(), reason.take(MAX_REASON))).awaitSuspending().rowCount() > 0
        if (!recorded) release(registration)
    }

    /** The three values [HELD] reads (id, generation, claim), then the statement's own. */
    private fun held(registration: MonitoringRegistration, vararg own: Any): Tuple {
        val tuple = Tuple.of(registration.id, registration.generation, claimOf(registration))
        own.forEach { tuple.addValue(it) }
        return tuple
    }

    private suspend fun release(registration: MonitoringRegistration) {
        pool.preparedQuery(
            "UPDATE kyb_monitored_subjects SET claimed_until = NULL WHERE id = $1 AND claimed_until = $2",
        )
            .execute(Tuple.of(registration.id, claimOf(registration))).awaitSuspending()
    }

    private fun claimOf(registration: MonitoringRegistration): OffsetDateTime =
        requireNotNull(registration.claim) { "only a claimed row can be written about" }.utc()

    override suspend fun findBySubjectRef(subjectRef: String): MonitoringRegistration? =
        pool.preparedQuery("SELECT $ROW FROM kyb_monitored_subjects WHERE subject_ref = $1")
            .execute(Tuple.of(subjectRef)).awaitSuspending().firstOrNull()?.let(::registration)

    override suspend fun requeueSettledBefore(before: Instant, now: Instant, limit: Int): Int {
        val ids = pool.preparedQuery(
            "SELECT id FROM kyb_monitored_subjects WHERE $SETTLED AND updated_at < $1 ORDER BY updated_at, id LIMIT $2",
        ).execute(Tuple.of(before.utc(), limit)).awaitSuspending().map { it.getLong(0) }
        // One row per statement, each re-checking what the read saw: a row a case write turned to
        // WITHDRAWN in between is left alone, and no two row locks are ever held at once.
        return ids.count { id ->
            pool.preparedQuery(
                "UPDATE kyb_monitored_subjects SET sync_state = 'PENDING', attempt_count = 0, next_attempt_at = $1, " +
                    "pending_since = $1, updated_at = $1 WHERE id = $2 AND $SETTLED AND updated_at < $3",
            ).execute(Tuple.of(now.utc(), id, before.utc())).awaitSuspending().rowCount() > 0
        }
    }

    override suspend fun casesToReconcile(limit: Int): List<UUID> =
        pool.preparedQuery("SELECT case_id FROM kyb_cases c WHERE $UNRECONCILED ORDER BY c.id LIMIT $1")
            .execute(Tuple.of(limit)).awaitSuspending().map { it.getUUID(0) }

    override suspend fun statesOf(caseId: UUID): Map<String, String> = pool.preparedQuery(
        "SELECT subject_key, sync_state FROM kyb_monitored_subjects WHERE case_id = $1 AND desired = 'REGISTERED'",
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
            exitsOwed = row.getLong(column++),
            oldestExitOwedAgeSeconds = row.getLong(column++),
            unscreenedApprovedCases = row.getLong(column++),
            unreconciledCases = row.getLong(column),
        )
    }

    private fun registration(row: Row) = MonitoringRegistration(
        id = row.getLong("id"),
        caseId = row.getUUID("case_id"),
        subjectKey = row.getString("subject_key"),
        subjectRef = row.getString("subject_ref"),
        desired = MonitoringDesired.valueOf(row.getString("desired")),
        generation = row.getLong("generation"),
        attemptCount = row.getInteger("attempt_count"),
        claim = row.getOffsetDateTime("claimed_until")?.toInstant(),
    )

    /** Postgres keeps microseconds; a value compared with a stored one must not carry more. */
    private fun Instant.utc(): OffsetDateTime = truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC)

    private companion object {
        const val MAX_REASON = 64
        const val TIME_PARAMETER = 3

        /** A subject key is hexadecimal; it cannot contain this. */
        const val KEY_SEPARATOR = ","

        const val ROW = "id, case_id, subject_key, subject_ref, desired, generation, attempt_count, claimed_until"

        /** Due, and held by nobody; `$1` is the time. */
        const val CLAIMABLE =
            "sync_state = 'PENDING' AND next_attempt_at <= $1 AND (claimed_until IS NULL OR claimed_until < $1)"

        /**
         * The row exactly as its claimer left it: the same wish, still owed, still this relay's
         * lease. Always the first three parameters of a statement: id, generation, claim.
         */
        const val HELD = "id = $1 AND generation = $2 AND sync_state = 'PENDING' AND claimed_until = $3"

        /** Settled either way: a registration or a withdrawal that was confirmed, or refused. */
        const val SETTLED = "sync_state IN ('SYNCED', 'REFUSED')"

        /** A withdrawal of a subject the registry has held: the row was delivered at least once. */
        const val HELD_BY_REGISTRY_WITHDRAWAL = "desired = 'WITHDRAWN' AND synced_at IS NOT NULL"

        /**
         * The case's last writer did not bring this store up to date, and there is something to
         * bring: the case is approved, or it has rows. An ended case that never had a row has
         * nothing to withdraw and is left alone.
         */
        const val UNRECONCILED =
            "c.monitoring_synced_version IS DISTINCT FROM c.version AND (c.status IN ('SIGNED', 'ACTIVE') " +
                "OR EXISTS (SELECT 1 FROM kyb_monitored_subjects m WHERE m.case_id = c.case_id))"

        /**
         * ?1 case id, ?2 the desired subject keys joined by [KEY_SEPARATOR], ?3 the time. The
         * insert adds what is missing, under a reference the database mints, and yields to a row
         * that exists. The update turns every row whose desired state disagrees with ?2 and makes
         * a still-desired REFUSED row due again. A row the insert just added is not visible to
         * the update of the same statement, and needs no turning.
         */
        const val SYNC_SQL =
            "WITH wanted AS (SELECT k AS key FROM unnest(string_to_array(cast(?2 as text), ',')) AS k " +
                "WHERE k <> ''), " +
                "added AS (INSERT INTO kyb_monitored_subjects " +
                "(id, case_id, subject_key, next_attempt_at, pending_since, created_at, updated_at) " +
                "SELECT nextval('kyb_monitored_subjects_seq'), ?1, key, ?3, ?3, ?3, ?3 " +
                "FROM wanted ON CONFLICT (case_id, subject_key) DO NOTHING) " +
                "UPDATE kyb_monitored_subjects m SET " +
                "desired = CASE WHEN m.subject_key IN (SELECT key FROM wanted) " +
                "THEN 'REGISTERED' ELSE 'WITHDRAWN' END, " +
                "generation = m.generation + CASE WHEN (m.desired = 'REGISTERED') = " +
                "(m.subject_key IN (SELECT key FROM wanted)) THEN 0 ELSE 1 END, " +
                "sync_state = 'PENDING', attempt_count = 0, next_attempt_at = ?3, pending_since = ?3, " +
                "last_error = NULL, updated_at = ?3 " +
                "WHERE m.case_id = ?1 AND (" +
                "(m.desired = 'REGISTERED') <> (m.subject_key IN (SELECT key FROM wanted)) " +
                "OR (m.sync_state = 'REFUSED' AND m.subject_key IN (SELECT key FROM wanted)))"

        /**
         * ?1 case id, ?2 the findings as a JSON array of text fields. A finding is a row per
         * (case, finding). Each element either inserts its row, or rewrites a stored row that is
         * still OPEN, or rewrites a stored row that already says exactly the same decision (disposition, decider,
         * time and reason). A
         * stored decision that differs is NOT written and so is missing from the row count,
         * which the caller checks. ?2 is cast to text before jsonb: bound as jsonb, the string
         * would arrive as one JSON string and not as the array it spells.
         */
        const val FINDINGS_SQL =
            "INSERT INTO kyb_monitoring_findings AS s (finding_id, case_id, subject_key, match_kind, list_type, " +
                "list_entry_ref, list_entry_version, match_score, match_status, detection, received_at, " +
                "disposition, decided_by, decision_reason, decided_at) " +
                "SELECT cast(f->>'findingId' as uuid), ?1, f->>'subjectKey', f->>'matchKind', f->>'listType', " +
                "f->>'listEntryRef', f->>'listEntryVersion', cast(f->>'matchScore' as double precision), " +
                "f->>'matchStatus', f->>'detection', cast(f->>'receivedAt' as timestamptz), f->>'disposition', " +
                "f->>'decidedBy', f->>'decisionReason', cast(f->>'decidedAt' as timestamptz) " +
                "FROM jsonb_array_elements(cast(cast(?2 as text) as jsonb)) AS f " +
                "ON CONFLICT (case_id, finding_id) DO UPDATE SET disposition = excluded.disposition, " +
                "decided_by = excluded.decided_by, decision_reason = excluded.decision_reason, " +
                "decided_at = excluded.decided_at " +
                "WHERE s.disposition = 'OPEN' OR (s.disposition = excluded.disposition " +
                "AND s.decided_by IS NOT DISTINCT FROM excluded.decided_by " +
                "AND s.decided_at IS NOT DISTINCT FROM excluded.decided_at " +
                "AND s.decision_reason IS NOT DISTINCT FROM excluded.decision_reason)"

        const val STATS_SQL =
            "SELECT " +
                "(SELECT count(*) FROM kyb_monitored_subjects " +
                "  WHERE desired = 'REGISTERED' AND sync_state = 'SYNCED'), " +
                "(SELECT count(*) FROM kyb_monitored_subjects WHERE sync_state = 'PENDING'), " +
                "(SELECT COALESCE(EXTRACT(EPOCH FROM ($1::timestamptz - min(pending_since))), 0)::bigint " +
                "  FROM kyb_monitored_subjects WHERE sync_state = 'PENDING'), " +
                "(SELECT count(*) FROM kyb_monitored_subjects " +
                "  WHERE desired = 'REGISTERED' AND sync_state = 'REFUSED'), " +
                "(SELECT count(*) FROM kyb_cases c WHERE c.monitoring_review_since IS NOT NULL), " +
                "(SELECT COALESCE(EXTRACT(EPOCH FROM " +
                "  ($1::timestamptz - min(c.monitoring_review_since))), 0)::bigint FROM kyb_cases c), " +
                "(SELECT count(*) FROM kyb_cases c WHERE c.monitoring_exit_owed_since IS NOT NULL), " +
                "(SELECT COALESCE(EXTRACT(EPOCH FROM " +
                "  ($1::timestamptz - min(c.monitoring_exit_owed_since))), 0)::bigint FROM kyb_cases c), " +
                "(SELECT count(*) FROM kyb_cases c " +
                "  WHERE c.status IN ('SIGNED', 'ACTIVE') AND c.screening_json IS NULL), " +
                "(SELECT count(*) FROM kyb_cases c WHERE $UNRECONCILED)"
    }
}
