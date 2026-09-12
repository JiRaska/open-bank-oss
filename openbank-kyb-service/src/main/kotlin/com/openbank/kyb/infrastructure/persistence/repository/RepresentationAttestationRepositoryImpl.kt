// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

package com.openbank.kyb.infrastructure.persistence.repository

import com.openbank.kyb.application.port.out.RepresentationAttestationRepository
import com.openbank.kyb.domain.model.IdentifierScheme
import com.openbank.kyb.domain.model.LegalEntityIdentifier
import com.openbank.kyb.domain.model.RepresentationAttestation
import com.openbank.kyb.domain.model.RepresentationMode
import com.openbank.kyb.infrastructure.persistence.entity.RepresentationAttestationEntity
import io.quarkus.hibernate.reactive.panache.Panache
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepository
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class RepresentationAttestationRepositoryImpl :
    RepresentationAttestationRepository,
    PanacheRepository<RepresentationAttestationEntity> {

    override suspend fun findActive(
        identifier: LegalEntityIdentifier,
        ruleTextHash: String,
    ): RepresentationAttestation? = Panache.withSession {
        find(
            "identifierScheme = ?1 and identifierValue = ?2 and ruleTextHash = ?3 and supersededAt is null",
            identifier.scheme.name,
            identifier.value,
            ruleTextHash,
        ).firstResult()
    }.awaitSuspending()?.toDomain()

    override suspend fun findLatestFor(identifier: LegalEntityIdentifier): RepresentationAttestation? =
        Panache.withSession {
            find(
                "identifierScheme = ?1 and identifierValue = ?2 and supersededAt is null order by attestedAt desc",
                identifier.scheme.name,
                identifier.value,
            ).firstResult()
        }.awaitSuspending()?.toDomain()

    /**
     * Supersede-then-insert in ONE transaction. Two statements, and the partial unique index makes
     * the ordering load-bearing: inserting first would collide with the row it is about to retire
     * whenever an operator re-confirms the same text.
     */
    override suspend fun attest(attestation: RepresentationAttestation): RepresentationAttestation =
        Panache.withTransaction {
            update(
                "supersededAt = ?1 where identifierScheme = ?2 and identifierValue = ?3 and supersededAt is null",
                attestation.attestedAt,
                attestation.identifier.scheme.name,
                attestation.identifier.value,
            ).flatMap { persist(entity(attestation)) }
        }.awaitSuspending().let { attestation }

    override suspend fun listFor(identifier: LegalEntityIdentifier): List<RepresentationAttestation> =
        Panache.withSession {
            list(
                "identifierScheme = ?1 and identifierValue = ?2 order by attestedAt desc",
                identifier.scheme.name,
                identifier.value,
            )
        }.awaitSuspending().map { it.toDomain() }

    private fun entity(a: RepresentationAttestation) = RepresentationAttestationEntity().apply {
        attestationId = a.id
        identifierScheme = a.identifier.scheme.name
        identifierValue = a.identifier.value
        ruleTextHash = a.ruleTextHash
        ruleText = a.ruleText
        parsedMode = a.parsedMode.name
        parsedSigners = a.parsedSigners
        confirmedSigners = a.confirmedSigners
        confirmedRoles = KybJson.writeStrings(a.confirmedRoles)
        attestedBy = a.attestedBy
        attestedAt = a.attestedAt
        supersededAt = a.supersededAt
        note = a.note
    }

    private fun RepresentationAttestationEntity.toDomain() = RepresentationAttestation(
        id = attestationId,
        identifier = LegalEntityIdentifier.of(IdentifierScheme.valueOf(identifierScheme), identifierValue),
        ruleTextHash = ruleTextHash,
        ruleText = ruleText,
        parsedMode = RepresentationMode.valueOf(parsedMode),
        parsedSigners = parsedSigners,
        confirmedSigners = confirmedSigners,
        confirmedRoles = KybJson.readStrings(confirmedRoles),
        attestedBy = attestedBy,
        attestedAt = attestedAt,
        supersededAt = supersededAt,
        note = note,
    )
}
