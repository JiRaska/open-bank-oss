// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.interest.infrastructure.persistence.repository

import com.openbank.interest.application.port.out.CloseJobRunPort
import com.openbank.libs.close.CloseJobRun
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

@ApplicationScoped
class CloseJobRunRepository(private val sf: Mutiny.SessionFactory) : CloseJobRunPort {

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
        // Positional parameters of UPSERT_SQL (SELECT_SQL reuses 1 and 2 for its range).
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

        const val UPSERT_SQL =
            "INSERT INTO interest_close_job_run " +
                "(business_date, job, items_processed, journals_posted, refused, refusal_reason, completed_at) " +
                "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7) " +
                "ON CONFLICT (business_date, job) DO UPDATE SET items_processed = EXCLUDED.items_processed, " +
                "journals_posted = EXCLUDED.journals_posted, refused = EXCLUDED.refused, " +
                "refusal_reason = EXCLUDED.refusal_reason, completed_at = EXCLUDED.completed_at"

        const val SELECT_SQL =
            "SELECT business_date, job, items_processed, journals_posted, refused, refusal_reason, completed_at " +
                "FROM interest_close_job_run WHERE business_date BETWEEN ?1 AND ?2 ORDER BY business_date, job"
    }
}
