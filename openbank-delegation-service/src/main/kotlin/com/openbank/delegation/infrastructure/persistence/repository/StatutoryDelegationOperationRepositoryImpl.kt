// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.StatutoryDelegationCommit
import com.openbank.delegation.application.port.out.StatutoryDelegationDecision
import com.openbank.delegation.application.port.out.StatutoryDelegationOperationRepository
import com.openbank.delegation.domain.model.ApprovalSignature
import com.openbank.delegation.domain.model.DelegationGrant
import com.openbank.delegation.domain.model.DelegationStatus
import com.openbank.delegation.domain.model.StatutoryDelegationAction
import com.openbank.delegation.domain.model.StatutoryDelegationOperation
import com.openbank.delegation.domain.model.StatutoryDelegationRule
import com.openbank.delegation.domain.model.StatutoryDelegationStatus
import com.openbank.delegation.infrastructure.persistence.entity.DelegationGrantEntity
import com.openbank.delegation.infrastructure.persistence.entity.StatutoryDelegationOperationEntity
import com.openbank.delegation.infrastructure.persistence.entity.StatutoryDelegationSignatureEntity
import com.openbank.libs.idempotency.IdempotencyKeyReusedException
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny
import java.util.UUID

@ApplicationScoped
@Suppress("TooManyFunctions") // Each query shares the same atomic operation, grant and evidence boundary.
class StatutoryDelegationOperationRepositoryImpl(
    private val grantRepository: DelegationRepositoryImpl,
    private val mapper: ObjectMapper,
) : StatutoryDelegationOperationRepository {

    override suspend fun create(
        requestKey: String,
        operation: StatutoryDelegationOperation,
    ): StatutoryDelegationOperation {
        require(requestKey.isNotBlank() && requestKey.length <= MAX_REQUEST_KEY) { "invalid request key" }
        require(operation.status == StatutoryDelegationStatus.COLLECTING && operation.signatures.isEmpty()) {
            "a new operation cannot carry decisions"
        }
        return Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                insert(session, requestKey, operation)
                    .flatMap {
                        findByRequestKey(
                            session,
                            operation.rule.principalPartyId,
                            operation.initiatorPartyId,
                            operation.action,
                            requestKey,
                        )
                    }
                    .flatMap { row ->
                        val stored = requireNotNull(row) { "statutory request row was not persisted" }
                        val sameTarget = operation.action == StatutoryDelegationAction.ISSUE ||
                            stored.delegationId == operation.delegationId
                        if (!sameTarget || stored.frozenPayload != operation.frozenPayload) {
                            throw IdempotencyKeyReusedException()
                        }
                        readOperation(session, stored)
                    }
            }
        }.awaitSuspending()
    }

    override suspend fun find(id: UUID): StatutoryDelegationOperation? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.find(StatutoryDelegationOperationEntity::class.java, id)
                .flatMap { row ->
                    if (row == null) Uni.createFrom().nullItem() else readOperation(session, row)
                }
        }
    }.awaitSuspending()

    override suspend fun findByRequest(
        principalPartyId: UUID,
        actorPartyId: UUID,
        action: StatutoryDelegationAction,
        requestKey: String,
    ): StatutoryDelegationOperation? = Panache.withSession {
        Panache.getSession().flatMap { session ->
            findByRequestKey(session, principalPartyId, actorPartyId, action, requestKey).flatMap { row ->
                if (row == null) Uni.createFrom().nullItem() else readOperation(session, row)
            }
        }
    }.awaitSuspending()

    override suspend fun pending(
        principalPartyId: UUID,
        actorPartyId: UUID,
        limit: Int,
    ): List<StatutoryDelegationOperation> = Panache.withSession {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery(PENDING_SQL, StatutoryDelegationOperationEntity::class.java)
                .setParameter("principal", principalPartyId)
                .setParameter("actor", actorPartyId.toString())
                .setMaxResults(limit.coerceIn(1, MAX_PENDING))
                .resultList
                .flatMap { rows ->
                    // A reactive Hibernate session cannot drive multiple queries at once.
                    rows.fold(Uni.createFrom().item(emptyList<StatutoryDelegationOperation>())) { reads, row ->
                        reads.flatMap { operations -> readOperation(session, row).map { operations + it } }
                    }
                }
        }
    }.awaitSuspending()

    override suspend fun decideAtomically(
        id: UUID,
        decide: (StatutoryDelegationOperation, DelegationGrant?) -> StatutoryDelegationDecision,
    ): StatutoryDelegationOperation? = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.createNativeQuery(LOCK_OPERATION_SQL, StatutoryDelegationOperationEntity::class.java)
                .setParameter("id", id)
                .resultList
                .flatMap { rows ->
                    val row = rows.firstOrNull() ?: return@flatMap Uni.createFrom().nullItem()
                    readOperation(session, row).flatMap { current ->
                        lockGrant(session, current.delegationId).flatMap { grant ->
                            persistDecision(session, row, current, grant, decide(current, grant))
                        }
                    }
                }
        }
    }.awaitSuspending()

    private fun persistDecision(
        session: Mutiny.Session,
        row: StatutoryDelegationOperationEntity,
        current: StatutoryDelegationOperation,
        lockedGrant: DelegationGrant?,
        decision: StatutoryDelegationDecision,
    ): Uni<StatutoryDelegationOperation> {
        val next = decision.operation
        val frozen = next.copy(signatures = current.signatures, status = current.status, closedBy = current.closedBy)
        require(
            frozen == current &&
                next.signatures.take(current.signatures.size) == current.signatures &&
                next.signatures.size <= current.signatures.size + 1,
        ) { "statutory decision must preserve frozen terms and append at most one signature" }
        if (next == current) {
            require(decision.commit == null) { "a replay cannot write a delegation" }
            return Uni.createFrom().item(current)
        }
        require(current.status == StatutoryDelegationStatus.COLLECTING) { "statutory operation is already closed" }
        require((next.status == StatutoryDelegationStatus.COMMITTED) == (decision.commit != null)) {
            "operation commitment and delegation transition must be atomic"
        }
        val newSignature = next.signatures.getOrNull(current.signatures.size)
        val signatureWrite = if (newSignature == null) {
            Uni.createFrom().voidItem()
        } else {
            session.persist(
                StatutoryDelegationSignatureEntity().also {
                    it.operationId = current.id
                    it.actorPartyId = newSignature.partyId
                    it.scaChallengeId = newSignature.scaChallengeId
                    it.signedAt = newSignature.at
                },
            )
        }
        return signatureWrite.flatMap {
            val commit = decision.commit ?: return@flatMap Uni.createFrom().item(next)
            requireCommitMatches(current, lockedGrant, commit)
            grantRepository.saveInTransaction(commit.grant, commit.event).replaceWith(next)
        }.flatMap {
            row.status = next.status.name
            row.closedBy = next.closedBy
            session.flush().replaceWith(next)
        }
    }

    private fun requireCommitMatches(
        operation: StatutoryDelegationOperation,
        locked: DelegationGrant?,
        commit: StatutoryDelegationCommit,
    ) {
        val grant = commit.grant
        require(grant.id == operation.delegationId && commit.event.aggregateId == operation.delegationId) {
            "statutory decision must commit its own delegation"
        }
        val matches = when (operation.action) {
            StatutoryDelegationAction.ISSUE -> {
                val initialOffer = grant.status == DelegationStatus.OFFERED && grant.lifecycleRevision == 0L
                val newOffer = locked == null && initialOffer
                val provenIssuer = grant.grantorPartyId == operation.rule.principalPartyId &&
                    grant.grantorStatutoryOperationId == operation.id
                newOffer && provenIssuer
            }

            StatutoryDelegationAction.ACCEPT -> {
                val offered = locked?.takeIf {
                    it.status == DelegationStatus.OFFERED &&
                        it.lifecycleRevision == operation.expectedLifecycleRevision
                }
                offered != null &&
                    grant.status == DelegationStatus.ACTIVE &&
                    grant.granteePartyId == operation.rule.principalPartyId &&
                    grant.granteeStatutoryOperationId == operation.id &&
                    grant.lifecycleRevision == Math.addExact(offered.lifecycleRevision, 1)
            }
        }
        require(matches) { "delegation state changed before statutory commitment" }
    }

    private fun insert(
        session: Mutiny.Session,
        requestKey: String,
        operation: StatutoryDelegationOperation,
    ): Uni<Int> = session.createNativeQuery<Any>(INSERT_SQL)
        .setParameter("id", operation.id)
        .setParameter("action", operation.action.name)
        .setParameter("delegation", operation.delegationId)
        .setParameter("revision", operation.expectedLifecycleRevision)
        .setParameter("principal", operation.rule.principalPartyId)
        .setParameter("initiator", operation.initiatorPartyId)
        .setParameter("key", requestKey)
        .setParameter("rule", mapper.writeValueAsString(operation.rule))
        .setParameter("payload", operation.frozenPayload)
        .setParameter("created", operation.createdAt)
        .setParameter("expires", operation.expiresAt)
        .executeUpdate()

    private fun findByRequestKey(
        session: Mutiny.Session,
        principalPartyId: UUID,
        actorPartyId: UUID,
        action: StatutoryDelegationAction,
        requestKey: String,
    ): Uni<StatutoryDelegationOperationEntity?> = session.createQuery(
        "from StatutoryDelegationOperationEntity where principalPartyId = :principal " +
            "and initiatorPartyId = :initiator and action = :action and requestKey = :key",
        StatutoryDelegationOperationEntity::class.java,
    ).setParameter("principal", principalPartyId)
        .setParameter("initiator", actorPartyId)
        .setParameter("action", action.name)
        .setParameter("key", requestKey)
        .resultList
        .map { it.singleOrNull() }

    private fun readOperation(
        session: Mutiny.Session,
        row: StatutoryDelegationOperationEntity,
    ): Uni<StatutoryDelegationOperation> = session.createQuery(
        "from StatutoryDelegationSignatureEntity where operationId = :id order by signedAt, actorPartyId",
        StatutoryDelegationSignatureEntity::class.java,
    ).setParameter("id", row.id).resultList.map { signatures ->
        StatutoryDelegationOperation(
            id = row.id,
            action = StatutoryDelegationAction.valueOf(row.action),
            delegationId = row.delegationId,
            expectedLifecycleRevision = row.expectedLifecycleRevision,
            initiatorPartyId = row.initiatorPartyId,
            rule = mapper.readValue(row.ruleSnapshot, StatutoryDelegationRule::class.java),
            frozenPayload = row.frozenPayload,
            createdAt = row.createdAt,
            expiresAt = row.expiresAt,
            signatures = signatures.map { ApprovalSignature(it.actorPartyId, it.scaChallengeId, it.signedAt) },
            status = StatutoryDelegationStatus.valueOf(row.status),
            closedBy = row.closedBy,
        )
    }

    private fun lockGrant(session: Mutiny.Session, id: UUID): Uni<DelegationGrant?> = session
        .createNativeQuery(LOCK_GRANT_SQL, DelegationGrantEntity::class.java)
        .setParameter("id", id)
        .resultList
        .map { it.firstOrNull()?.toDomain() }

    private companion object {
        const val MAX_REQUEST_KEY = 128
        const val MAX_PENDING = 50
        const val LOCK_OPERATION_SQL = "SELECT * FROM statutory_delegation_operations WHERE id = :id FOR UPDATE"
        const val LOCK_GRANT_SQL = "SELECT * FROM delegation_grants WHERE id = :id FOR UPDATE"

        const val PENDING_SQL = """
            SELECT op.* FROM statutory_delegation_operations op
            WHERE op.principal_party_id = :principal AND op.status = 'COLLECTING'
              AND op.expires_at > CURRENT_TIMESTAMP
              AND CAST(op.rule_snapshot AS jsonb) -> 'mandatesByRepresentative' ->> CAST(:actor AS text) IS NOT NULL
              AND (op.action = 'ISSUE' OR EXISTS (
                  SELECT 1 FROM delegation_grants grant_row WHERE grant_row.id = op.delegation_id
                    AND grant_row.status = 'OFFERED' AND grant_row.lifecycle_revision = op.expected_lifecycle_revision
              ))
            ORDER BY op.expires_at, op.id
        """

        const val INSERT_SQL = """
            INSERT INTO statutory_delegation_operations
                (id, action, delegation_id, expected_lifecycle_revision, principal_party_id, initiator_party_id,
                 request_key, rule_snapshot, frozen_payload, created_at, expires_at, status)
            VALUES (:id, :action, :delegation, :revision, :principal, :initiator, :key, :rule, :payload,
                    :created, :expires, 'COLLECTING')
            ON CONFLICT (principal_party_id, initiator_party_id, action, request_key) DO NOTHING
        """
    }
}
