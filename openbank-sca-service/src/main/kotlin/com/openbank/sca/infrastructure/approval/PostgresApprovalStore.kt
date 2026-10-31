// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.sca.infrastructure.approval

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.libs.approval.ApprovalLimitExceededException
import com.openbank.libs.approval.ApprovalRequestBinding
import com.openbank.libs.approval.ApprovalStatus
import com.openbank.libs.approval.ApprovalStore
import com.openbank.libs.approval.InvalidApprovalStateException
import com.openbank.libs.approval.MakerActorKind
import com.openbank.libs.approval.PendingApproval
import com.openbank.libs.approval.SelfApprovalNotAllowedException
import com.openbank.libs.domain.identifiers.Ids
import com.openbank.libs.persistence.outbox.OutboxMessage
import com.openbank.sca.infrastructure.persistence.entity.ScaOperatorApprovalEntity
import com.openbank.sca.infrastructure.persistence.repository.ScaOutboxRepositoryImpl
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Clock
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * SCA's maker/checker store (ADR-0155), durable in PostgreSQL instead of the fleet's
 * [com.openbank.libs.approval.impl.RedisApprovalStore]: every transition (create, decide, claim)
 * and its `SCA_OPERATOR_APPROVAL_CHANGED` outbox event commit in ONE transaction, so the identity of
 * the maker, of the deciding checker and of the claim cannot be lost while the authorization stands.
 *
 * Authorization expires at `expires_at`; the row and its evidence remain. Every check-and-write is
 * made under a row lock (`decide`/`markExecuted`) or a per-(action, maker) transaction-scoped
 * advisory lock (`create`), so the [ApprovalStore] contract's "exactly one of N concurrent callers
 * wins" and the per-maker pending bound hold across pods. The request binding (#11675) is stored
 * verbatim; an approval without one never satisfies an intercepted request.
 */
@ApplicationScoped
class PostgresApprovalStore(
    private val outbox: ScaOutboxRepositoryImpl,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    @ConfigProperty(name = MAX_PENDING_CONFIG_KEY, defaultValue = "20")
    private val maxPendingPerMakerAction: Int,
) : ApprovalStore,
    PanacheRepositoryBase<ScaOperatorApprovalEntity, UUID> {

    override suspend fun create(
        action: String,
        resourceId: String?,
        makerId: String,
        ttlSeconds: Long,
        binding: ApprovalRequestBinding?,
        makerActorKind: MakerActorKind,
    ): PendingApproval {
        require(ttlSeconds > 0) { "Approval TTL must be positive" }
        require(action.isNotBlank() && makerId.isNotBlank()) { "Approval action and maker are required" }
        return Panache.withTransaction {
            lockMakerAction(action, makerId).flatMap {
                val now = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)
                count(
                    "action = ?1 and makerId = ?2 and status = ?3 and expiresAt > ?4",
                    action,
                    makerId,
                    ApprovalStatus.PENDING,
                    now,
                ).flatMap { pending ->
                    if (pending >= maxPendingPerMakerAction) {
                        throw ApprovalLimitExceededException(action, maxPendingPerMakerAction)
                    }
                    val entity = ScaOperatorApprovalEntity().also {
                        it.id = Ids.newId()
                        it.action = action
                        it.resourceId = resourceId
                        it.makerId = makerId
                        it.makerActorKind = makerActorKind
                        it.status = ApprovalStatus.PENDING
                        it.createdAt = now
                        it.expiresAt = now.plusSeconds(ttlSeconds)
                        it.requestFingerprint = binding?.fingerprint
                        it.summary = binding?.summary
                    }
                    persistAndFlush(entity).flatMap { appendEvidence(entity, makerId, now) }
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
        val bounded = limit.coerceIn(1, MAX_PENDING_LIMIT)
        return Panache.withSession {
            find(
                "status = ?1 and expiresAt > ?2 order by createdAt, id",
                ApprovalStatus.PENDING,
                OffsetDateTime.now(clock),
            )
                .range<ScaOperatorApprovalEntity>(0, bounded - 1).list<ScaOperatorApprovalEntity>()
                .map { rows -> rows.map { it.toDomain() } }
        }.awaitSuspending()
    }

    override suspend fun decide(id: String, decidedBy: String, approve: Boolean): PendingApproval? =
        transition(id) { entity, now ->
            require(decidedBy.isNotBlank()) { "Checker is required" }
            // Self-approval before status, as the shared contract requires.
            if (entity.makerId == decidedBy) throw SelfApprovalNotAllowedException(entity.makerId)
            requireStatus(entity, ApprovalStatus.PENDING)
            entity.status = if (approve) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED
            entity.decidedBy = decidedBy
            entity.decidedAt = now
            decidedBy
        }

    override suspend fun markExecuted(id: String): PendingApproval? = transition(id) { entity, now ->
        requireStatus(entity, ApprovalStatus.APPROVED)
        entity.status = ApprovalStatus.EXECUTED
        entity.claimedAt = now
        entity.makerId
    }

    private suspend fun transition(
        id: String,
        change: (ScaOperatorApprovalEntity, OffsetDateTime) -> String,
    ): PendingApproval? {
        val key = parseId(id) ?: return null
        return Panache.withTransaction {
            findById(key, LockModeType.PESSIMISTIC_WRITE).flatMap { entity ->
                val now = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS)
                if (entity == null || !entity.expiresAt.isAfter(now)) {
                    Uni.createFrom().nullItem<PendingApproval>()
                } else {
                    val actor = change(entity, now)
                    flush().flatMap { appendEvidence(entity, actor, now) }
                }
            }
        }.awaitSuspending()
    }

    /** Serialises concurrent creates of one maker for one action until the transaction ends. */
    private fun lockMakerAction(action: String, makerId: String): Uni<Any?> = Panache.getSession().flatMap { session ->
        session.createNativeQuery<Any>(
            "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?1, 0))) AS locked",
        ).setParameter(1, "sca-approval|$action|$makerId").singleResult
    }

    private fun appendEvidence(
        entity: ScaOperatorApprovalEntity,
        actor: String,
        occurredAt: OffsetDateTime,
    ): Uni<PendingApproval> {
        val eventId = Ids.newId()
        val payload = mapOf(
            "eventId" to eventId.toString(),
            "eventType" to "SCA_OPERATOR_APPROVAL_CHANGED",
            "schemaVersion" to 2,
            "sourceService" to "sca-service",
            "aggregateType" to "SCA_OPERATOR_APPROVAL",
            "aggregateId" to entity.id.toString(),
            "actorId" to actor,
            "actorType" to "AUTHENTICATED_PRINCIPAL",
            "action" to entity.action,
            "resourceId" to entity.resourceId,
            "makerId" to entity.makerId,
            "makerActorKind" to entity.makerActorKind.name,
            "status" to entity.status.name,
            "requestFingerprint" to entity.requestFingerprint,
            "createdAt" to entity.createdAt.toString(),
            "expiresAt" to entity.expiresAt.toString(),
            "decidedBy" to entity.decidedBy,
            "decidedAt" to entity.decidedAt?.toString(),
            "claimedAt" to entity.claimedAt?.toString(),
            "occurredAt" to occurredAt.toString(),
        )
        return outbox.persistInTransaction(
            OutboxMessage(
                eventId = eventId,
                aggregateId = entity.id,
                eventType = "SCA_OPERATOR_APPROVAL_CHANGED",
                payload = mapper.writeValueAsString(payload),
            ),
        ).replaceWith(entity.toDomain())
    }

    private fun requireStatus(entity: ScaOperatorApprovalEntity, expected: ApprovalStatus) {
        if (entity.status != expected) {
            throw InvalidApprovalStateException(entity.id.toString(), expected, entity.status)
        }
    }

    private fun parseId(id: String): UUID? = runCatching { UUID.fromString(id) }.getOrNull()

    companion object {
        const val MAX_PENDING_CONFIG_KEY = "openbank.approval.max-pending-per-maker-action"
        private const val MAX_PENDING_LIMIT = 200
    }
}
