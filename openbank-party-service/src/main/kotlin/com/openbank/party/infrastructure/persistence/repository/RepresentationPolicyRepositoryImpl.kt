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

    override suspend fun insert(snapshot: RepresentationPolicySnapshot): RepresentationPolicySnapshot {
        val entity = RepresentationPolicyEntity().apply {
            policyId = snapshot.id
            principalPartyId = snapshot.principalPartyId
            revision = snapshot.revision
            sourceCaseId = snapshot.sourceCaseId
            ruleTextHash = snapshot.ruleTextHash
            mode = snapshot.mode.name
            requiredSignatures = snapshot.requiredSignatures
            requiredOfficesJson = mapper.writeValueAsString(snapshot.requiredOffices)
            eligibleRepresentativesJson = mapper.writeValueAsString(snapshot.eligibleRepresentatives)
            evidenceRef = snapshot.evidenceRef
            effectiveFrom = snapshot.effectiveFrom
        }
        Panache.withTransaction { persist(entity) }.awaitSuspending()
        return snapshot
    }

    override suspend fun findById(id: UUID): RepresentationPolicySnapshot? =
        Panache.withSession { find("policyId", id).firstResult() }.awaitSuspending()?.toDomain()

    override suspend fun findBySourceCaseId(sourceCaseId: UUID): RepresentationPolicySnapshot? =
        Panache.withSession { find("sourceCaseId", sourceCaseId).firstResult() }.awaitSuspending()?.toDomain()

    private fun RepresentationPolicyEntity.toDomain() = RepresentationPolicySnapshot(
        id = policyId,
        principalPartyId = principalPartyId,
        revision = revision,
        sourceCaseId = sourceCaseId,
        ruleTextHash = ruleTextHash.trim(),
        mode = RepresentationPolicyMode.valueOf(mode),
        requiredSignatures = requiredSignatures,
        requiredOffices = mapper.readValue(requiredOfficesJson, object : TypeReference<List<String>>() {}),
        eligibleRepresentatives = mapper.readValue(
            eligibleRepresentativesJson,
            object : TypeReference<List<EligibleRepresentative>>() {},
        ),
        evidenceRef = evidenceRef,
        effectiveFrom = effectiveFrom,
    )
}
