// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
package com.openbank.anacredit.infrastructure.persistence.repository

import com.openbank.anacredit.application.port.out.LoanStageProjectionRepository
import com.openbank.anacredit.domain.model.LoanStageProjection
import com.openbank.anacredit.infrastructure.persistence.entity.LoanStageProjectionEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

/**
 * Reactive-Panache backing for [LoanStageProjectionRepository] (ADR-0037 event-ingestion follow-up,
 * issue #638). [applyIfNewer] is the sole write path and is the idempotency/ordering guard: it holds
 * the existing row (if any) for the loan, compares `eventTimestamp`, and only mutates/persists when the
 * incoming event is strictly newer — so an out-of-order or redelivered `loan.stage_changed` can never
 * regress the projection.
 */
@ApplicationScoped
class LoanStageProjectionRepositoryImpl :
    LoanStageProjectionRepository,
    PanacheRepository<LoanStageProjectionEntity> {

    override suspend fun findByLoanId(loanId: UUID): LoanStageProjection? = Panache.withSession {
        find("loanId", loanId).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun applyIfNewer(projection: LoanStageProjection): Boolean = Panache.withTransaction {
        find("loanId", projection.loanId).firstResult().flatMap { existing ->
            when {
                existing == null -> {
                    val entity = LoanStageProjectionEntity().also {
                        it.loanId = projection.loanId
                        it.stage = projection.stage
                        it.daysPastDue = projection.daysPastDue
                        it.eventTimestamp = projection.eventTimestamp
                        it.updatedAt = projection.updatedAt
                    }
                    persist(entity).map { true }
                }
                existing.eventTimestamp.isBefore(projection.eventTimestamp) -> {
                    existing.stage = projection.stage
                    existing.daysPastDue = projection.daysPastDue
                    existing.eventTimestamp = projection.eventTimestamp
                    existing.updatedAt = projection.updatedAt
                    Uni.createFrom().item(true)
                }
                else -> {
                    // Existing row is equally-or-more recent: stale/duplicate delivery, no-op.
                    Uni.createFrom().item(false)
                }
            }
        }
    }.awaitSuspending()

    private fun LoanStageProjectionEntity.toDomain() = LoanStageProjection(
        loanId = loanId,
        stage = stage,
        daysPastDue = daysPastDue,
        eventTimestamp = eventTimestamp,
        updatedAt = updatedAt,
    )
}
