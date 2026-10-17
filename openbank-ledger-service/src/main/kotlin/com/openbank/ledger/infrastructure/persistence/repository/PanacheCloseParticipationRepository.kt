// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.ledger.infrastructure.persistence.repository

import com.openbank.ledger.application.port.out.CloseParticipationRepository
import com.openbank.ledger.domain.model.CloseParticipationRecord
import com.openbank.ledger.domain.model.ParticipationOutcome
import com.openbank.ledger.infrastructure.persistence.entity.CloseParticipationEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.time.LocalDate
import java.util.UUID

@ApplicationScoped
class PanacheCloseParticipationRepository :
    CloseParticipationRepository,
    PanacheRepositoryBase<CloseParticipationEntity, UUID> {

    /** Every row is new (fresh id, history never rewritten), so `persist` is the right verb. */
    override suspend fun append(record: CloseParticipationRecord): CloseParticipationRecord =
        Panache.withTransaction { persist(record.toEntity()) }.awaitSuspending().toDomain()

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
}
