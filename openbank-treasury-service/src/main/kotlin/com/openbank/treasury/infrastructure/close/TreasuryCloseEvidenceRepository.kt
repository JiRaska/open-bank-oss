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
 * Postgres day-close evidence for treasury (ADR-0342 D1): `treasury_close_job_run` (V26), the shape
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
     * Known lifecycle events follow their exact deal/event journal value day. The UTC range
     * attributes only events without a matching dated journal to their creation day.
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
                .setParameter(P_VALUE_DATE, accountingDate)
                .singleResult
        }.map { row ->
            DayJournalPostings(
                journals = (row[0] as Number).toInt(),
                pending = (row[1] as Number).toInt(),
                dead = (row[2] as Number).toInt(),
                lastSettlementAt = row[COL_LAST_SETTLEMENT]?.let { asInstant(it) },
                unapplied = (row[COL_UNAPPLIED] as Number).toInt(),
                openIntents = (row[COL_OPEN_INTENTS] as Number).toInt(),
            )
        }
    }

    override fun latestCompletedPassStart(job: String, from: LocalDate): Uni<Instant?> = sf.withSession { s ->
        s.createNativeQuery<Any?>(LATEST_PASS_SQL)
            .setParameter(P_LATEST_JOB, job)
            .setParameter(P_LATEST_FROM, from)
            .singleResult
    }.map { it?.let { v -> asInstant(v) } }

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
        const val P_VALUE_DATE = 3
        const val COL_LAST_SETTLEMENT = 3
        const val COL_UNAPPLIED = 4
        const val COL_OPEN_INTENTS = 5

        // Positional parameters of LATEST_PASS_SQL.
        const val P_LATEST_JOB = 1
        const val P_LATEST_FROM = 2

        // Only a COMPLETE pass (refused = 0) proves every SETTLED deal was assessed: a pass that ran out
        // of budget or failed a deal may have skipped exactly the deal settled late (#13882 final review).
        const val LATEST_PASS_SQL =
            "SELECT max(started_at) FROM treasury_close_job_run WHERE job = ?1 AND business_date >= ?2 " +
                "AND NOT running AND completed_at IS NOT NULL AND refused = 0"

        // Legacy lifecycle keys are one journal per (deal, event). Match the exact event kind,
        // not every event for an aggregate: settlement, maturity and reversal can have different
        // value days. An event without attributable journal evidence retains its creation-day hold.
        const val EVENT_JOURNAL_MATCH =
            "j.deal_id = o.aggregate_id AND j.event = CASE o.event_type " +
                "WHEN 'treasury.deal.settled.v1' THEN 'SETTLED' " +
                "WHEN 'treasury.deal.matured.v1' THEN 'MATURED' " +
                "WHEN 'treasury.deal.reversed.v1' THEN 'REVERSED' END"

        const val EVENT_DAY_SCOPE =
            "(EXISTS (SELECT 1 FROM deal_journals j WHERE " + EVENT_JOURNAL_MATCH + " AND j.value_date = ?3) " +
                "OR (o.created_at >= ?1 AND o.created_at < ?2 AND NOT EXISTS " +
                "(SELECT 1 FROM deal_journals j WHERE " + EVENT_JOURNAL_MATCH + " AND j.value_date IS NOT NULL)))"

        // A journal acknowledgement commits independently of the lifecycle save. Only a matching
        // append-only transition proves that save (including its outbox insertion) committed.
        // Current state is insufficient: a legitimate settled deal may subsequently mature/reverse.
        const val POSTINGS_SQL =
            "SELECT " +
                "(SELECT count(*) FROM deal_journals WHERE value_date = ?3), " +
                "(SELECT count(*) FROM treasury_outbox o WHERE o.event_type IN (" + JOURNAL_EVENTS + ") " +
                "AND o.status IN ('PENDING', 'DISPATCHING', 'FAILED') AND " + EVENT_DAY_SCOPE + "), " +
                "(SELECT count(*) FROM treasury_outbox o WHERE o.event_type IN (" + JOURNAL_EVENTS + ") " +
                "AND o.status = 'DEAD' AND " + EVENT_DAY_SCOPE + "), " +
                "(SELECT max(posted_at) FROM deal_journals WHERE value_date = ?3 " +
                "AND event IN ('SETTLED', 'MATURED')), " +
                "(SELECT count(*) FROM deal_journals j WHERE j.value_date = ?3 " +
                "AND j.event IN ('SETTLED', 'MATURED', 'REVERSED') AND NOT EXISTS " +
                "(SELECT 1 FROM deal_transitions t WHERE t.deal_id = j.deal_id AND t.to_state = j.event)), " +
                // #13883: a lifecycle post decided for this value day and not yet applied, whether or
                // not treasury has recorded the ledger's answer: the ledger may hold its journal.
                "(SELECT count(*) FROM deal_posting_intent WHERE status = 'OPEN' AND value_date = ?3)"
    }
}
