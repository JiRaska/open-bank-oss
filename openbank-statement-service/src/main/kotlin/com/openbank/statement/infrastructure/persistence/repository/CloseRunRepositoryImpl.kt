// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.statement.infrastructure.persistence.repository

import com.openbank.statement.application.port.out.CloseRunRepository
import com.openbank.statement.domain.model.CloseFailure
import com.openbank.statement.domain.model.CloseRun
import com.openbank.statement.infrastructure.persistence.entity.CloseFailureEntity
import com.openbank.statement.infrastructure.persistence.entity.CloseRunEntity
import io.quarkus.hibernate.reactive.panache.common.WithSession
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.hibernate.reactive.mutiny.Mutiny
import java.util.UUID

@ApplicationScoped
class CloseRunRepositoryImpl @Inject constructor(private val sf: Mutiny.SessionFactory) : CloseRunRepository {

    @WithTransaction
    override fun startRun(run: CloseRun): Uni<CloseRun> {
        val e = toEntity(run)
        return sf.withTransaction { s -> s.persist(e) }.replaceWith(run)
    }

    @WithTransaction
    override fun finishRun(run: CloseRun): Uni<CloseRun> = sf.withTransaction { s ->
        s.find(CloseRunEntity::class.java, run.id).flatMap { e ->
            if (e == null) {
                Uni.createFrom().item(run)
            } else {
                e.status = run.status
                e.periodFrom = run.periodFrom
                e.periodTo = run.periodTo
                e.accountsEnumerated = run.accountsEnumerated
                e.pocketsClosed = run.pocketsClosed
                e.pocketsFailed = run.pocketsFailed
                e.pocketsSkipped = run.pocketsSkipped
                e.finishedAt = run.finishedAt
                s.flush().replaceWith(run)
            }
        }
    }

    @WithTransaction
    override fun recordFailure(failure: CloseFailure): Uni<CloseFailure> {
        val e = CloseFailureEntity().apply {
            id = failure.id
            runId = failure.runId
            accountId = failure.accountId
            pocketCurrency = failure.pocketCurrency
            periodFrom = failure.periodFrom
            periodTo = failure.periodTo
            reason = failure.reason
            detail = failure.detail
            failedAt = failure.failedAt
        }
        return sf.withTransaction { s -> s.persist(e) }.replaceWith(failure)
    }

    @WithSession
    override fun latestRun(): Uni<CloseRun?> = sf.withSession { s ->
        s.createQuery("FROM CloseRunEntity ORDER BY startedAt DESC", CloseRunEntity::class.java)
            .setMaxResults(1).singleResultOrNull
    }.map { it?.let(::toDomain) }

    @WithSession
    override fun recentRuns(limit: Int): Uni<List<CloseRun>> = sf.withSession { s ->
        s.createQuery("FROM CloseRunEntity ORDER BY startedAt DESC", CloseRunEntity::class.java)
            .setMaxResults(limit.coerceIn(1, 200)).resultList
    }.map { list -> list.map(::toDomain) }

    @WithSession
    override fun failuresForRun(runId: UUID): Uni<List<CloseFailure>> = sf.withSession { s ->
        s.createQuery(
            "FROM CloseFailureEntity WHERE runId = :r ORDER BY failedAt ASC",
            CloseFailureEntity::class.java,
        ).setParameter("r", runId).resultList
    }.map { list -> list.map(::toDomain) }

    private fun toEntity(run: CloseRun): CloseRunEntity = CloseRunEntity().apply {
        id = run.id
        trigger = run.trigger
        status = run.status
        periodFrom = run.periodFrom
        periodTo = run.periodTo
        accountsEnumerated = run.accountsEnumerated
        pocketsClosed = run.pocketsClosed
        pocketsFailed = run.pocketsFailed
        pocketsSkipped = run.pocketsSkipped
        startedAt = run.startedAt
        finishedAt = run.finishedAt
    }

    private fun toDomain(e: CloseRunEntity): CloseRun = CloseRun(
        id = e.id,
        trigger = e.trigger,
        status = e.status,
        periodFrom = e.periodFrom,
        periodTo = e.periodTo,
        accountsEnumerated = e.accountsEnumerated,
        pocketsClosed = e.pocketsClosed,
        pocketsFailed = e.pocketsFailed,
        pocketsSkipped = e.pocketsSkipped,
        startedAt = e.startedAt,
        finishedAt = e.finishedAt,
    )

    private fun toDomain(e: CloseFailureEntity): CloseFailure = CloseFailure(
        id = e.id,
        runId = e.runId,
        accountId = e.accountId,
        pocketCurrency = e.pocketCurrency,
        periodFrom = e.periodFrom,
        periodTo = e.periodTo,
        reason = e.reason,
        detail = e.detail,
        failedAt = e.failedAt,
    )
}
