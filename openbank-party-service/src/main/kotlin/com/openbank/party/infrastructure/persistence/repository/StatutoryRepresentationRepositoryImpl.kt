// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.party.infrastructure.persistence.repository

import com.fasterxml.jackson.databind.ObjectMapper
import com.openbank.party.application.port.out.StatutoryRepresentationRepository
import com.openbank.party.domain.model.StatutoryRepresentationRule
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "party_statutory_representation_rules")
class StatutoryRepresentationRuleEntity {
    @Id
    @Column(name = "attestation_id", nullable = false, updatable = false)
    lateinit var attestationId: UUID

    @Column(name = "principal_party_id", nullable = false, updatable = false)
    lateinit var principalPartyId: UUID

    @Column(name = "attested_at", nullable = false, updatable = false)
    lateinit var attestedAt: Instant

    @Column(name = "snapshot", nullable = false, updatable = false, columnDefinition = "TEXT")
    lateinit var snapshot: String
}

@ApplicationScoped
class StatutoryRepresentationRepositoryImpl(private val mapper: ObjectMapper) :
    PanacheRepositoryBase<StatutoryRepresentationRuleEntity, UUID>,
    StatutoryRepresentationRepository {

    override suspend fun insertOrRead(rule: StatutoryRepresentationRule): StatutoryRepresentationRule =
        Panache.withTransaction {
            Panache.getSession().flatMap { session ->
                session.createNativeQuery<Any>(INSERT_SQL)
                    .setParameter("attestationId", rule.evidence.attestationId)
                    .setParameter("principalPartyId", rule.principalPartyId)
                    .setParameter("attestedAt", rule.evidence.attestedAt)
                    .setParameter("snapshot", mapper.writeValueAsString(rule))
                    .executeUpdate()
                    .flatMap {
                        session.find(StatutoryRepresentationRuleEntity::class.java, rule.evidence.attestationId)
                    }
            }
        }.awaitSuspending().let { decode(requireNotNull(it)) }

    override suspend fun findLatest(principalPartyId: UUID): StatutoryRepresentationRule? = Panache.withSession {
        find("principalPartyId = ?1 order by attestedAt desc", principalPartyId).firstResult()
    }.awaitSuspending()?.let(::decode)

    private fun decode(entity: StatutoryRepresentationRuleEntity) =
        mapper.readValue(entity.snapshot, StatutoryRepresentationRule::class.java)

    private companion object {
        const val INSERT_SQL = """
            INSERT INTO party_statutory_representation_rules
                (attestation_id, principal_party_id, attested_at, snapshot)
            VALUES (:attestationId, :principalPartyId, :attestedAt, :snapshot)
            ON CONFLICT (attestation_id) DO NOTHING
        """
    }
}
