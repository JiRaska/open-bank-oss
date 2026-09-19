// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.delegation.infrastructure.persistence.repository

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.delegation.application.port.out.AppliedChange
import com.openbank.delegation.application.port.out.BusinessSigningRepository
import com.openbank.delegation.application.port.out.DelegationOutboxRepository
import com.openbank.delegation.application.port.out.Transition
import com.openbank.delegation.application.port.out.TransitionContext
import com.openbank.delegation.application.usecase.BusinessSigningService
import com.openbank.delegation.domain.model.ApprovalKind
import com.openbank.delegation.domain.model.ApprovalRejection
import com.openbank.delegation.domain.model.ApprovalRequest
import com.openbank.delegation.domain.model.ApprovalSignature
import com.openbank.delegation.domain.model.ApprovalStatus
import com.openbank.delegation.domain.model.SignerGroup
import com.openbank.delegation.domain.model.SigningAmount
import com.openbank.delegation.domain.model.SigningPolicy
import com.openbank.delegation.domain.model.TrustedPayee
import com.openbank.delegation.domain.model.TrustedPayeeStatus
import com.openbank.delegation.infrastructure.persistence.entity.ApprovalRequestEntity
import com.openbank.delegation.infrastructure.persistence.entity.ApprovalSignatureEntity
import com.openbank.delegation.infrastructure.persistence.entity.SignerGroupEntity
import com.openbank.delegation.infrastructure.persistence.entity.SigningPolicyEntity
import com.openbank.delegation.infrastructure.persistence.entity.TrustedPayeeEntity
import com.openbank.libs.domain.event.DomainEvent
import com.openbank.libs.persistence.outbox.OutboxMessage
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.LockModeType
import org.hibernate.reactive.mutiny.Mutiny
import java.time.Instant
import java.util.UUID

@ApplicationScoped
// MagicNumber: positional query parameters; one sealed `when` over the applied-change kinds.
@Suppress("TooManyFunctions", "CyclomaticComplexMethod", "MagicNumber", "SpreadOperator", "LongMethod")
class BusinessSigningRepositoryImpl(
    private val outbox: DelegationOutboxRepository,
    private val objectMapper: ObjectMapper,
) : BusinessSigningRepository {

    override suspend fun findPolicy(entityPartyId: UUID): SigningPolicy? = Panache.withSession {
        Panache.getSession().flatMap { it.find(SigningPolicyEntity::class.java, entityPartyId) }
    }.awaitSuspending()?.toDomain()

    override suspend fun listGroups(entityPartyId: UUID): List<SignerGroup> = Panache.withSession {
        Panache.getSession().flatMap { s ->
            s.createSelectionQuery(
                "from SignerGroupEntity where entityPartyId = ?1 order by id",
                SignerGroupEntity::class.java,
            )
                .setParameter(1, entityPartyId)
                .resultList
        }
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun listActivePayees(entityPartyId: UUID): List<TrustedPayee> = Panache.withSession {
        Panache.getSession().flatMap { s ->
            s.createSelectionQuery(
                "from TrustedPayeeEntity where entityPartyId = ?1 and status = ?2 order by addedAt",
                TrustedPayeeEntity::class.java,
            ).setParameter(1, entityPartyId).setParameter(2, TrustedPayeeStatus.ACTIVE.name).resultList
        }
    }.awaitSuspending().map { it.toDomain() }

    override suspend fun findPayee(entityPartyId: UUID, payeeId: UUID): TrustedPayee? = Panache.withSession {
        Panache.getSession().flatMap { it.find(TrustedPayeeEntity::class.java, payeeId) }
    }.awaitSuspending()?.takeIf { it.entityPartyId == entityPartyId }?.toDomain()

    override suspend fun create(request: ApprovalRequest, events: List<DomainEvent>) {
        Panache.withTransaction {
            Panache.getSession().flatMap { s ->
                s.persist(request.toEntity())
                    .chain { _ -> persistSignatures(s, request, emptySet()) }
                    .chain { _ -> persistEvents(events) }
            }
        }.awaitSuspending()
    }

    override suspend fun find(entityPartyId: UUID, id: UUID): ApprovalRequest? = Panache.withSession {
        Panache.getSession().flatMap { s ->
            s.find(ApprovalRequestEntity::class.java, id).flatMap { row ->
                if (row == null || row.entityPartyId != entityPartyId) {
                    Uni.createFrom().nullItem()
                } else {
                    signatures(s, row.id).map { row.toDomain(it) }
                }
            }
        }
    }.awaitSuspending()

    override suspend fun findBySignatureChallenge(scaChallengeId: UUID): ApprovalRequest? = Panache.withSession {
        Panache.getSession().flatMap { s ->
            s.createSelectionQuery(
                "from ApprovalSignatureEntity where scaChallengeId = ?1",
                ApprovalSignatureEntity::class.java,
            )
                .setParameter(1, scaChallengeId).singleResultOrNull.flatMap { sig ->
                    if (sig == null) {
                        Uni.createFrom().nullItem()
                    } else {
                        s.find(ApprovalRequestEntity::class.java, sig.approvalRequestId).flatMap { row ->
                            signatures(s, row.id).map { row.toDomain(it) }
                        }
                    }
                }
        }
    }.awaitSuspending()

    override suspend fun list(entityPartyId: UUID, status: ApprovalStatus?, limit: Int): List<ApprovalRequest> =
        Panache.withSession {
            Panache.getSession().flatMap { s ->
                val query = if (status == null) {
                    s.createSelectionQuery(
                        "from ApprovalRequestEntity where entityPartyId = ?1 order by createdAt desc",
                        ApprovalRequestEntity::class.java,
                    ).setParameter(1, entityPartyId)
                } else {
                    s.createSelectionQuery(
                        "from ApprovalRequestEntity where entityPartyId = ?1 and status = ?2 order by createdAt desc",
                        ApprovalRequestEntity::class.java,
                    ).setParameter(1, entityPartyId).setParameter(2, status.name)
                }
                query.setMaxResults(limit).resultList.flatMap { rows -> hydrate(s, rows) }
            }
        }.awaitSuspending()

    override suspend fun listPending(entityPartyIds: Set<UUID>, limit: Int): List<ApprovalRequest> =
        Panache.withSession {
            Panache.getSession().flatMap { s ->
                s.createSelectionQuery(
                    "from ApprovalRequestEntity where entityPartyId in ?1 and status = ?2 order by expiresAt asc",
                    ApprovalRequestEntity::class.java,
                ).setParameter(1, entityPartyIds).setParameter(2, ApprovalStatus.PENDING.name)
                    .setMaxResults(limit).resultList.flatMap { rows -> hydrate(s, rows) }
            }
        }.awaitSuspending()

    override suspend fun transition(
        entityPartyId: UUID,
        id: UUID,
        decide: (ApprovalRequest, TransitionContext) -> Transition,
    ): ApprovalRequest = Panache.withTransaction {
        Panache.getSession().flatMap { s ->
            // The row lock is the serialisation point: two signers finishing a round at once, or a
            // signature racing the expiry sweep, see each other's committed state.
            s.find(ApprovalRequestEntity::class.java, id, LockModeType.PESSIMISTIC_WRITE).flatMap { row ->
                if (row == null || row.entityPartyId != entityPartyId) {
                    return@flatMap Uni.createFrom().failure(
                        BusinessSigningService.refused(
                            BusinessSigningService.NOT_FOUND,
                            "APPROVAL_NOT_FOUND",
                            "approval request $id not found",
                        ),
                    )
                }
                signatures(s, row.id).flatMap { sigs ->
                    // Locked HERE, on first load: the policy row is the serialisation point for two
                    // POLICY_CHANGE rounds completing at once, and a later lock UPGRADE on an
                    // already-loaded row is what Hibernate Reactive renders with the property name
                    // instead of the column (`select entityPartyId … for no key update`, 42703).
                    s.find(
                        SigningPolicyEntity::class.java,
                        entityPartyId,
                        LockModeType.PESSIMISTIC_WRITE,
                    ).flatMap { policy ->
                        val before = row.toDomain(sigs)
                        val result = decide(before, TransitionContext(policy?.version ?: 0))
                        row.applyFrom(result.next)
                        persistSignatures(s, result.next, before.signerIds)
                            .chain { _ -> applyChange(s, result.change) }
                            .chain { _ -> persistEvents(result.events) }
                            .replaceWith(result.next)
                    }
                }
            }
        }
    }.awaitSuspending()

    override suspend fun claimRelease(entityPartyId: UUID, id: UUID, claimToken: UUID, now: Instant): Boolean =
        Panache.withTransaction {
            Panache.getSession().flatMap { s ->
                s.createMutationQuery(
                    """
                    update ApprovalRequestEntity
                       set status = ?1, claimToken = ?2, releasedAt = ?3, updatedAt = ?3
                     where id = ?4 and entityPartyId = ?5 and kind = ?6 and status = ?7 and expiresAt > ?3
                    """.trimIndent(),
                ).setParameter(1, ApprovalStatus.RELEASED.name)
                    .setParameter(2, claimToken)
                    .setParameter(3, now)
                    .setParameter(4, id)
                    .setParameter(5, entityPartyId)
                    .setParameter(6, ApprovalKind.PAYMENT.name)
                    .setParameter(7, ApprovalStatus.APPROVED.name)
                    .executeUpdate()
            }
        }.awaitSuspending() == 1

    override suspend fun findExpirable(now: Instant, limit: Int): List<ApprovalRequest> = Panache.withSession {
        Panache.getSession().flatMap { s ->
            s.createSelectionQuery(
                "from ApprovalRequestEntity where status in ?1 and expiresAt <= ?2 order by expiresAt asc",
                ApprovalRequestEntity::class.java,
            ).setParameter(
                1,
                listOf(
                    ApprovalStatus.AWAITING_INITIATOR.name,
                    ApprovalStatus.PENDING.name,
                    ApprovalStatus.APPROVED.name,
                ),
            )
                .setParameter(2, now)
                .setMaxResults(limit).resultList.flatMap { rows -> hydrate(s, rows) }
        }
    }.awaitSuspending()

    // ------------------------------------------------------------------------------------ helpers

    private fun hydrate(s: Mutiny.Session, rows: List<ApprovalRequestEntity>): Uni<List<ApprovalRequest>> {
        if (rows.isEmpty()) return Uni.createFrom().item(emptyList())
        return s.createSelectionQuery(
            "from ApprovalSignatureEntity where approvalRequestId in ?1 order by signedAt asc",
            ApprovalSignatureEntity::class.java,
        ).setParameter(1, rows.map { it.id }).resultList.map { sigs ->
            val byRequest = sigs.groupBy { it.approvalRequestId }
            rows.map { row -> row.toDomain(byRequest[row.id].orEmpty()) }
        }
    }

    private fun signatures(s: Mutiny.Session, requestId: UUID): Uni<List<ApprovalSignatureEntity>> =
        s.createSelectionQuery(
            "from ApprovalSignatureEntity where approvalRequestId = ?1 order by signedAt asc",
            ApprovalSignatureEntity::class.java,
        ).setParameter(1, requestId).resultList

    private fun persistSignatures(s: Mutiny.Session, request: ApprovalRequest, existing: Set<UUID>): Uni<Void> {
        val added = request.signatures.filter { it.partyId !in existing }.map { sig ->
            ApprovalSignatureEntity().also {
                it.id = UUID.randomUUID()
                it.approvalRequestId = request.id
                it.partyId = sig.partyId
                it.scaChallengeId = sig.scaChallengeId
                it.signedAt = sig.at
            }
        }
        return if (added.isEmpty()) Uni.createFrom().voidItem() else s.persistAll(*added.toTypedArray())
    }

    private fun persistEvents(events: List<DomainEvent>): Uni<Void> = events.fold(Uni.createFrom().voidItem()) {
            acc,
            event,
        ->
        acc.chain { _ -> outbox.persistInTransaction(outboxMessage(event)) }
    }

    private fun applyChange(s: Mutiny.Session, change: AppliedChange?): Uni<Void> = when (change) {
        null -> Uni.createFrom().voidItem()
        // Already loaded and locked by transition(); this returns the managed row.
        is AppliedChange.ReplacePolicy -> s.find(SigningPolicyEntity::class.java, change.policy.entityPartyId)
            .flatMap { existing ->
                val row = existing ?: SigningPolicyEntity().also { it.entityPartyId = change.policy.entityPartyId }
                row.version = change.policy.version
                row.rulesJson =
                    objectMapper.writeValueAsString(
                        change.policy.rules.map {
                            with(BusinessSigningService) { it.toMap() }
                        },
                    )
                row.trustedPayeeCapAmount = change.policy.trustedPayeeCap?.amount
                row.trustedPayeeCapCurrency = change.policy.trustedPayeeCap?.currency
                row.updatedAt = change.policy.updatedAt ?: Instant.now()
                row.updatedByApprovalId = change.policy.updatedByApprovalId
                if (existing == null) s.persist(row) else Uni.createFrom().voidItem()
            }
        is AppliedChange.UpsertGroup -> s.createSelectionQuery(
            "from SignerGroupEntity where entityPartyId = ?1 and id = ?2",
            SignerGroupEntity::class.java,
        ).setParameter(
            1,
            change.group.entityPartyId,
        ).setParameter(2, change.group.id).singleResultOrNull.flatMap { existing ->
            val row = existing ?: SignerGroupEntity().also {
                it.rowId = UUID.randomUUID()
                it.id = change.group.id
                it.entityPartyId = change.group.entityPartyId
            }
            row.name = change.group.name
            row.memberPartyIds =
                objectMapper.writeValueAsString(change.group.memberPartyIds.map { it.toString() }.sorted())
            row.updatedAt = change.at
            row.updatedByApprovalId = change.approvalId
            val saved = if (existing == null) s.persist(row) else Uni.createFrom().voidItem()
            // Membership changes who may sign, so it moves the policy version: a POLICY_CHANGE
            // prepared before it is refused as SUPERSEDED instead of being applied blind.
            saved.chain { _ ->
                s.find(SigningPolicyEntity::class.java, change.group.entityPartyId)
                    .invoke { policy -> policy?.let { it.version += 1 } }
                    .replaceWithVoid()
            }
        }
        is AppliedChange.AddPayee -> s.persist(
            TrustedPayeeEntity().also {
                it.id = change.payee.id
                it.entityPartyId = change.payee.entityPartyId
                it.iban = change.payee.iban
                it.name = change.payee.name
                it.bic = change.payee.bic
                it.status = TrustedPayeeStatus.ACTIVE.name
                it.addedAt = change.payee.addedAt
                it.addedByApprovalId = change.payee.addedByApprovalId
            },
        )
        is AppliedChange.RemovePayee -> s.find(
            TrustedPayeeEntity::class.java,
            change.payeeId,
            LockModeType.PESSIMISTIC_WRITE,
        )
            .invoke { payee ->
                if (payee != null && payee.status == TrustedPayeeStatus.ACTIVE.name) {
                    payee.status = TrustedPayeeStatus.REMOVED.name
                    payee.removedAt = change.at
                    payee.removedByApprovalId = change.approvalId
                }
            }.replaceWithVoid()
    }

    private fun outboxMessage(event: DomainEvent) = OutboxMessage(
        aggregateId = event.aggregateId,
        eventType = event.eventType,
        payload = objectMapper.writeValueAsString(event),
        createdAt = event.occurredAt,
    )

    private fun ids(json: String?): Set<UUID> =
        json?.let { objectMapper.readValue(it, STRINGS) }.orEmpty().mapTo(linkedSetOf()) { UUID.fromString(it) }

    private fun idsJson(ids: Set<UUID>): String = objectMapper.writeValueAsString(ids.map { it.toString() }.sorted())

    private fun ApprovalRequest.toEntity() = ApprovalRequestEntity().also {
        it.id = id
        it.entityPartyId = entityPartyId
        it.kind = kind.name
        it.payload = payload
        it.payloadSha256 = payloadSha256
        it.summaryJson = summary
        it.policyVersion = policyVersion
        it.requiredSignatures = required
        it.eligibleSignerIds = idsJson(eligibleSignerIds)
        it.mustIncludeGroupId = mustIncludeGroupId
        it.mustIncludeSignerIds = idsJson(mustIncludeSignerIds)
        it.initiatorPartyId = initiatorPartyId
        it.entityName = entityName
        it.initiatorName = initiatorName
        it.expiresAt = expiresAt
        it.createdAt = createdAt
        it.applyFrom(this)
    }

    private fun ApprovalRequestEntity.applyFrom(r: ApprovalRequest) {
        status = r.status.name
        rejectedByPartyId = r.rejection?.partyId
        rejectionReason = r.rejection?.reason
        rejectedAt = r.rejection?.at
        claimToken = r.claimToken ?: claimToken
        releasedAt = r.releasedAt ?: releasedAt
        releaseRef = r.releaseRef
        releaseError = r.releaseError
        expiresAt = r.expiresAt
        updatedAt = Instant.now()
    }

    private fun ApprovalRequestEntity.toDomain(sigs: List<ApprovalSignatureEntity>) = ApprovalRequest(
        id = id,
        entityPartyId = entityPartyId,
        kind = ApprovalKind.valueOf(kind),
        payload = payload,
        payloadSha256 = payloadSha256.trim(),
        summary = summaryJson,
        policyVersion = policyVersion,
        required = requiredSignatures,
        eligibleSignerIds = ids(eligibleSignerIds),
        mustIncludeGroupId = mustIncludeGroupId,
        mustIncludeSignerIds = ids(mustIncludeSignerIds),
        initiatorPartyId = initiatorPartyId,
        signatures = sigs.map { ApprovalSignature(it.partyId, it.scaChallengeId, it.signedAt) },
        status = ApprovalStatus.valueOf(status),
        rejection = rejectedAt?.let { ApprovalRejection(rejectedByPartyId, rejectionReason, it) },
        expiresAt = expiresAt,
        createdAt = createdAt,
        releasedAt = releasedAt,
        releaseRef = releaseRef,
        releaseError = releaseError,
        claimToken = claimToken,
        entityName = entityName,
        initiatorName = initiatorName,
    )

    @Suppress("UNCHECKED_CAST")
    private fun SigningPolicyEntity.toDomain() = SigningPolicy(
        entityPartyId = entityPartyId,
        version = version,
        rules = (objectMapper.readValue(rulesJson, List::class.java) as List<Map<String, Any?>>).map {
            BusinessSigningService.ruleFromMap(it)
        },
        trustedPayeeCap = trustedPayeeCapAmount?.let {
            SigningAmount(it.stripTrailingZeros(), trustedPayeeCapCurrency!!)
        },
        updatedAt = updatedAt,
        updatedByApprovalId = updatedByApprovalId,
    )

    private fun SignerGroupEntity.toDomain() = SignerGroup(id, entityPartyId, name, ids(memberPartyIds))

    private fun TrustedPayeeEntity.toDomain() = TrustedPayee(
        id = id,
        entityPartyId = entityPartyId,
        iban = iban,
        name = name,
        bic = bic,
        addedAt = addedAt,
        addedByApprovalId = addedByApprovalId,
        status = TrustedPayeeStatus.valueOf(status),
    )

    private companion object {
        val STRINGS = object : TypeReference<List<String>>() {}
    }
}
