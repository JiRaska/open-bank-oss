// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.persistence.repository

import com.openbank.libs.domain.identifiers.Ids
import com.openbank.party.application.port.out.KybSignedCaseProjection
import com.openbank.party.application.port.out.KybSignedCaseProjectionRepository
import com.openbank.party.domain.model.MandateAuthority
import com.openbank.party.domain.model.MandateRole
import com.openbank.party.domain.model.MandateSource
import com.openbank.party.domain.model.MandateStatus
import com.openbank.party.domain.model.PartyMandate
import com.openbank.party.domain.model.RepresentationPolicySnapshot
import com.openbank.party.infrastructure.persistence.entity.KybSignedCaseProjectionEntity
import com.openbank.party.infrastructure.persistence.entity.RepresentationPolicyEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import org.hibernate.reactive.mutiny.Mutiny

/** Case marker, every mandate, every mandate outbox event and optional rule in one DB transaction. */
@ApplicationScoped
class KybSignedCaseProjectionRepositoryImpl(
    private val mandates: PartyMandateRepositoryImpl,
    private val policies: RepresentationPolicyRepositoryImpl,
) : KybSignedCaseProjectionRepository {
    override suspend fun project(case: KybSignedCaseProjection): Boolean = Panache.withTransaction {
        Panache.getSession().flatMap { session ->
            session.find(KybSignedCaseProjectionEntity::class.java, case.caseId).flatMap { existing ->
                if (existing != null) {
                    require(existing.payloadHash.trim() == case.payloadHash) {
                        "conflicting signed KYB evidence for case ${case.caseId}"
                    }
                    Uni.createFrom().item(false)
                } else {
                    val marker = KybSignedCaseProjectionEntity().apply {
                        caseId = case.caseId
                        payloadHash = case.payloadHash
                        mandateCount = case.holders.size
                        projectedAt = case.occurredAt
                    }
                    session.persist(marker)
                        .flatMap { projectMandates(session, case) }
                        .flatMap { projectPolicy(session, case.policy) }
                        .replaceWith(true)
                }
            }
        }
    }.awaitSuspending()

    private fun projectMandates(session: Mutiny.Session, case: KybSignedCaseProjection): Uni<Void> =
        case.holders.fold(Uni.createFrom().voidItem()) { previous, holder ->
            previous.flatMap {
                val mandate = PartyMandate(
                    id = Ids.newId(),
                    principalPartyId = case.principalPartyId,
                    agentPartyId = holder.partyId,
                    role = if (case.soleTrader) MandateRole.OWNER else MandateRole.LEGAL_REPRESENTATIVE,
                    authority = if (case.requiredSignatures == 1) MandateAuthority.SOLE else MandateAuthority.JOINT,
                    requiredSignatures = case.requiredSignatures,
                    source = if (holder.registryRepresentativeIndex == null) {
                        MandateSource.POWER_OF_ATTORNEY
                    } else {
                        MandateSource.REGISTRY
                    },
                    status = MandateStatus.ACTIVE,
                    evidenceRef = "kyb-case:${case.caseId}:signer:${holder.signerId}",
                    validFrom = case.occurredAt,
                    validTo = null,
                    createdAt = case.occurredAt,
                    updatedAt = case.occurredAt,
                )
                mandates.upsertSignedInTransaction(session, case.caseId, mandate)
            }
        }

    private fun projectPolicy(session: Mutiny.Session, policy: RepresentationPolicySnapshot?): Uni<Void> {
        if (policy == null) return Uni.createFrom().voidItem()
        return session.createQuery(
            "FROM RepresentationPolicyEntity WHERE sourceCaseId = :caseId",
            RepresentationPolicyEntity::class.java,
        ).setParameter("caseId", policy.sourceCaseId).singleResultOrNull.flatMap { existing ->
            if (existing != null) {
                val saved = policies.fromEntity(existing)
                require(policy.copy(id = saved.id, revision = saved.revision) == saved) {
                    "conflicting statutory policy for KYB case ${policy.sourceCaseId}"
                }
                Uni.createFrom().voidItem()
            } else {
                session.createNativeQuery(
                    "SELECT nextval('party_representation_policy_revisions_seq')",
                    java.lang.Long::class.java,
                ).singleResult.flatMap { revision ->
                    session.persist(policies.toEntity(policy.copy(revision = (revision as Number).toLong())))
                }
            }
        }
    }
}
