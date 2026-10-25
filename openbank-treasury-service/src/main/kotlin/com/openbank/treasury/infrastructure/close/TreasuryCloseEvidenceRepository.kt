// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.treasury.infrastructure.close

import com.openbank.libs.close.CloseJobRun
import com.openbank.libs.domain.calendar.AccountingClock
import com.openbank.treasury.application.port.out.DayJournalPostings
import com.openbank.treasury.application.port.out.InFlightRun
import com.openbank.treasury.application.port.out.TreasuryCloseEvidencePort
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Postgres day-close evidence for treasury (ADR-0342 D1): `treasury_close_job_run` (V21), the shape
 * lending-service keeps (in-flight marker, items accumulating across a day's passes), plus the day's
 * journals: `deal_journals` rows posted in the Prague day, and the deal events announcing them that
 * are still in `treasury_outbox`.
 */
@ApplicationScoped
class TreasuryCloseEvidenceRepository(private val sf: Mutiny.SessionFactory) : TreasuryCloseEvidencePort {

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

    /**
     * The Prague day is turned into a UTC instant range here, so both tables are read by a plain
     * range on their timestamp (indexed: `idx_deal_journals_posted_at`, `idx_treasury_outbox_status`).
     */
    override fun postingsFor(accountingDate: LocalDate): Uni<DayJournalPostings> {
        val start = OffsetDateTime.ofInstant(
            accountingDate.atStartOfDay(AccountingClock.BANK_ZONE).toInstant(),
            ZoneOffset.UTC,
        )
        val end = OffsetDateTime.ofInstant(
            accountingDate.plusDays(1).atStartOfDay(AccountingClock.BANK_ZONE).toInstant(),
            ZoneOffset.UTC,
        )
        return sf.withSession { s ->
            s.createNativeQuery<Array<Any?>>(POSTINGS_SQL)
                .setParameter(1, start)
                .setParameter(2, end)
                .singleResult
        }.map { row ->
            DayJournalPostings(
                journals = (row[0] as Number).toInt(),
                pending = (row[1] as Number).toInt(),
                dead = (row[2] as Number).toInt(),
            )
        }
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
            "INSERT INTO treasury_close_job_run (business_date, job, running, started_at) " +
                "VALUES (?1, ?2, TRUE, ?3) " +
                "ON CONFLICT (business_date, job) DO UPDATE SET running = TRUE, started_at = EXCLUDED.started_at"

        const val UPSERT_SQL =
            "INSERT INTO treasury_close_job_run " +
                "(business_date, job, items_processed, journals_posted, refused, refusal_reason, completed_at) " +
                "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7) " +
                "ON CONFLICT (business_date, job) DO UPDATE SET " +
                "items_processed = treasury_close_job_run.items_processed + EXCLUDED.items_processed, " +
                "journals_posted = treasury_close_job_run.journals_posted + EXCLUDED.journals_posted, " +
                "refused = EXCLUDED.refused, refusal_reason = EXCLUDED.refusal_reason, " +
                "completed_at = EXCLUDED.completed_at, running = FALSE"

        const val SELECT_SQL =
            "SELECT business_date, job, items_processed, journals_posted, refused, refusal_reason, completed_at " +
                "FROM treasury_close_job_run WHERE business_date BETWEEN ?1 AND ?2 " +
                "AND NOT running AND completed_at IS NOT NULL ORDER BY business_date, job"

        const val IN_FLIGHT_SQL =
            "SELECT business_date, job, started_at FROM treasury_close_job_run " +
                "WHERE business_date BETWEEN ?1 AND ?2 AND running ORDER BY business_date, job"

        /** The deal events committed with a journal (settle, mature, reverse). */
        const val JOURNAL_EVENTS =
            "'treasury.deal.settled.v1', 'treasury.deal.matured.v1', 'treasury.deal.reversed.v1'"

        // FAILED is a retryable outbox state (the dispatcher re-claims it), so it is still pending;
        // only DEAD means the event will never leave. SENT rows are not counted at all: SENT retention
        // purges them, so the day's journal count comes from deal_journals instead.
        const val POSTINGS_SQL =
            "SELECT " +
                "(SELECT count(*) FROM deal_journals WHERE posted_at >= ?1 AND posted_at < ?2), " +
                "(SELECT count(*) FROM treasury_outbox WHERE event_type IN (" + JOURNAL_EVENTS + ") " +
                "AND status IN ('PENDING', 'DISPATCHING', 'FAILED') AND created_at >= ?1 AND created_at < ?2), " +
                "(SELECT count(*) FROM treasury_outbox WHERE event_type IN (" + JOURNAL_EVENTS + ") " +
                "AND status = 'DEAD' AND created_at >= ?1 AND created_at < ?2)"
    }
}
