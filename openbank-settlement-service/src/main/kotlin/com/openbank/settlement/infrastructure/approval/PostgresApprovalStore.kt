// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package com.openbank.settlement.infrastructure.approval

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.settlement.infrastructure.persistence.SettlementOutboxRepositoryImpl
import com.openbank.settlement.infrastructure.persistence.entity.SettlementOperatorApprovalEntity
import com.openbank.settlement.infrastructure.persistence.entity.SettlementOperatorProposalEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/** Authorization expires; the recorded maker/checker facts and audit evidence remain. */
@ApplicationScoped
class PostgresApprovalStore(
    private val outbox: SettlementOutboxRepositoryImpl,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    private val proposals: SettlementProposalStore,
) : ApprovalStore,
    PanacheRepositoryBase<SettlementOperatorApprovalEntity, UUID> {
    override suspend fun create(
        action: String,
        resourceId: String?,
        makerId: String,
        ttlSeconds: Long,
    ): PendingApproval {
        require(action == "settlement.create" && resourceId?.matches(Regex("[0-9a-f]{64}")) == true) {
            "a complete settlement instruction binding is required"
        }
        require(ttlSeconds > 0) { "Approval TTL must be positive" }
        require(action.isNotBlank() && makerId.isNotBlank()) { "Approval action and maker are required" }
        val now = OffsetDateTime.now(clock)
        val entity = SettlementOperatorApprovalEntity().also {
            it.id = Ids.newId()
            it.action = action
            it.resourceId = resourceId
            it.makerId = makerId
            it.status = ApprovalStatus.PENDING
            it.createdAt = now
            it.expiresAt = now.plusSeconds(ttlSeconds)
        }
        return Panache.withTransaction {
            proposals.findForMaker(makerId, requireNotNull(resourceId)).flatMap { proposal ->
                checkNotNull(proposal) { "A durable reviewed instruction is required" }
                check(proposal.instruction().approvalFingerprint == resourceId) { "Proposal binding is invalid" }
                entity.proposalId = proposal.id
                persistAndFlush(entity).flatMap { appendEvidence(entity, makerId, now) }
            }
        }.awaitSuspending()
    }

    suspend fun proposalForApproval(id: String): SettlementOperatorProposalEntity? {
        val key = parseId(id) ?: return null
        return Panache.withSession {
            findById(key).flatMap { approval ->
                val proposalId = approval?.proposalId
                if (approval == null || proposalId == null || !approval.expiresAt.isAfter(OffsetDateTime.now(clock))) {
                    Uni.createFrom().nullItem<SettlementOperatorProposalEntity>()
                } else {
                    proposals.findBound(approval)
                }
            }
        }.awaitSuspending()
    }

    override suspend fun find(id: String): PendingApproval? {
        val key = parseId(id) ?: return null
        return Panache.withSession {
            findById(key).map { it?.takeIf { row -> row.expiresAt.isAfter(OffsetDateTime.now(clock)) }?.toDomain() }
        }.awaitSuspending()
    }

    override suspend fun findPending(limit: Int): List<PendingApproval> {
        require(limit in 1..MAX_PENDING_LIMIT) { "Approval limit must be between 1 and 200" }
        return Panache.withSession {
            find(
                "status = ?1 and expiresAt > ?2 order by createdAt, id",
                ApprovalStatus.PENDING,
                OffsetDateTime.now(clock),
            )
                .range<SettlementOperatorApprovalEntity>(0, limit - 1).list<SettlementOperatorApprovalEntity>()
                .map { rows -> rows.map { it.toDomain() } }
        }.awaitSuspending()
    }

    override suspend fun decide(id: String, decidedBy: String, approve: Boolean): PendingApproval? =
        transition(id) { entity, now ->
            require(decidedBy.isNotBlank()) { "Checker is required" }
            if (entity.makerId == decidedBy) throw SelfApprovalNotAllowedException(entity.makerId)
            requireStatus(entity, ApprovalStatus.PENDING)
            if (approve) {
                requireNotNull(entity.proposalId) {
                    "Legacy approval has no reviewable instruction; reject it and resubmit"
                }
            }
            entity.status = if (approve) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED
            entity.decidedBy = decidedBy
            entity.decidedAt = now
            decidedBy
        }

    override suspend fun markExecuted(id: String): PendingApproval? = transition(id) { entity, now ->
        requireStatus(entity, ApprovalStatus.APPROVED)
        requireNotNull(entity.proposalId) { "Legacy approval has no reviewable instruction; resubmit the operation" }
        entity.status = ApprovalStatus.EXECUTED
        entity.claimedAt = now
        entity.makerId
    }

    private suspend fun transition(
        id: String,
        change: (SettlementOperatorApprovalEntity, OffsetDateTime) -> String,
    ): PendingApproval? {
        val key = parseId(id) ?: return null
        return Panache.withTransaction {
            findById(key, LockModeType.PESSIMISTIC_WRITE).flatMap { entity ->
                val now = OffsetDateTime.now(clock)
                if (entity == null || !entity.expiresAt.isAfter(now)) {
                    Uni.createFrom().nullItem<PendingApproval>()
                } else {
                    val actor = change(entity, now)
                    flush().flatMap { appendEvidence(entity, actor, now) }
                }
            }
        }.awaitSuspending()
    }

    private fun appendEvidence(
        entity: SettlementOperatorApprovalEntity,
        actor: String,
        occurredAt: OffsetDateTime,
    ): Uni<PendingApproval> = Panache.getSession().flatMap { it.refresh(entity) }.flatMap {
        val eventId = Ids.newId()
        outbox.persistInTransaction(
            OutboxMessage(
                eventId = eventId,
                aggregateId = entity.id,
                eventType = "SETTLEMENT_OPERATOR_APPROVAL_CHANGED",
                payload = mapper.writeValueAsString(
                    mapOf(
                        "eventId" to eventId.toString(),
                        "eventType" to "SETTLEMENT_OPERATOR_APPROVAL_CHANGED",
                        "schemaVersion" to 1,
                        "sourceService" to "settlement-service",
                        "aggregateType" to "SETTLEMENT_OPERATOR_APPROVAL",
                        "aggregateId" to entity.id.toString(),
                        "actorId" to actor,
                        "actorType" to "AUTHENTICATED_PRINCIPAL",
                        "action" to entity.action,
                        "resourceId" to entity.resourceId,
                        "makerId" to entity.makerId,
                        "status" to entity.status.name,
                        "createdAt" to entity.createdAt.toString(),
                        "expiresAt" to entity.expiresAt.toString(),
                        "decidedBy" to entity.decidedBy,
                        "decidedAt" to entity.decidedAt?.toString(),
                        "claimedAt" to entity.claimedAt?.toString(),
                        "occurredAt" to occurredAt.toString(),
                    ),
                ),
            ),
        ).replaceWith(entity.toDomain())
    }

    private fun requireStatus(entity: SettlementOperatorApprovalEntity, expected: ApprovalStatus) {
        if (entity.status != expected) {
            throw InvalidApprovalStateException(entity.id.toString(), expected, entity.status)
        }
    }

    private fun parseId(id: String): UUID? = runCatching { UUID.fromString(id) }.getOrNull()

    private companion object {
        const val MAX_PENDING_LIMIT = 200
    }
}
