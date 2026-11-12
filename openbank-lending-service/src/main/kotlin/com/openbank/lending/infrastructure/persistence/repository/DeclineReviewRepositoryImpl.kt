// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.repository

import com.openbank.lending.application.port.out.DeclineReviewRepository
import com.openbank.lending.domain.model.DeclineReview
import com.openbank.lending.domain.model.DeclineReviewStatus
import com.openbank.lending.infrastructure.persistence.entity.DeclineReviewEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase
import io.quarkus.hibernate.reactive.panache.common.WithSession
import io.quarkus.hibernate.reactive.panache.common.WithTransaction
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import java.time.OffsetDateTime
import java.util.UUID

@ApplicationScoped
class DeclineReviewRepositoryImpl :
    DeclineReviewRepository,
    PanacheRepositoryBase<DeclineReviewEntity, UUID> {

    /**
     * `persist` is right here and only here: the row is always NEW (the id is minted by the service,
     * and every later change goes through [claimDecision]'s conditional UPDATE, never a re-save), so
     * the assigned-id persist-vs-merge trap does not apply. A second insert for the same application
     * is the unique constraint doing its job and the service reads that as "already requested".
     */
    @WithTransaction
    override fun insert(review: DeclineReview): Uni<DeclineReview> {
        val entity = toEntity(review)
        return persist(entity).map { toDomain(entity) }
    }

    @WithSession
    override fun loadById(id: UUID): Uni<DeclineReview?> =
        find("id", id).firstResult<DeclineReviewEntity>().map { it?.let(::toDomain) }

    @WithSession
    override fun loadByApplication(applicationId: UUID): Uni<DeclineReview?> =
        find("applicationId", applicationId).firstResult<DeclineReviewEntity>().map { it?.let(::toDomain) }

    @WithSession
    override fun listPending(limit: Int): Uni<List<DeclineReview>> =
        find("status = ?1 order by requestedAt, id", DeclineReviewStatus.REQUESTED)
            .page<DeclineReviewEntity>(0, limit).list<DeclineReviewEntity>().map { rows -> rows.map(::toDomain) }

    @WithTransaction
    override fun claimDecision(
        id: UUID,
        to: DeclineReviewStatus,
        reviewedBy: String,
        reviewedAt: OffsetDateTime,
        reason: String,
        successorApplicationId: UUID?,
    ): Uni<Int> = Panache.getSession().flatMap { session ->
        // Flush first: the caller has just persisted the successor application in this same
        // transaction, and Hibernate does not auto-flush it before an UPDATE on another table, so the
        // foreign key on successor_application_id would otherwise see a row that is not there yet.
        session.flush().flatMap {
            session.createMutationQuery(CLAIM_HQL)
                .setParameter("to", to)
                .setParameter("by", reviewedBy)
                .setParameter("at", reviewedAt)
                .setParameter("reason", reason)
                .setParameter("successor", successorApplicationId)
                .setParameter("id", id)
                .setParameter("from", DeclineReviewStatus.REQUESTED)
                .executeUpdate()
        }
    }

    private fun toEntity(r: DeclineReview) = DeclineReviewEntity().also {
        it.id = r.id
        it.applicationId = r.applicationId
        it.partyId = r.partyId
        it.requestedAt = r.requestedAt
        it.engineOutcome = r.engineOutcome
        it.engineReasons = r.engineReasons
        it.policyVersions = r.policyVersions
        it.engineDecidedAt = r.engineDecidedAt
        it.status = r.status
        it.reviewedBy = r.reviewedBy
        it.reviewedAt = r.reviewedAt
        it.reviewReason = r.reviewReason
        it.successorApplicationId = r.successorApplicationId
    }

    private fun toDomain(e: DeclineReviewEntity) = DeclineReview(
        id = e.id,
        applicationId = e.applicationId,
        partyId = e.partyId,
        requestedAt = e.requestedAt,
        engineOutcome = e.engineOutcome,
        engineReasons = e.engineReasons,
        policyVersions = e.policyVersions,
        engineDecidedAt = e.engineDecidedAt,
        status = e.status,
        reviewedBy = e.reviewedBy,
        reviewedAt = e.reviewedAt,
        reviewReason = e.reviewReason,
        successorApplicationId = e.successorApplicationId,
    )

    private companion object {
        /** The `and status = :from` is the whole point: without it this is a blind overwrite. */
        const val CLAIM_HQL =
            "update DeclineReviewEntity set status = :to, reviewedBy = :by, reviewedAt = :at, " +
                "reviewReason = :reason, successorApplicationId = :successor " +
                "where id = :id and status = :from"
    }
}
