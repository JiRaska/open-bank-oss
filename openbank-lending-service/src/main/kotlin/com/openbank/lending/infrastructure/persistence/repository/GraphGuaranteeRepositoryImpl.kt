// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.lending.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.lending.application.port.out.GraphGuaranteeRepository
import com.openbank.lending.domain.model.GraphGuaranteeFact
import com.openbank.lending.domain.model.GraphGuaranteeProposal
import com.openbank.lending.domain.model.GraphGuaranteeStatus
import com.openbank.lending.infrastructure.persistence.entity.GraphGuaranteeEntity
import com.openbank.lending.infrastructure.persistence.entity.LendingOutboxEntity
import com.openbank.lending.infrastructure.persistence.entity.LoanEntity
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxStatus
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class GraphGuaranteeRepositoryImpl(
    private val sessions: Mutiny.SessionFactory,
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "openbank.lending.graph.bank-scope") private val bankScope: String,
) : GraphGuaranteeRepository {
    override suspend fun propose(proposal: GraphGuaranteeProposal, actor: String, at: Instant): GraphGuaranteeFact =
        sessions.withTransaction { session ->
            session.find(LoanEntity::class.java, proposal.loanId).flatMap { loan ->
                if (loan == null) {
                    Uni.createFrom().failure(IllegalArgumentException("loan does not exist"))
                } else {
                    val entity = GraphGuaranteeEntity.pending(proposal, actor, at)
                    session.persist(entity).replaceWith(entity.toFact())
                }
            }
        }.awaitSuspending()

    override suspend fun find(guaranteeId: UUID): GraphGuaranteeFact? = sessions.withSession { session ->
        session.find(GraphGuaranteeEntity::class.java, guaranteeId).map { it?.toFact() }
    }.awaitSuspending()

    override suspend fun findApprovedForLoan(
        loanId: UUID,
        effectiveAt: Instant,
        knownAt: Instant,
        limit: Int,
    ): List<GraphGuaranteeFact> {
        require(limit in 1..MAX_APPROVED_GRAPH_FACTS) { "limit must be between 1 and $MAX_APPROVED_GRAPH_FACTS" }
        return sessions.withSession { session ->
            session.createQuery(
                """
                    FROM GraphGuaranteeEntity
                    WHERE loanId = :loanId
                      AND status = :approved
                      AND validFrom <= :effectiveAt
                      AND (validTo IS NULL OR validTo > :effectiveAt)
                      AND decidedAt <= :knownAt
                    ORDER BY contractId ASC, revision ASC, guaranteeId ASC
                """.trimIndent(),
                GraphGuaranteeEntity::class.java,
            )
                .setParameter("loanId", loanId)
                .setParameter("approved", GraphGuaranteeStatus.APPROVED.name)
                .setParameter("effectiveAt", effectiveAt)
                .setParameter("knownAt", knownAt)
                .setMaxResults(limit + 1)
                .resultList
        }.map { entities -> entities.map { it.toFact() } }.awaitSuspending()
    }

    override suspend fun findApprovedForLoanAndGuarantors(
        loanId: UUID,
        guarantorPartyIds: Set<UUID>,
        effectiveAt: Instant,
        knownAt: Instant,
        limit: Int,
    ): List<GraphGuaranteeFact> {
        require(guarantorPartyIds.isNotEmpty() && guarantorPartyIds.size <= MAX_APPROVED_GRAPH_FACTS)
        require(limit in 1..MAX_APPROVED_GRAPH_FACTS)
        return sessions.withSession { session ->
            session.createQuery(
                """
                    FROM GraphGuaranteeEntity
                    WHERE loanId = :loanId
                      AND guarantorPartyId IN :guarantorPartyIds
                      AND status = :approved
                      AND validFrom <= :effectiveAt
                      AND (validTo IS NULL OR validTo > :effectiveAt)
                      AND decidedAt <= :knownAt
                    ORDER BY contractId ASC, revision ASC, guaranteeId ASC
                """.trimIndent(),
                GraphGuaranteeEntity::class.java,
            )
                .setParameter("loanId", loanId)
                .setParameter("guarantorPartyIds", guarantorPartyIds)
                .setParameter("approved", GraphGuaranteeStatus.APPROVED.name)
                .setParameter("effectiveAt", effectiveAt)
                .setParameter("knownAt", knownAt)
                .setMaxResults(limit + 1)
                .resultList
        }.map { entities -> entities.map { it.toFact() } }.awaitSuspending()
    }

    override suspend fun findSharedCandidateLoanIds(
        candidateLoanIds: List<UUID>,
        guarantorPartyIds: Set<UUID>,
        effectiveAt: Instant,
        knownAt: Instant,
        limit: Int,
    ): List<UUID> {
        require(candidateLoanIds.isNotEmpty() && candidateLoanIds.size <= MAX_CANDIDATES)
        require(guarantorPartyIds.isNotEmpty() && guarantorPartyIds.size <= MAX_APPROVED_GRAPH_FACTS)
        require(limit in 1..MAX_APPROVED_GRAPH_FACTS)
        return sessions.withSession { session ->
            session.createQuery(
                """
                    SELECT DISTINCT g.loanId FROM GraphGuaranteeEntity g
                    WHERE g.loanId IN :candidateLoanIds
                      AND g.guarantorPartyId IN :guarantorPartyIds
                      AND g.status = :approved
                      AND g.validFrom <= :effectiveAt
                      AND (g.validTo IS NULL OR g.validTo > :effectiveAt)
                      AND g.decidedAt <= :knownAt
                    ORDER BY g.loanId ASC
                """.trimIndent(),
                UUID::class.java,
            )
                .setParameter("candidateLoanIds", candidateLoanIds)
                .setParameter("guarantorPartyIds", guarantorPartyIds)
                .setParameter("approved", GraphGuaranteeStatus.APPROVED.name)
                .setParameter("effectiveAt", effectiveAt)
                .setParameter("knownAt", knownAt)
                .setMaxResults(limit)
                .resultList
        }.awaitSuspending()
    }

    override suspend fun decide(
        guaranteeId: UUID,
        decision: GraphGuaranteeStatus,
        actor: String,
        at: Instant,
    ): GraphGuaranteeFact = sessions.withTransaction { session ->
        session.createQuery("FROM GraphGuaranteeEntity WHERE guaranteeId = :id", GraphGuaranteeEntity::class.java)
            .setParameter("id", guaranteeId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .singleResultOrNull
            .flatMap { entity ->
                requireNotNull(entity) { "guarantee does not exist" }
                require(entity.status == GraphGuaranteeStatus.PENDING.name) { "guarantee is already decided" }
                require(actor != entity.proposedBy) { "maker cannot decide own guarantee" }
                require(decision != GraphGuaranteeStatus.PENDING) { "a decision is required" }
                entity.status = decision.name
                entity.decidedBy = actor
                entity.decidedAt = at
                if (decision == GraphGuaranteeStatus.APPROVED) {
                    session.persist(approvedReference(entity, at)).replaceWith(entity.toFact())
                } else {
                    Uni.createFrom().item(entity.toFact())
                }
            }
    }.awaitSuspending()

    /** This outbox row commits atomically with approval. It is only a pointer, never the contract. */
    private fun approvedReference(entity: GraphGuaranteeEntity, at: Instant) = LendingOutboxEntity().also { outbox ->
        check(bankScope.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))) { "invalid deployment bank scope" }
        outbox.eventId = Ids.newId()
        outbox.aggregateId = entity.contractId
        outbox.eventType = APPROVED_EVENT_TYPE
        outbox.payload = mapper.writeValueAsString(
            GuaranteeApprovedReference(
                schemaVersion = 1,
                eventType = APPROVED_EVENT_TYPE,
                guaranteeId = entity.guaranteeId,
                loanId = entity.loanId,
                revision = entity.revision,
                bankScope = bankScope,
                occurredAt = at,
            ),
        )
        outbox.status = OutboxStatus.PENDING.name
        outbox.createdAt = at
        outbox.updatedAt = at
    }

    private data class GuaranteeApprovedReference(
        val schemaVersion: Int,
        val eventType: String,
        val guaranteeId: UUID,
        val loanId: UUID,
        val revision: Long,
        val bankScope: String,
        val occurredAt: Instant,
    )

    private companion object {
        const val APPROVED_EVENT_TYPE = "lending.graph.guarantee.approved"
        const val MAX_APPROVED_GRAPH_FACTS = 100
        const val MAX_CANDIDATES = 256
    }
}
