// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.repository

import com.openbank.lending.application.port.out.AllowancePostings
import com.openbank.lending.application.port.out.InFlightRun
import com.openbank.lending.application.port.out.LendingCloseEvidencePort
import com.openbank.libs.close.CloseJobRun
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Postgres day-close evidence for lending: [CloseJobRunPort] over `lending_close_job_run` (V28), and
 * [AllowancePostingsPort] over the `lending.allowance.posting` rows of `lending_outbox`.
 *
 * Same shape as interest-service's run table, except that a second run on the same day ADDS its items
 * and journals (lending's jobs run on an interval, so a restart can run a job twice in one day) and a
 * pass marks itself in flight before it starts.
 */
@ApplicationScoped
class CloseJobRunRepository(private val sf: Mutiny.SessionFactory) : LendingCloseEvidencePort {

    override fun markStarted(businessDate: LocalDate, job: String, at: Instant): Uni<Unit> = sf.withTransaction { s ->
        s.createNativeQuery<Int>(START_SQL)
            .setParameter(P_DATE, businessDate)
            .setParameter(P_JOB, job)
            .setParameter(P_ITEMS, OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .executeUpdate()
    }.replaceWith(Unit)

    override fun record(run: CloseJobRun): Uni<Unit> = sf.withTransaction { s ->
        s.createNativeQuery<Int>(UPSERT_SQL)
            .setParameter(P_DATE, run.businessDate)
            .setParameter(P_JOB, run.job)
            .setParameter(P_ITEMS, run.itemsProcessed)
            .setParameter(P_JOURNALS, run.journalsPosted)
            .setParameter(P_REFUSED, run.refused)
            .setParameter(P_REASON, run.refusalReason)
            .setParameter(P_COMPLETED, OffsetDateTime.ofInstant(run.completedAt, ZoneOffset.UTC))
            .executeUpdate()
    }.replaceWith(Unit)

    override fun addJournals(businessDate: LocalDate, job: String, journals: Int): Uni<Unit> = sf.withTransaction { s ->
        s.createNativeQuery<Int>(ADD_JOURNALS_SQL)
            .setParameter(P_DATE, businessDate)
            .setParameter(P_JOB, job)
            .setParameter(P_ITEMS, journals)
            .executeUpdate()
    }.replaceWith(Unit)

    override fun findBetween(from: LocalDate, to: LocalDate): Uni<List<CloseJobRun>> = sf.withSession { s ->
        s.createNativeQuery<Array<Any?>>(SELECT_SQL)
            .setParameter(P_DATE, from)
            .setParameter(P_JOB, to)
            .resultList
    }.map { rows -> rows.map { it.toRun() } }

    override fun inFlightBetween(from: LocalDate, to: LocalDate): Uni<List<InFlightRun>> = sf.withSession { s ->
        s.createNativeQuery<Array<Any?>>(IN_FLIGHT_SQL)
            .setParameter(P_DATE, from)
            .setParameter(P_JOB, to)
            .resultList
    }.map { rows -> rows.map { InFlightRun(asDate(it[0]), it[1] as String, asInstant(it[2])) } }

    override fun postingsFor(accountingDate: LocalDate): Uni<AllowancePostings> = sf.withSession { s ->
        s.createNativeQuery<Array<Any?>>(ALLOWANCE_SQL)
            .setParameter(1, accountingDate.toString())
            .singleResult
    }.map { row ->
        AllowancePostings(
            pending = (row[0] as Number).toInt(),
            posted = (row[1] as Number).toInt(),
            dead = (row[2] as Number).toInt(),
        )
    }

    // Column order of SELECT_SQL.
    private fun Array<Any?>.toRun() = CloseJobRun(
        businessDate = asDate(this[COL_DATE]),
        job = this[COL_JOB] as String,
        itemsProcessed = (this[COL_ITEMS] as Number).toInt(),
        journalsPosted = (this[COL_JOURNALS] as Number).toInt(),
        refused = (this[COL_REFUSED] as Number).toInt(),
        refusalReason = this[COL_REASON] as? String,
        completedAt = asInstant(this[COL_COMPLETED]),
    )

    private fun asDate(value: Any?): LocalDate = when (value) {
        is LocalDate -> value
        is java.sql.Date -> value.toLocalDate()
        else -> LocalDate.parse(value.toString())
    }

    private fun asInstant(value: Any?): Instant = when (value) {
        is Instant -> value
        is OffsetDateTime -> value.toInstant()
        is java.sql.Timestamp -> value.toInstant()
        else -> OffsetDateTime.parse(value.toString()).toInstant()
    }

    private companion object {
        // Positional parameters of UPSERT_SQL (SELECT_SQL reuses 1 and 2 for its range, START_SQL 1-3).
        const val P_DATE = 1
        const val P_JOB = 2
        const val P_ITEMS = 3
        const val P_JOURNALS = 4
        const val P_REFUSED = 5
        const val P_REASON = 6
        const val P_COMPLETED = 7
        const val COL_DATE = 0
        const val COL_JOB = 1
        const val COL_ITEMS = 2
        const val COL_JOURNALS = 3
        const val COL_REFUSED = 4
        const val COL_REASON = 5
        const val COL_COMPLETED = 6

        const val START_SQL =
            "INSERT INTO lending_close_job_run (business_date, job, running, started_at) " +
                "VALUES (?1, ?2, TRUE, ?3) " +
                "ON CONFLICT (business_date, job) DO UPDATE SET running = TRUE, started_at = EXCLUDED.started_at"

        const val UPSERT_SQL =
            "INSERT INTO lending_close_job_run " +
                "(business_date, job, items_processed, journals_posted, refused, refusal_reason, completed_at) " +
                "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7) " +
                "ON CONFLICT (business_date, job) DO UPDATE SET " +
                "items_processed = lending_close_job_run.items_processed + EXCLUDED.items_processed, " +
                "journals_posted = lending_close_job_run.journals_posted + EXCLUDED.journals_posted, " +
                "refused = CASE WHEN lending_close_job_run.unreconciled THEN " +
                "GREATEST(EXCLUDED.refused, 1) ELSE EXCLUDED.refused END," +
                "refusal_reason = CASE WHEN lending_close_job_run.unreconciled THEN 'accrual " +
                "evidence requires reconciliation' ELSE EXCLUDED.refusal_reason END," +
                "completed_at = EXCLUDED.completed_at, running = FALSE"

        // Only journals_posted moves; refused, refusal_reason, running and completed_at are left alone.
        const val ADD_JOURNALS_SQL =
            "INSERT INTO lending_close_job_run (business_date, job, journals_posted) VALUES (?1, ?2, ?3) " +
                "ON CONFLICT (business_date, job) DO UPDATE SET " +
                "journals_posted = lending_close_job_run.journals_posted + EXCLUDED.journals_posted"

        const val SELECT_SQL =
            "SELECT business_date, job, items_processed, journals_posted, " +
                "CASE WHEN unreconciled OR (job='accrual' AND EXISTS (SELECT 1 FROM lending_accrual_progress p " +
                "WHERE p.completed_at IS NULL AND (p.business_date=r.business_date OR " +
                "p.requested_date=r.business_date)))" +
                "THEN GREATEST(refused,1) ELSE refused END, " +
                "CASE WHEN unreconciled OR (job='accrual' AND EXISTS (SELECT 1 FROM lending_accrual_progress p " +
                "WHERE p.completed_at IS NULL AND (p.business_date=r.business_date OR " +
                "p.requested_date=r.business_date)))" +
                "THEN 'accrual evidence requires reconciliation' ELSE refusal_reason END, completed_at " +
                "FROM lending_close_job_run r WHERE business_date BETWEEN ?1 AND ?2 " +
                "AND NOT running AND completed_at IS NOT NULL ORDER BY business_date, job"

        const val IN_FLIGHT_SQL =
            "SELECT business_date, job, started_at FROM lending_close_job_run " +
                "WHERE business_date BETWEEN ?1 AND ?2 AND running ORDER BY business_date, job"

        // FAILED is a retryable outbox state (the dispatcher re-claims it), so it is still pending;
        // only DEAD means the journal will never be booked.
        const val ALLOWANCE_SQL =
            "SELECT " +
                "count(*) FILTER (WHERE status IN ('PENDING', 'DISPATCHING', 'FAILED')), " +
                "count(*) FILTER (WHERE status = 'SENT'), " +
                "count(*) FILTER (WHERE status = 'DEAD') " +
                "FROM lending_outbox WHERE event_type = 'lending.allowance.posting' " +
                "AND CAST(payload AS jsonb) ->> 'accountingDate' = ?1"
    }
}
