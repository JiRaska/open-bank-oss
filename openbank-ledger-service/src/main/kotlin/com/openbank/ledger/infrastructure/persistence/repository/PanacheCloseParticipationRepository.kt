// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.persistence.repository

import com.openbank.ledger.application.port.out.AppendGuard
import com.openbank.ledger.application.port.out.AppendVerdict
import com.openbank.ledger.application.port.out.CloseParticipationRepository
import com.openbank.ledger.domain.model.AccountingDayStatus
import com.openbank.ledger.domain.model.CloseParticipationRecord
import com.openbank.ledger.domain.model.ParticipationOutcome
import com.openbank.ledger.infrastructure.persistence.entity.CloseParticipationEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class PanacheCloseParticipationRepository :
    CloseParticipationRepository,
    PanacheRepositoryBase<CloseParticipationEntity, UUID> {

    /**
     * Guard evaluation and insert in ONE transaction. Two locks make what the guard saw the state the
     * insert commits against: a transaction-scoped advisory lock keyed on `(day, participant)`
     * serialises concurrent reports and waivers for the same participant, and `FOR SHARE` on the
     * day row blocks a concurrent CUTOFF -> TIED_OUT (whose UPDATE needs the row exclusively) until
     * this transaction ends. Every row is new (fresh id, history never rewritten), so `persist` is
     * the right verb.
     */
    override suspend fun appendGuarded(record: CloseParticipationRecord, guard: AppendGuard): CloseParticipationRecord =
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(LOCK_SQL)
                    .setParameter("k", "close-participation:${record.businessDate}:${record.participant}")
                    .singleResult
                    .flatMap {
                        session.createNativeQuery<Any>(DAY_STATUS_SQL)
                            .setParameter("d", record.businessDate)
                            .resultList
                    }
                    .flatMap { statusRows ->
                        val dayStatus = statusRows.firstOrNull()?.let { AccountingDayStatus.valueOf(it.toString()) }
                        find(
                            "businessDate = ?1 and participant = ?2 order by reportedAt desc",
                            record.businessDate,
                            record.participant,
                        ).firstResult().flatMap { latestEntity ->
                            val latest = latestEntity?.toDomain()
                            when (val verdict = guard.check(latest, dayStatus)) {
                                AppendVerdict.Append -> persist(record.toEntity()).map { it.toDomain() }
                                AppendVerdict.KeepExisting ->
                                    Uni.createFrom().item(checkNotNull(latest) { "KeepExisting needs a latest row" })
                                is AppendVerdict.Reject -> Uni.createFrom().failure(verdict.error)
                            }
                        }
                    }
            }
        }.awaitSuspending()

    override suspend fun findForDate(businessDate: LocalDate): List<CloseParticipationRecord> = Panache.withSession {
        find("businessDate = ?1 order by reportedAt asc", businessDate).list()
    }.awaitSuspending().map { it.toDomain() }

    private fun CloseParticipationRecord.toEntity() = CloseParticipationEntity().also {
        it.businessDate = businessDate
        it.participant = participant
        it.outcome = outcome.name
        it.journalsPosted = journalsPosted
        it.itemsProcessed = itemsProcessed
        it.reason = reason
        it.reportedBy = reportedBy
        it.reportedAt = reportedAt
        it.late = late
    }

    private fun CloseParticipationEntity.toDomain() = CloseParticipationRecord(
        businessDate = businessDate,
        participant = participant,
        outcome = ParticipationOutcome.valueOf(outcome),
        journalsPosted = journalsPosted,
        itemsProcessed = itemsProcessed,
        reason = reason,
        reportedBy = reportedBy,
        reportedAt = reportedAt,
        late = late,
    )

    private companion object {
        /** Void-returning advisory lock wrapped so the driver gets a plain integer column back. */
        const val LOCK_SQL = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(:k))) AS l"
        const val DAY_STATUS_SQL = "SELECT status FROM ledger_accounting_day WHERE business_date = :d FOR SHARE"
    }
}
