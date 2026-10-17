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
            .setParameter(1, run.businessDate)
            .setParameter(2, run.job)
            .setParameter(3, run.itemsProcessed)
            .setParameter(4, run.journalsPosted)
            .setParameter(5, run.refused)
            .setParameter(6, run.refusalReason)
            .setParameter(7, OffsetDateTime.ofInstant(run.completedAt, ZoneOffset.UTC))
            .executeUpdate()
    }.replaceWith(Unit)

    override fun findBetween(from: LocalDate, to: LocalDate): Uni<List<CloseJobRun>> = sf.withSession { s ->
        s.createNativeQuery<Array<Any?>>(SELECT_SQL)
            .setParameter(1, from)
            .setParameter(2, to)
            .resultList
    }.map { rows -> rows.map { it.toRun() } }

    private fun Array<Any?>.toRun() = CloseJobRun(
        businessDate = asDate(this[0]),
        job = this[1] as String,
        itemsProcessed = (this[2] as Number).toInt(),
        journalsPosted = (this[3] as Number).toInt(),
        refused = (this[4] as Number).toInt(),
        refusalReason = this[5] as String?,
        completedAt = asInstant(this[6]),
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
