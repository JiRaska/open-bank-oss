// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.persistence.repository

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.party.application.port.out.RepresentationPolicyRepository
import com.openbank.party.domain.model.EligibleRepresentative
import com.openbank.party.domain.model.RepresentationPolicyMode
import com.openbank.party.domain.model.RepresentationPolicySnapshot
import com.openbank.party.infrastructure.persistence.entity.RepresentationPolicyEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class RepresentationPolicyRepositoryImpl(private val mapper: ObjectMapper) :
    RepresentationPolicyRepository,
    PanacheRepository<RepresentationPolicyEntity> {

    override suspend fun allocateRevision(): Long {
        val value = Panache.withSession {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery(
                    "SELECT nextval('party_representation_policy_revisions_seq')",
                    java.lang.Long::class.java,
                ).singleResult
            }
        }.awaitSuspending()
        return (value as Number).toLong()
    }

    override suspend fun insert(snapshot: RepresentationPolicySnapshot): RepresentationPolicySnapshot {
        Panache.withTransaction { persist(toEntity(snapshot)) }.awaitSuspending()
        return snapshot
    }

    internal fun toEntity(snapshot: RepresentationPolicySnapshot): RepresentationPolicyEntity =
        RepresentationPolicyEntity().apply {
            policyId = snapshot.id
            principalPartyId = snapshot.principalPartyId
            revision = snapshot.revision
            sourceCaseId = snapshot.sourceCaseId
            attestationId = snapshot.attestationId
            ruleTextHash = snapshot.ruleTextHash
            registrySource = snapshot.registrySource
            registrySourceRef = snapshot.registrySourceRef
            registryRepresentativeCount = snapshot.registryRepresentativeCount
            mode = snapshot.mode.name
            requiredSignatures = snapshot.requiredSignatures
            requiredOfficesJson = mapper.writeValueAsString(snapshot.requiredOffices)
            eligibleRepresentativesJson = mapper.writeValueAsString(snapshot.eligibleRepresentatives)
            evidenceRef = snapshot.evidenceRef
            effectiveFrom = snapshot.effectiveFrom
        }

    override suspend fun findById(id: UUID): RepresentationPolicySnapshot? =
        Panache.withSession { find("policyId", id).firstResult() }.awaitSuspending()?.let(::fromEntity)

    override suspend fun findBySourceCaseId(sourceCaseId: UUID): RepresentationPolicySnapshot? =
        Panache.withSession { find("sourceCaseId", sourceCaseId).firstResult() }.awaitSuspending()?.let(::fromEntity)

    override suspend fun findLatestEffective(principalPartyId: UUID): RepresentationPolicySnapshot? =
        Panache.withSession {
            find("principalPartyId = ?1 order by effectiveFrom desc, revision desc", principalPartyId).firstResult()
        }.awaitSuspending()?.let(::fromEntity)

    internal fun fromEntity(entity: RepresentationPolicyEntity) = RepresentationPolicySnapshot(
        id = entity.policyId,
        principalPartyId = entity.principalPartyId,
        revision = entity.revision,
        sourceCaseId = entity.sourceCaseId,
        attestationId = entity.attestationId,
        ruleTextHash = entity.ruleTextHash.trim(),
        registrySource = entity.registrySource,
        registrySourceRef = entity.registrySourceRef,
        registryRepresentativeCount = entity.registryRepresentativeCount,
        mode = RepresentationPolicyMode.valueOf(entity.mode),
        requiredSignatures = entity.requiredSignatures,
        requiredOffices = mapper.readValue(entity.requiredOfficesJson, object : TypeReference<List<String>>() {}),
        eligibleRepresentatives = mapper.readValue(
            entity.eligibleRepresentativesJson,
            object : TypeReference<List<EligibleRepresentative>>() {},
        ),
        evidenceRef = entity.evidenceRef,
        effectiveFrom = entity.effectiveFrom,
    )
}
